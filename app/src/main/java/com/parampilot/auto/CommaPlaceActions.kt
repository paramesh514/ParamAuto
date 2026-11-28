package com.parampilot.auto

import org.json.JSONObject

/**
 * Request bodies for the comma's saved-places actions
 * (Addendum 158): POST /api/navigation/action with the exact key sets
 * the StarPilot server accepts — {"action", "revision", ...fields},
 * any other key is a 400. Pure builders so the shapes are testable;
 * [NavigationManager] posts them through its usual revision-retry
 * path and applies the snapshot each answer carries.
 *
 * The actions (server: openpilot/starpilot/navigation/owner.py):
 * - favorite {destination, label?}: save a place that already has
 *   coordinates (a phone search result, a recent, a map pick).
 * - favoritePlace {id, searchId, label?}: save a searched suggestion;
 *   the comma retrieves the place itself. Only valid while the search
 *   session that produced the suggestion is current.
 * - removeFavorite {id} / labelFavorite {id, label} /
 *   removeRecent {id} / clearRecents {}: manage the saved lists; the
 *   id is the comma's own place id ([NavDestination.commaId]).
 * Labels are "home" / "work" (one favorite each; the server moves
 * the label) or null to clear (labelFavorite only).
 */
object CommaPlaceActions {

    /** The destination dict shape the server validates: name +
     *  coordinates, address only when it adds information. */
    fun destinationJson(dest: NavDestination): JSONObject = JSONObject().apply {
        put("name", dest.name)
        put("latitude", dest.latitude)
        put("longitude", dest.longitude)
        val address = dest.placeName
        if (address.isNotBlank() && address != dest.name) put("address", address)
    }

    fun favorite(dest: NavDestination, revision: String, label: String?): JSONObject =
        JSONObject().apply {
            put("action", "favorite")
            put("destination", destinationJson(dest))
            put("revision", revision)
            if (label != null) put("label", label)
        }

    fun favoritePlace(
        id: String,
        searchId: String,
        revision: String,
        label: String?
    ): JSONObject = JSONObject().apply {
        put("action", "favoritePlace")
        put("id", id)
        put("searchId", searchId)
        put("revision", revision)
        if (label != null) put("label", label)
    }

    fun removeFavorite(id: String, revision: String): JSONObject = JSONObject().apply {
        put("action", "removeFavorite")
        put("id", id)
        put("revision", revision)
    }

    fun labelFavorite(id: String, label: String?, revision: String): JSONObject =
        JSONObject().apply {
            put("action", "labelFavorite")
            put("id", id)
            put("label", label ?: JSONObject.NULL)
            put("revision", revision)
        }

    fun removeRecent(id: String, revision: String): JSONObject = JSONObject().apply {
        put("action", "removeRecent")
        put("id", id)
        put("revision", revision)
    }

    fun clearRecents(revision: String): JSONObject = JSONObject().apply {
        put("action", "clearRecents")
        put("revision", revision)
    }
}
