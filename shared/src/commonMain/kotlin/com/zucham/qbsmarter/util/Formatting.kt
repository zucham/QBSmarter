package com.zucham.qbsmarter.util

/**
 * Shared formatting helpers. Pure-Kotlin to keep them multiplatform –
 * no `String.format` (which has different behaviour across Kotlin targets).
 */

/**
 * Format milliseconds as `mm:ss.cc` (or `s.cc` for sub-minute durations).
 * Used by the Solve screen, History screen, and stat tiles.
 */
fun formatDuration(ms: Long): String {
    val totalCs = ms / 10
    val cs = totalCs % 100
    val totalSec = totalCs / 100
    val s = totalSec % 60
    val m = totalSec / 60
    return if (m > 0) {
        "${m}:${s.toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}"
    } else {
        "${s}.${cs.toString().padStart(2, '0')}"
    }
}

/**
 * Format a turns-per-second value to two decimal places (e.g. `4.27`).
 * Caller appends a unit string if needed (e.g. `" tps"`).
 */
fun formatTps(tps: Double): String {
    val hundredths = (tps * 100).toLong()
    val whole = hundredths / 100
    val frac = (hundredths % 100).let { if (it < 0) -it else it }
    return "${whole}.${frac.toString().padStart(2, '0')}"
}
