package com.nganyaflow.app

import com.nganyaflow.app.core.model.*
import com.nganyaflow.app.core.network.*
import com.nganyaflow.app.data.validatedServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class NetworkContractTest {
    @Test fun successAndUnknownFieldsDecode() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok","model_loaded":true,"future_field":1}"""))
            val result = apiResult { createApi(server.url("/").toString()).health() }
            assertTrue(result is ApiResult.Success)
            assertEquals(true, (result as ApiResult.Success).value.modelLoaded)
        }
    }
    @Test fun expectedHttpErrorsKeepCodeAndMessage() = runBlocking {
        MockWebServer().use { server ->
            val api = createApi(server.url("/").toString())
            for(code in listOf(401, 409, 422, 429)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("""{"detail":"specific $code"}"""))
                val result = apiResult { api.health() } as ApiResult.Failure
                assertEquals(code, result.error.code)
                assertEquals("specific $code", result.error.message)
                assertEquals(code == 429, result.error.retryable)
            }
        }
    }
    @Test fun timeoutIsRetryableAndMalformedJsonIsNot() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
            val api = createApi(server.url("/").toString(), client = client)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val timeout = apiResult { api.health() } as ApiResult.Failure
            assertTrue(timeout.error.retryable)
            server.enqueue(MockResponse().setBody("not-json"))
            val malformed = apiResult { api.health() } as ApiResult.Failure
            assertFalse(malformed.error.retryable)
            assertNull(malformed.error.code)
        }
    }
    @Test fun publicCallsNeverCarryBearerAndProtectedCallsDo() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor { "test-only-token" }).build()
            val api = createApi(server.url("/").toString(), client = client)
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            api.health()
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"role":"crew"}"""))
            api.me()
            assertEquals("Bearer test-only-token", server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun retriedReportUsesIdenticalUuidAndContent() = runBlocking {
        MockWebServer().use { server ->
            val id = UUID.randomUUID().toString()
            val original = BoardingReport(id, UUID.randomUUID().toString(), "stage", "route", "townbound", Instant.now().toString(), "consent")
            // This is the same immutable JSON snapshot stored in the outbox.
            val snapshot = WireJson.encodeToString(BoardingReport.serializer(), original)
            val api = createApi(server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(503))
            apiResult { api.reports(ReportBatch(listOf(WireJson.decodeFromString(snapshot)))) }
            server.enqueue(MockResponse().setBody("""{"accepted_ids":[],"duplicate_ids":["$id"]}"""))
            val retry = api.reports(ReportBatch(listOf(WireJson.decodeFromString(snapshot))))
            assertEquals(id, retry.duplicateIds.single())
            assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
        }
    }
    @Test fun numericEstimateDisappearsAtExpiryEvenWithoutNetwork() {
        val deadline = Instant.parse("2026-11-17T10:00:00Z")
        val cached = Estimate("available", lowerMinutes = 2.0, medianMinutes = 4.0, upperMinutes = 6.0, expiresAt = deadline.toString())
        assertEquals("available", cached.current(deadline.minusNanos(1)).status)
        val expired = cached.current(deadline)
        assertEquals("unavailable", expired.status)
        assertNull(expired.lowerMinutes); assertNull(expired.medianMinutes); assertNull(expired.upperMinutes)
        assertEquals("unavailable", cached.copy(expiresAt = "invalid").current(deadline).status)
    }
    @Test fun serverAddressValidationLimitsCleartextAndRejectsCredentials() {
        assertEquals("http://10.0.2.2:8000/", validatedServer("http://10.0.2.2:8000", true))
        assertEquals("https://example.org/", validatedServer("https://example.org", false))
        for(address in listOf("http://example.org", "https://user:secret@example.org", "https://example.org/path", "https://example.org/?token=x")) {
            assertTrue(runCatching { validatedServer(address, true) }.isFailure)
        }
        assertTrue(runCatching { validatedServer("http://10.0.2.2:8000", false) }.isFailure)
    }
}
