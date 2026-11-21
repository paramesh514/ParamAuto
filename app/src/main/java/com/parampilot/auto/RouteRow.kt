package com.parampilot.auto

import java.util.Locale

/**
 * Row label for the route alternatives picker (Feature 7). Built only
 * from fields [RouteOption] actually carries — the comma's total
 * duration/distance and its local summary — plus the selection marker.
 * A total the device never provided stays absent, never invented.
 */
object RouteRow {

    fun label(route: RouteOption, isSelected: Boolean): String {
        val parts = mutableListOf<String>()
        if (route.durationMin > 0) parts.add("${route.durationMin} min")
        if (route.distanceMiles > 0.0) {
            parts.add(String.format(Locale.US, "%.1f mi", route.distanceMiles))
        }
        if (parts.isEmpty()) {
            // No totals at all: fall back to the route's own summary.
            parts.add(route.summary.ifBlank { "Route ${route.id + 1}" })
        } else if (route.summary.isNotBlank()) {
            parts.add(route.summary)
        }
        if (isSelected) parts.add("Selected")
        return parts.joinToString(" · ")
    }
}
