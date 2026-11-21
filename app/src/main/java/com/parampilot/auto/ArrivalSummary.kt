package com.parampilot.auto

import java.util.Locale

/**
 * Arrival card content (Feature 2): the destination the comma confirmed, plus
 * the trip's duration and distance taken from the comma's own selected-route
 * totals captured while the trip was tracked. A total the device never
 * provided stays unknown (0) and renders as "—" — never invented.
 */
data class ArrivalSummary(
    val destinationName: String,
    val durationMin: Int = 0,
    val distanceMiles: Double = 0.0
) {
    /** "12 min", "1 h 15 min", "1 h", or "—" when the comma gave no total. */
    fun durationText(): String = when {
        durationMin <= 0 -> "—"
        durationMin < 60 -> "$durationMin min"
        durationMin % 60 == 0 -> "${durationMin / 60} h"
        else -> "${durationMin / 60} h ${durationMin % 60} min"
    }

    /** "3.4 mi", or "—" when the comma gave no total. */
    fun distanceText(): String =
        if (distanceMiles > 0.0) String.format(Locale.US, "%.1f mi", distanceMiles) else "—"

    /** Second card line: trip totals joined the way the trip bar shows them. */
    fun detailText(): String = "${durationText()} · ${distanceText()}"
}
