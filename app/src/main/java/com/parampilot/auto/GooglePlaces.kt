package com.parampilot.auto

import org.json.JSONObject

/**
 * Layer 2: Google Places API (New) parsing for the in-app search
 * (Param, 2026-11-18 — "use Google api for search in app").
 *
 * The flow mirrors the Mapbox SearchBox pair the app already uses:
 * `places:autocomplete` returns suggestions carrying a placeId (the
 * NavDestination's googlePlaceId), and selecting one resolves it
 * through a Place Details fetch. This object is the pure half —
 * request body building and response parsing; the HTTP lives in
 * NavigationManager.
 *
 * Pure: values/JSON in, values out.
 */
object GooglePlaces {

    data class Suggestion(val placeId: String, val name: String, val address: String)

    /** Autocomplete request body: the query biased to a 50 km circle around the car. */
    fun autocompleteBody(query: String, lat: Double, lng: Double, sessionToken: String): JSONObject =
        JSONObject()
            .put("input", query)
            .put("sessionToken", sessionToken)
            .put(
                "locationBias",
                JSONObject().put(
                    "circle",
                    JSONObject()
                        .put(
                            "center",
                            JSONObject().put("latitude", lat).put("longitude", lng)
                        )
                        .put("radius", 50000.0)
                )
            )

    /** Field mask for the autocomplete call (headers, not body). */
    const val AUTOCOMPLETE_FIELD_MASK =
        "suggestions.placePrediction.placeId,suggestions.placePrediction.text," +
            "suggestions.placePrediction.structuredFormat"

    /** Field mask for the Place Details call. */
    const val DETAILS_FIELD_MASK = "id,displayName,formattedAddress,location"

    /** Parses an autocomplete response into suggestions; malformed entries are skipped. */
    fun parseAutocomplete(root: JSONObject): List<Suggestion> {
        val arr = root.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Suggestion>()
        for (i in 0 until arr.length()) {
            val pred = arr.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
            val placeId = pred.optString("placeId", "")
            if (placeId.isBlank()) continue
            val structured = pred.optJSONObject("structuredFormat")
            val mainText = structured?.optJSONObject("mainText")?.optString("text", "") ?: ""
            val secondary = structured?.optJSONObject("secondaryText")?.optString("text", "") ?: ""
            val fullText = pred.optJSONObject("text")?.optString("text", "") ?: ""
            val name = mainText.ifBlank { fullText }
            if (name.isBlank()) continue
            out.add(
                Suggestion(
                    placeId = placeId,
                    name = name,
                    address = secondary.ifBlank { fullText }
                )
            )
        }
        return out
    }

    /**
     * Parses a Place Details response into a resolved destination,
     * or null when it carries no coordinates (a place we cannot
     * navigate to is never invented into one).
     */
    fun parseDetails(root: JSONObject): NavDestination? {
        val loc = root.optJSONObject("location") ?: return null
        val lat = loc.optDouble("latitude", 0.0)
        val lng = loc.optDouble("longitude", 0.0)
        if (lat == 0.0 && lng == 0.0) return null
        val name = root.optJSONObject("displayName")?.optString("text", "") ?: ""
        val address = root.optString("formattedAddress", "")
        if (name.isBlank() && address.isBlank()) return null
        return NavDestination(
            name = name.ifBlank { address },
            placeName = address.ifBlank { name },
            latitude = lat,
            longitude = lng,
            googlePlaceId = root.optString("id", "").ifBlank { null },
            source = "google"
        )
    }
}
