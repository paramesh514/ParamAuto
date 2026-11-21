package com.parampilot.auto

/**
 * How search results are shaped before they are shown (Param,
 * 2026-11-20, from the field: "search resulting only one business
 * address instead of multiple (like Costco)" and "always sort
 * search results by distance of Phone location").
 *
 * Dedupe: results used to be de-duplicated by "latitude,longitude"
 * alone. Suggestions from Google, Mapbox and the comma carry NO
 * coordinates until one is picked (they sit at 0.0, 0.0), so every
 * suggestion shared one key and all but the first were dropped —
 * a chain like Costco collapsed to a single row. Now only results
 * that actually have coordinates dedupe by position; suggestions
 * dedupe by their own identity (source + place id, else name and
 * address), so every location survives.
 *
 * Ordering: a stable sort by distance from the phone's position.
 * Results without coordinates (unresolved suggestions) cannot be
 * measured, so they keep the order their source gave them, after
 * the measured ones.
 */
object SearchOrdering {

    fun dedupe(results: List<NavDestination>): List<NavDestination> =
        results.distinctBy { key(it) }

    private fun key(dest: NavDestination): String =
        if (dest.hasCoordinates) {
            "geo:${dest.latitude},${dest.longitude}"
        } else {
            "sug:${dest.source}:" +
                (dest.googlePlaceId ?: dest.mapboxId
                    ?: "${dest.name}|${dest.placeName}")
        }

    fun sortByDistance(
        results: List<NavDestination>,
        fromLat: Double,
        fromLng: Double
    ): List<NavDestination> = results.sortedBy { dest ->
        if (dest.hasCoordinates) {
            GuidanceMath.haversineM(
                fromLat, fromLng, dest.latitude, dest.longitude
            )
        } else {
            Double.MAX_VALUE
        }
    }
}
