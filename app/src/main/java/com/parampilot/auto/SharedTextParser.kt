package com.parampilot.auto

import java.net.URLDecoder

/**
 * What a share (Feature 11) resolved to: a text query to run through the
 * existing search path, a coordinate pair to send directly, a link the
 * phone must expand first (Feature 11 extension, 2026-11-18: short
 * links are followed on the PHONE and re-parsed — Param: "Don't want
 * risk comma side. Do in phone", so nothing is sent to the comma's
 * share resolver), or nothing readable at all.
 */
sealed class SharedTarget {
    data class Query(val text: String) : SharedTarget()
    data class Coords(val latitude: Double, val longitude: Double, val label: String?) : SharedTarget()
    data class Link(val url: String) : SharedTarget()
    object Unreadable : SharedTarget()
}

/**
 * Pure parser for text shared into the app via ACTION_SEND (a place shared
 * from Maps, a browser, a message) and for VIEW intents (geo: URIs and
 * maps links from other apps). No I/O: Google place/directions, Apple
 * Maps and Waze links are read from their URL parameters directly, and
 * a bare short link is never guessed at — it comes back as
 * [SharedTarget.Link] so the caller can expand it on the phone
 * (LinkExpander) and parse the result again.
 */
object SharedTextParser {

    private val URL_REGEX = Regex("""https?://\S+""")
    private val COORD_REGEX = Regex("""(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")

    fun parse(text: String?): SharedTarget {
        var raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return SharedTarget.Unreadable

        val url = URL_REGEX.find(raw)?.value?.trimEnd('.', ',', ')')
        if (url != null) {
            when {
                isShortLink(url) -> {
                    // The surrounding text (if any) is usable locally;
                    // a bare short link comes back as Link so the
                    // caller expands it on the phone and re-parses.
                    val rest = raw.replace(url, "").trim()
                    if (rest.isEmpty()) return SharedTarget.Link(url)
                    raw = rest
                }
                isMapsUrl(url) -> {
                    mapsQueryFromUrl(url)?.let { return SharedTarget.Query(it) }
                    val rest = raw.replace(url, "").trim()
                    if (rest.isEmpty()) return SharedTarget.Link(url)
                    raw = rest
                }
                isAppleMapsUrl(url) -> {
                    appleMapsTarget(url)?.let { return it }
                    val rest = raw.replace(url, "").trim()
                    if (rest.isEmpty()) return SharedTarget.Link(url)
                    raw = rest
                }
                isWazeUrl(url) -> {
                    wazeTarget(url)?.let { return it }
                    val rest = raw.replace(url, "").trim()
                    if (rest.isEmpty()) return SharedTarget.Link(url)
                    raw = rest
                }
            }
        }

        COORD_REGEX.find(raw)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                val label = raw.replace(m.value, "")
                    .replace(URL_REGEX, "")
                    .lines().map { it.trim().trim(',', '·', '-') }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ")
                    .ifEmpty { null }
                return SharedTarget.Coords(lat, lng, label)
            }
        }

        // Plain text: join lines into one query, skipping any line that is
        // just a URL.
        val query = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://") }
            .joinToString(", ")
        return if (query.isNotEmpty()) SharedTarget.Query(query) else SharedTarget.Unreadable
    }

    private fun isShortLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("goo.gl/") || lower.contains("maps.app.goo.gl") ||
            lower.contains("bit.ly/") || lower.contains("t.co/")
    }

    private fun isAppleMapsUrl(url: String): Boolean =
        url.lowercase().contains("maps.apple.com")

    private fun isWazeUrl(url: String): Boolean =
        url.lowercase().contains("waze.com/")

    /** Decoded query parameters of a URL, by key. */
    private fun queryParams(url: String): Map<String, String> {
        val queryPart = url.substringAfter('?', "")
        if (queryPart.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in queryPart.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = decode(value)
        }
        return out
    }

    /** A "lat,lng" text as Coords, or null when it isn't one. */
    private fun coordsFromText(text: String): SharedTarget.Coords? {
        val m = COORD_REGEX.find(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return SharedTarget.Coords(lat, lng, null)
    }

    /**
     * Apple Maps links (2026-11-18, read on the phone): daddr is a
     * directions destination, ll the map center, q a place or
     * coordinates, address a plain address.
     */
    private fun appleMapsTarget(url: String): SharedTarget? {
        val params = queryParams(url)
        params["daddr"]?.takeIf { it.isNotBlank() }?.let { dest ->
            return coordsFromText(dest) ?: SharedTarget.Query(dest)
        }
        params["ll"]?.let { ll ->
            coordsFromText(ll)?.let { return it }
        }
        params["q"]?.takeIf { it.isNotBlank() }?.let { q ->
            return coordsFromText(q) ?: SharedTarget.Query(q)
        }
        params["address"]?.takeIf { it.isNotBlank() }?.let {
            return SharedTarget.Query(it)
        }
        return null
    }

    /** Waze links (2026-11-18, read on the phone): ll or q. */
    private fun wazeTarget(url: String): SharedTarget? {
        val params = queryParams(url)
        params["ll"]?.let { ll ->
            coordsFromText(ll)?.let { return it }
        }
        params["q"]?.takeIf { it.isNotBlank() }?.let { q ->
            return coordsFromText(q) ?: SharedTarget.Query(q)
        }
        return null
    }

    /**
     * Parses a geo: URI from another app's "open location with"
     * (2026-11-18): geo:lat,lng, geo:lat,lng?q=<label>, and the
     * search form geo:0,0?q=<query>. A q that itself holds
     * coordinates wins as the destination.
     */
    fun parseGeoUri(uri: String?): SharedTarget {
        var raw = uri?.trim().orEmpty()
        if (!raw.startsWith("geo:", ignoreCase = true)) return SharedTarget.Unreadable
        raw = raw.substring(4)
        val coordPart = raw.substringBefore('?').substringBefore(';')
        val queryPart = raw.substringAfter('?', "")
        var label: String? = null
        if (queryPart.isNotEmpty()) {
            for (pair in queryPart.split('&')) {
                if (pair.substringBefore('=') == "q") {
                    label = decode(pair.substringAfter('=', ""))
                        .ifEmpty { null }
                    break
                }
            }
        }
        // A q holding coordinates is the destination itself.
        label?.let { text ->
            COORD_REGEX.find(text)?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lng = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lng != null &&
                    lat in -90.0..90.0 && lng in -180.0..180.0
                ) {
                    return SharedTarget.Coords(lat, lng, null)
                }
            }
        }
        val parts = coordPart.split(',')
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            if (lat != 0.0 || lng != 0.0) {
                return SharedTarget.Coords(lat, lng, label)
            }
        }
        // geo:0,0?q=<place> — a pure search.
        return if (!label.isNullOrBlank()) {
            SharedTarget.Query(label)
        } else {
            SharedTarget.Unreadable
        }
    }

    private fun isMapsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("google.com/maps") || lower.contains("maps.google.com") ||
            lower.contains("google.co") && lower.contains("/maps")
    }

    /**
     * q=/query= parameter first (a trip link's destination= counts —
     * it is the place the trip goes to), then the /place/<segment>/
     * path form.
     */
    private fun mapsQueryFromUrl(url: String): String? {
        val queryPart = url.substringAfter('?', "")
        if (queryPart.isNotEmpty()) {
            for (pair in queryPart.split('&')) {
                val key = pair.substringBefore('=')
                if (key == "q" || key == "query" || key == "destination") {
                    val value = pair.substringAfter('=', "")
                    val decoded = decode(value)
                    if (decoded.isNotBlank()) return decoded
                }
            }
        }
        val placeIdx = url.indexOf("/place/")
        if (placeIdx >= 0) {
            val segment = url.substring(placeIdx + "/place/".length)
                .substringBefore('/')
                .substringBefore('?')
            val decoded = decode(segment)
            if (decoded.isNotBlank()) return decoded
        }
        return null
    }

    private fun decode(value: String): String =
        try {
            URLDecoder.decode(value, "UTF-8").trim()
        } catch (e: Exception) {
            value.replace('+', ' ').trim()
        }
}
