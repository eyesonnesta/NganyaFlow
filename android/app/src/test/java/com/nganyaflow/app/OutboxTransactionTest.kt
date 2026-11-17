package com.nganyaflow.app

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.nganyaflow.app.core.database.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OutboxTransactionTest {
    @Test fun failedLocalSaveRollsBackDomainRecordAndOutboxTogether() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NganyaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.dao()
        try {
            runCatching {
                db.withTransaction {
                    dao.record(RecordEntity("https://a/", "report-id", "report", "original", createdAt = 1))
                    dao.enqueue(OutboxEntity(server = "https://a/", id = "upload-id", entityId = "report-id", action = "report", groupId = "report:report-id", payload = "original"))
                    error("Simulated local storage failure")
                }
            }
            assertNull(dao.record("https://a/", "report-id"))
            assertTrue(dao.outbox("https://a/").isEmpty())
            db.withTransaction {
                dao.record(RecordEntity("https://a/", "report-id", "report", "original", createdAt = 1))
                dao.enqueue(OutboxEntity(server = "https://a/", id = "upload-id", entityId = "report-id", action = "report", groupId = "report:report-id", payload = "original"))
            }
            val saved = dao.outbox("https://a/").single()
            dao.outboxState(saved.sequence, "Will retry", true, "timeout after acceptance")
            val retry = dao.outbox("https://a/").single()
            assertEquals(saved.id, retry.id); assertEquals(saved.payload, retry.payload)
            assertTrue(retry.attempted)
            assertTrue(dao.outbox("https://b/").isEmpty())
            assertNull(dao.record("https://b/", "report-id"))
        } finally { db.close() }
    }
    @Test fun deletingDataRotatesIdentityAndClearsQueuedReportsAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NganyaDatabase::class.java).allowMainThreadQueries().build()
        val dao = db.dao()
        try {
            dao.identity(IdentityEntity("https://a/", "old-install"))
            dao.record(RecordEntity("https://a/", "report-id", "report", "old", createdAt = 1))
            dao.enqueue(OutboxEntity(server = "https://a/", id = "upload-id", entityId = "report-id", action = "report", groupId = "report:report-id", payload = "old"))
            db.withTransaction {
                dao.deleteReportOutbox("https://a/"); dao.deleteReports("https://a/")
                dao.identity(IdentityEntity("https://a/", "new-install"))
                dao.enqueue(OutboxEntity(server = "https://a/", id = "delete-id", entityId = "request-id", action = "delete", groupId = "install:old-install", payload = "old-install"))
            }
            assertEquals("new-install", dao.identity("https://a/")?.installId)
            assertNull(dao.record("https://a/", "report-id"))
            assertEquals("delete", dao.outbox("https://a/").single().action)
            assertEquals("old-install", dao.outbox("https://a/").single().payload)
        } finally { db.close() }
    }
}
