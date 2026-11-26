package com.nganyaflow.app

import android.app.Application
import com.nganyaflow.app.feature.alerts.WaitAlertConfig
import com.nganyaflow.app.feature.alerts.WaitAlertStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WaitAlertStoreTest {
    @Test fun expiredAlertsStopAndOldWorkersCannotFinishReplacement() {
        val store = WaitAlertStore(RuntimeEnvironment.getApplication())
        val first = WaitAlertConfig("old", "https://example.test/", "s", "r", "townbound", 5,
            System.currentTimeMillis() - 1, "Route", "Stage", "en")
        store.save(first)
        assertFalse(store.expire()!!.active)
        assertEquals("expired", store.read()!!.result)
        store.save(first.copy(id = "replacement", expiresAt = System.currentTimeMillis() + 7200000))
        assertFalse(store.finish("old", "fired"))
        assertTrue(store.read()!!.active)
        assertTrue(store.finish("replacement", "cancelled"))
        assertFalse(store.read()!!.active)
    }
}
