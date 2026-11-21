package com.parampilot.auto

/**
 * Speed-limit provenance label (Feature 5).
 *
 * TelemetryPacket.fromSg tags the limit's source as "sg" when the comma's
 * own payload carried the limit and "mapbox" when it came from the route's
 * Mapbox maxspeed annotations. The HUD shows that provenance under the sign
 * so a route-derived limit is never mistaken for the comma's own reading.
 *
 * Pure: returns the short label to draw, or null when nothing should show.
 */
object SpeedLimitSource {

    fun label(source: String?, limitMph: Int): String? {
        if (limitMph <= 0) return null
        return when (source?.trim()?.lowercase()) {
            "sg" -> "comma"
            "mapbox" -> "route"
            else -> null
        }
    }
}
