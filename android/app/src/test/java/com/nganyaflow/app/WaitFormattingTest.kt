package com.nganyaflow.app
import com.nganyaflow.app.core.designsystem.formatWaitRange
import org.junit.Assert.assertEquals
import org.junit.Test
class WaitFormattingTest {
    @Test fun roundsOutwardWithoutShowingZero() {
        assertEquals("Under 1–4 min", formatWaitRange(.3, 3.4))
        assertEquals("<1–4 min", formatWaitRange(.3, 3.4, compact = true))
        assertEquals("1–4 min", formatWaitRange(1.9, 3.1))
        assertEquals("Due now", formatWaitRange(0.0, .99))
        assertEquals("Under 1–1 min", formatWaitRange(0.0, 1.0))
        assertEquals("2–4 min", formatWaitRange(2.0, 4.0))
        assertEquals("No estimate", formatWaitRange(4.0, 2.0))
        assertEquals("Chini ya 1–4 dakika", formatWaitRange(.3, 3.4, language = "sw"))
    }
}
