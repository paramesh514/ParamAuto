package com.parampilot.auto

import org.json.JSONArray

/**
 * Parser for the comma's own place lists (Feature 8): the nav status
 * snapshot's "recents" and "favorites" arrays hold destination dicts in
 * the same shape as the snapshot's destination ({name, address,
 * latitude, longitude}). Entries without a usable name or valid
 * coordinates can never be sent anywhere, so they are skipped; each list
 * is capped so the search UI stays a short, tappable list.
 */
object CommaPlaces {

    const val MAX_PLACES = 10

    fun parse(array: JSONArray?): List<NavDestination> {
        if (array == null) return emptyList()
        val out = ArrayList<NavDestination>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            val lat = obj.optDouble("latitude", obj.optDouble("lat", Double.NaN))
            val lng = obj.optDouble("longitude", obj.optDouble("lng", Double.NaN))
            if (lat.isNaN() || lng.isNaN()) continue
            if (lat == 0.0 && lng == 0.0) continue
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
            val address = obj.optString("address", "")
                .ifEmpty { obj.optString("placeName", "") }
                .ifEmpty { name }
            out.add(
                NavDestination(
                    name = name,
                    placeName = address,
                    latitude = lat,
                    longitude = lng,
                    source = "comma"
                )
            )
            if (out.size >= MAX_PLACES) break
        }
        return out
    }

    /**
     * Picks the comma-search match for a place the phone could not
     * resolve itself (e.g. a cached Mapbox suggestion tapped while no
     * phone Mapbox token is set): an exact name match wins; otherwise
     * the comma's own first result — its ranking is the device's, not
     * a guess made here. Null only when the comma found nothing.
     */
    fun bestMatch(matches: List<NavDestination>, name: String): NavDestination? {
        if (matches.isEmpty()) return null
        val wanted = name.trim()
        return matches.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
            ?: matches.first()
    }
}
