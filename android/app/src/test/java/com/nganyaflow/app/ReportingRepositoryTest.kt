package com.nganyaflow.app

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import com.nganyaflow.app.core.database.CacheEntity
import com.nganyaflow.app.core.database.NganyaDatabase
import com.nganyaflow.app.core.datastore.AppPreferences
import com.nganyaflow.app.core.datastore.TokenVault
import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.core.network.WireJson
import com.nganyaflow.app.data.NganyaRepository
import com.nganyaflow.app.feature.reporting.eligibleBoardingRoutes
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReportingRepositoryTest {
    @Test fun reviewedUuidSurvivesRetryWithoutDuplicateAndDecliningStoresNothing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NganyaDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = AppPreferences(context)
        val repository = NganyaRepository(db, preferences, TokenVault(context), scope) {}
        try {
            repository.initialize()
            val server = repository.settings.value.serverUrl
            val route = Route("r1", "Route one", stageIds = listOf("s1", "s2"), directions = listOf("townbound", "outbound"))
            val meta = Meta("0.2.0", Instant.now().toString(), consentVersion = "test-consent", demoMode = true)
            db.dao().cache(CacheEntity(server, "meta", WireJson.encodeToString(meta), 0))
            db.dao().cache(CacheEntity(server, "routes", WireJson.encodeToString(listOf(route)), 0))
            // With no explicit consent, confirmation cannot store either a report or upload.
            repository.setConsent(false)
            assertTrue(runCatching { repository.saveReport("s1", "r1", "townbound") }.isFailure)
            assertTrue(db.dao().recordsNow(server, "report").isEmpty())
            assertTrue(db.dao().outbox(server).isEmpty())
            repository.setConsent(true)
            val handle = SavedStateHandle(mapOf("boardingId" to UUID.randomUUID().toString(), "boardingTime" to Instant.now().minusSeconds(5).toString()))
            val id = requireNotNull(handle.get<String>("boardingId"))
            val time = requireNotNull(handle.get<String>("boardingTime"))
            val install = repository.settings.value.installId
            assertEquals(id, repository.saveReport("s1", "r1", "townbound", id, time, server, install))
            val stored = db.dao().record(server, id)
            // Simulate restoration/retry after Room committed but UI never received the result.
            assertEquals(id, repository.saveReport("s1", "r1", "townbound", id, time, server, install))
            assertEquals(stored, db.dao().record(server, id))
            assertEquals(1, db.dao().recordsNow(server, "report").size)
            assertEquals(1, db.dao().outbox(server).size)
            assertTrue(runCatching { repository.saveReport("s1", "r1", "outbound", id, time, server, install) }.isFailure)
            assertTrue(runCatching { repository.saveReport("s1", "r1", "townbound", id, time, "https://changed/", install) }.isFailure)
            assertEquals(1, db.dao().outbox(server).size)
            repository.undoReport(id)
            assertEquals("Deleted", db.dao().record(server, id)?.state)
            assertTrue(db.dao().outbox(server).isEmpty())
        } finally { scope.cancel(); db.close() }
    }
    @Test fun boardingChoicesRespectTerminusDirectionWithoutChoosingOne() {
        val route = Route("r", "Town route", stageIds = listOf("town", "middle", "far"), directions = listOf("townbound", "outbound"), source = "digital-matatus-2019")
        assertTrue(eligibleBoardingRoutes(listOf(route), "town", "townbound").isEmpty())
        assertEquals(listOf(route), eligibleBoardingRoutes(listOf(route), "middle", "townbound"))
        assertTrue(eligibleBoardingRoutes(listOf(route), "far", "outbound").isEmpty())
    }
}
