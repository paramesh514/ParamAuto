package com.parampilot.auto

/**
 * Layer 2: the PHONE's own Google API use (Param, 2026-11-18 — "Add
 * Google api key same as mapbox, use Google api for search in app.
 * Also use Google speed limit", plus a phone-maps dropdown of
 * Google/Mapbox/OSM next to the comma's Maps/Directions choices).
 *
 * This is the phone-side key, saved in the app like the Mapbox token
 * — NOT the comma's Google key (that one lives on the comma and is
 * modeled by GoogleNav). One gate, mirroring the Mapbox key gate:
 * the "Use phone Google key" toggle on + a key saved + the key
 * well-formed. Everything Google on the phone (Google map tiles,
 * Google place search, Google Roads speed limits) reads the key
 * through [effectiveGoogleKey] so the gate is applied identically
 * everywhere.
 */
object PhoneGoogle {

    const val SOURCE_GOOGLE = "google"
    const val SOURCE_MAPBOX = "mapbox"
    const val SOURCE_OSM = "osm"

    /** The Phone maps dropdown choices, in display order. */
    val MAP_SOURCES = listOf(SOURCE_GOOGLE, SOURCE_MAPBOX, SOURCE_OSM)

    fun sourceLabel(source: String): String = when (source) {
        SOURCE_GOOGLE -> "Google"
        SOURCE_OSM -> "OSM"
        else -> "Mapbox"
    }

    /**
     * Stored phone-map preference → a known source. Anything unknown
     * resolves to Mapbox, the long-standing default order's first
     * choice, so a corrupt or legacy value can never blank the map.
     */
    fun sanitizeMapSource(value: String?): String =
        if (value != null && MAP_SOURCES.contains(value)) value else SOURCE_MAPBOX

    /**
     * The phone Google key that may actually be used right now, or
     * null. Mirrors MapSourceChoice.effectiveMapboxToken's gating:
     * toggle off or no/invalid key means Google is simply not a
     * phone source and callers fall back to the existing providers.
     */
    fun effectiveGoogleKey(): String? {
        if (!NavStateManager.usePhoneGoogleKey) return null
        // The 95% budget rule (Param, 2026-11-18): once any of the
        // comma's Google free limits reaches 95%, the phone stops
        // using Google too — every caller of this gate (maps,
        // search, speed limit) falls back to Mapbox on a null key.
        if (NavStateManager.googleBudgetExhausted) return null
        val key = NavStateManager.googleApiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (GoogleNav.isValidGoogleKey(key)) key else null
    }
}
