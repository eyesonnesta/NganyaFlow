package com.nganyaflow.app.core.designsystem

import kotlin.math.ceil
import kotlin.math.floor
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Whole-minute outward rounding preserves the model's interval; zero is never promised. */
fun formatWaitRange(lower: Double, upper: Double, compact: Boolean = false, language: String = "en"): String {
    val sw = language == "sw"
    if (!lower.isFinite() || !upper.isFinite() || lower < 0 || upper < lower) return if(sw) "Hakuna makadirio" else "No estimate"
    if (upper < 1) return if(sw) "Linakaribia sasa" else "Due now"
    val high = ceil(upper).toLong()
    val range = if(lower < 1) "${if(compact) "<1" else if(sw) "Chini ya 1" else "Under 1"}–$high" else "${floor(lower).toLong()}–$high"
    return if(sw) "$range dakika" else "$range min"
}

fun relativeNairobiTime(value: String?, now: Instant, language: String = "en"): String {
    val at = value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return if(language == "sw") "Haijulikani" else "Not observed"
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    val ago = if(language == "sw") { if(minutes == 0L) "Sasa hivi" else "Dakika $minutes zilizopita" }
        else if(minutes == 0L) "Just now" else "$minutes min ago"
    return "$ago (${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Africa/Nairobi")).format(at)})"
}
