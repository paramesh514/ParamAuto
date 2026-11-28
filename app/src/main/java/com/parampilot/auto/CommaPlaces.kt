package com.parampilot.auto

import org.json.JSONArray

/**
 * Parser + derivations for the comma's own place lists (Feature 8;
 * Addendum 158 made them the app's ONLY searched/saved places store —
 * the phone-side starred/recents memory is gone). The nav status
 * snapshot's "recents" and "favorites" arrays hold destination dicts
 * in the same shape as the snapshot's destination ({id, name, address,
 * latitude, longitude, label?}). Entries without a usable name or
 * valid coordinates can never be sent anywhere, so they are skipped;
 * each list is capped so the search UI stays a short, tappable list.
 *
 * Home and Work are not a separate system on the comma: they are
 * favorites carrying label "home" / "work" (at most one each — the
 * server moves the label), and the snapshot already sorts them first.
 */
object CommaPlaces {

    const val MAX_PLACES = 10

    const val LABEL_HOME = "home"
    const val LABEL_WORK = "work"

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
                    source = "comma",
                    commaId = obj.optString("id", "").takeIf { it.isNotEmpty() },
                    commaLabel = obj.optString("label", "")
                        .takeIf { it == LABEL_HOME || it == LABEL_WORK }
                )
            )
            if (out.size >= MAX_PLACES) break
        }
        return out
    }

    /** The favorite labeled Home, or null when the comma has none. */
    fun home(favorites: List<NavDestination>): NavDestination? =
        favorites.firstOrNull { it.commaLabel == LABEL_HOME }

    /** The favorite labeled Work, or null when the comma has none. */
    fun work(favorites: List<NavDestination>): NavDestination? =
        favorites.firstOrNull { it.commaLabel == LABEL_WORK }

    /** The saved favorite that is [dest]'s place, if it is saved. */
    fun favoriteFor(
        favorites: List<NavDestination>,
        dest: NavDestination
    ): NavDestination? = favorites.firstOrNull { it.isSameAs(dest) }

    /** Whether [dest]'s place is among the comma's saved favorites. */
    fun isFavorite(
        favorites: List<NavDestination>,
        dest: NavDestination
    ): Boolean = favoriteFor(favorites, dest) != null

    /**
     * Local text matches over comma places (favorites + recents) for a
     * typed query — the instant, zero-network rows shown while a search
     * runs. Replaces the old phone-side starred matches.
     */
    fun matches(places: List<NavDestination>, query: String): List<NavDestination> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return places.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.placeName.contains(q, ignoreCase = true)
        }
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
