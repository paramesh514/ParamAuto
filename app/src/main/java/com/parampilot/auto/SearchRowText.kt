package com.parampilot.auto

import kotlin.math.roundToInt

/**
 * Layer-2 row text for the car search results (Addendum 154,
 * diagnosis A3).
 *
 * Two rules the pre-154 rows broke:
 * - The subtitle fell back to the literal string "Location"
 *   when the source gave no address — a placeholder presented
 *   as information. A row now shows the place's own address
 *   text, or no subtitle at all.
 * - Results carried no ETA and only sometimes a distance,
 *   while the Home/Work rows on the very same screen showed
 *   both. [etaDistanceText] is the same estimate those rows
 *   use: haversine distance from the current GPS, ETA at an
 *   assumed 25 mph (a plain estimate, hence the shared rule).
 */
object SearchRowText {

    /**
     * The address line for a result row: the place's own
     * address/place text when it adds anything beyond the name,
     * else null (no subtitle) — never an invented placeholder.
     */
    fun addressLine(placeName: String, name: String): String? =
        placeName.takeIf { it.isNotEmpty() && it != name }

    /** "12 min · 3.4 mi" for a distance in metres. */
    fun etaDistanceText(distanceM: Double): String {
        val miles = distanceM / Units.METERS_PER_MILE
        val etaMin = (miles / 25.0 * 60.0).roundToInt().coerceAtLeast(1)
        return "$etaMin min · ${formatMiles(miles)}"
    }

    fun formatMiles(miles: Double): String =
        if (miles < 10.0) "%.1f mi".format(miles) else "${miles.roundToInt()} mi"
}
