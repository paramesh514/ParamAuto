package com.parampilot.auto

import android.util.Log
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Handles destination search via Mapbox SearchBox API (suggest & retrieve)
 * and the comma's own search endpoint, and dispatches navigation commands
 * to the Comma Galaxy HTTP API. Routing itself is comma-only: routes are
 * consumed from the device via the SG snapshot path, never fetched here.
 */
class NavigationManager {

    private var activeSessionToken: String = UUID.randomUUID().toString()

    /** Resets the SearchBox session token for a new query lifecycle. */
    fun refreshSessionToken(): String {
        activeSessionToken = UUID.randomUUID().toString()
        return activeSessionToken
    }

    /**
     * Searches places via Mapbox SearchBox suggest API, returning nearby POIs and addresses.
     */
    fun searchPlaces(
        query: String,
        proximityLat: Double,
        proximityLng: Double,
        token: String
    ): List<NavDestination> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val coords = parseDirectCoords(trimmed)
        if (coords != null) {
            return listOf(
                NavDestination(
                    name = "${coords.latitude}, ${coords.longitude}",
                    placeName = "${coords.latitude}, ${coords.longitude}",
                    latitude = coords.latitude,
                    longitude = coords.longitude
                )
            )
        }

        var conn: HttpURLConnection? = null
        try {
            val enc = URLEncoder.encode(trimmed, "UTF-8")
            val urlStr = "https://api.mapbox.com/search/searchbox/v1/suggest" +
                "?access_token=$token&session_token=$activeSessionToken&q=$enc&limit=8&types=poi,address,place&proximity=$proximityLng,$proximityLat"
            UsageCounters.count(UsageCounters.MAPBOX_SUGGEST)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val suggestions = root.optJSONArray("suggestions")
                if (suggestions != null && suggestions.length() > 0) {
                    val results = ArrayList<NavDestination>()
                    for (i in 0 until suggestions.length()) {
                        val s = suggestions.getJSONObject(i)
                        val featType = s.optString("feature_type", "")
                        if (featType == "category") continue

                        val mapboxId = s.optString("mapbox_id", "")
                        val name = s.optString("name", trimmed)
                        val fullAddress = s.optString("full_address", "")
                        val address = s.optString("address", "")
                        val placeFormatted = s.optString("place_formatted", "")
                        val dist = if (s.has("distance")) s.getDouble("distance") else null

                        val displayAddress = when {
                            fullAddress.isNotBlank() && fullAddress != "Brand" -> fullAddress
                            address.isNotBlank() -> if (placeFormatted.isNotBlank()) "$address, $placeFormatted" else address
                            placeFormatted.isNotBlank() && placeFormatted != "Brand" -> placeFormatted
                            else -> name
                        }

                        results.add(
                            NavDestination(
                                name = name,
                                placeName = displayAddress,
                                latitude = 0.0,
                                longitude = 0.0,
                                mapboxId = mapboxId,
                                sessionToken = activeSessionToken,
                                distanceM = dist
                            )
                        )
                    }
                    if (results.isNotEmpty()) {
                        results.sortWith(
                            compareBy<NavDestination> { !it.name.equals(trimmed, ignoreCase = true) }
                                .thenBy { it.distanceM ?: Double.MAX_VALUE }
                        )
                        return results.take(5)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SearchBox suggest failed: ${e.message}, falling back to legacy geocoding")
        } finally {
            conn?.disconnect()
        }

        return fallbackGeocodeV5(trimmed, proximityLat, proximityLng, token)
    }

    /**
     * Fetches Mapbox Directions for the same origin/destination as the
     * comma's active route, WITH maxspeed annotations — the data
     * behind "Use phone speed limit" (the comma's payload carries no
     * limit anywhere; see MaxspeedAnnotations). Returns the parsed
     * series, or null on any failure (no limit is shown then — never
     * an invented one).
     */
    fun fetchMapboxRouteLimits(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        token: String
    ): MaxspeedAnnotations.Series? {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return null
        var conn: HttpURLConnection? = null
        return try {
            val coordsStr = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f", originLng, originLat, destLng, destLat)
            val urlStr = "https://api.mapbox.com/directions/v5/mapbox/driving/" +
                coordsStr +
                "?annotations=maxspeed&overview=full&geometries=geojson&steps=false" +
                "&access_token=$cleanToken"
            UsageCounters.count(UsageCounters.MAPBOX_DIRECTIONS)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Mapbox route limits fetch failed HTTP ${conn.responseCode}: $err")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val routes = JSONObject(body).optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val parsed = MaxspeedAnnotations.parseDirectionsRoute(routes.optJSONObject(0))
            Log.i(TAG, "Mapbox route limits loaded: ${parsed?.limitsMph?.count { it != null }} limits for ${parsed?.points?.size} points")
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "Mapbox route limits fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }


    /**
     * Google Places (New) autocomplete — the Google counterpart of
     * [searchPlaces] (Param, 2026-11-18: "use Google api for search
     * in app"). Suggestions carry a googlePlaceId and no coordinates;
     * [retrievePlaceGoogle] resolves the picked one, mirroring the
     * Mapbox suggest/retrieve pair. Any failure returns an empty
     * list and the caller falls back to the existing sources.
     */
    fun searchPlacesGoogle(
        query: String,
        proximityLat: Double,
        proximityLng: Double,
        key: String
    ): List<NavDestination> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val coords = parseDirectCoords(trimmed)
        if (coords != null) {
            return listOf(
                NavDestination(
                    name = "${coords.latitude}, ${coords.longitude}",
                    placeName = "${coords.latitude}, ${coords.longitude}",
                    latitude = coords.latitude,
                    longitude = coords.longitude
                )
            )
        }
        val session = UUID.randomUUID().toString()
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.GOOGLE_PLACES_AUTOCOMPLETE)
            conn = (URL("https://places.googleapis.com/v1/places:autocomplete")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", key)
                setRequestProperty(
                    "X-Goog-FieldMask", GooglePlaces.AUTOCOMPLETE_FIELD_MASK
                )
            }
            val body = GooglePlaces
                .autocompleteBody(trimmed, proximityLat, proximityLng, session)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Google autocomplete failed HTTP ${conn.responseCode}")
                return emptyList()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            GooglePlaces.parseAutocomplete(JSONObject(text)).take(5).map { s ->
                NavDestination(
                    name = s.name,
                    placeName = s.address,
                    latitude = 0.0,
                    longitude = 0.0,
                    googlePlaceId = s.placeId,
                    sessionToken = session,
                    source = "google"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google autocomplete error: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /** Resolves a Google suggestion through Place Details; the mirror of [retrievePlace]. */
    fun retrievePlaceGoogle(dest: NavDestination, key: String): NavDestination? {
        val placeId = dest.googlePlaceId ?: return null
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.GOOGLE_PLACES_DETAILS)
            val sessionPart = dest.sessionToken
                ?.let { "?sessionToken=${URLEncoder.encode(it, "UTF-8")}" } ?: ""
            conn = (URL("https://places.googleapis.com/v1/places/$placeId$sessionPart")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("X-Goog-Api-Key", key)
                setRequestProperty("X-Goog-FieldMask", GooglePlaces.DETAILS_FIELD_MASK)
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Google place details failed HTTP ${conn.responseCode}")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = GooglePlaces.parseDetails(JSONObject(text)) ?: return null
            val resolved = if (parsed.name.isBlank() || parsed.name == parsed.placeName) {
                parsed.copy(name = dest.name)
            } else {
                parsed
            }
            if (resolved.hasCoordinates) {
                try {
                    NavStateManager.appContext?.let { ctx ->
                        SearchCache.putResolved(ctx, resolved)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            resolved
        } catch (e: Exception) {
            Log.e(TAG, "Google place details error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Google Roads speed limits for the active route (Param,
     * 2026-11-18: "Also use Google speed limit") — the Google
     * counterpart of [fetchMapboxRouteLimits]. Snaps the route
     * geometry to roads and reads each segment's limit, returning
     * the same Series shape the Mapbox flow produces. Null on any
     * failure — the caller then falls back to Mapbox; a limit is
     * never invented.
     */
    fun fetchGoogleRouteLimits(
        points: List<LatLng>,
        key: String
    ): MaxspeedAnnotations.Series? {
        if (points.size < 2) return null
        val sampled = GoogleRoads.sample(points)
        val path = sampled.joinToString("|") {
            String.format(java.util.Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
        }
        val snapUrl = "https://roads.googleapis.com/v1/snapToRoads" +
            "?path=${URLEncoder.encode(path, "UTF-8")}&interpolate=false&key=$key"
        val snappedJson = googleRoadsGet(snapUrl) ?: return null
        val snapped = GoogleRoads.parseSnapped(snappedJson)
        if (snapped.size < 2) return null
        val placeIds = snapped.map { it.placeId }.distinct()
        val limitsQuery = placeIds.joinToString("&") {
            "placeId=${URLEncoder.encode(it, "UTF-8")}"
        }
        val limitsUrl = "https://roads.googleapis.com/v1/speedLimits" +
            "?$limitsQuery&units=MPH&key=$key"
        val limitsJson = googleRoadsGet(limitsUrl) ?: return null
        val series = GoogleRoads.buildSeries(
            snapped, GoogleRoads.parseSpeedLimits(limitsJson)
        )
        Log.i(
            TAG,
            "Google route limits loaded: ${series?.limitsMph?.count { it != null }} " +
                "limits for ${series?.points?.size} points"
        )
        return series
    }

    /** One Roads API GET; that API takes its key as a query parameter. Counts the call. */
    private fun googleRoadsGet(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.GOOGLE_ROADS)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Google Roads failed HTTP ${conn.responseCode}")
                return null
            }
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "Google Roads error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Creates a Google Map Tiles session for the phone map's Google
     * source and stores it in NavStateManager (the style key carries
     * the session, so its arrival reloads the map onto Google tiles
     * exactly once). Returns true when a fresh session is in place.
     */
    fun createGoogleTilesSession(key: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.GOOGLE_TILES_SESSION)
            conn = (URL("https://tile.googleapis.com/v1/createSession?key=$key")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject()
                .put("mapType", "roadmap")
                .put("language", "en-US")
                .put("region", "US")
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                Log.w(TAG, "Google tiles session failed HTTP ${conn.responseCode}")
                return false
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val session = GoogleTiles.parseSession(JSONObject(text)) ?: return false
            NavStateManager.googleTilesSession = session.token
            NavStateManager.googleTilesSessionExpirySec = session.expiryEpochSec
            NavStateManager.notifyChanged()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Google tiles session error: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Fetches traffic-signal points along the route from the Mapbox
     * Tilequery API (Streets v8, road layer): one query per sample
     * point from [RouteFeatures.samplePoints]. Returns every signal
     * candidate found (corridor filtering and dedupe are layer-2, in
     * RouteFeatures.alongRoute), or null when every query failed —
     * no markers then, never invented ones. Individual sample
     * failures are logged and skipped.
     */
    fun fetchRouteSignals(routePoints: List<LatLng>, token: String): List<LatLng>? {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty() || routePoints.size < 2) return null
        val samples = RouteFeatures.samplePoints(routePoints)
        val found = ArrayList<LatLng>()
        var anyOk = false
        for (p in samples) {
            var conn: HttpURLConnection? = null
            try {
                val urlStr = String.format(
                    java.util.Locale.US,
                    "https://api.mapbox.com/v4/mapbox.mapbox-streets-v8/tilequery/%.6f,%.6f.json?radius=170&limit=50&layers=road&geometry=point&access_token=%s",
                    p.longitude, p.latitude, cleanToken
                )
                UsageCounters.count(UsageCounters.MAPBOX_TILEQUERY)
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "ParamAuto/1.0")
                }
                if (conn.responseCode != 200) {
                    Log.w(TAG, "Tilequery signals fetch failed HTTP ${conn.responseCode} at ${p.latitude},${p.longitude}")
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                found.addAll(RouteFeatures.parseTilequerySignals(JSONObject(body)))
                anyOk = true
            } catch (e: Exception) {
                Log.w(TAG, "Tilequery signals fetch failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        if (!anyOk) return null
        Log.i(TAG, "Route signals loaded: ${found.size} candidates from ${samples.size} samples")
        return found
    }

    /**
     * Fetches stop signs along the route from OpenStreetMap via the
     * Overpass API: highway=stop nodes within 40 m of the route line
     * (an around query over sample points spaced ~200 m). Returns
     * the node positions, or null on any failure — OSM coverage is
     * patchy and a failure must not invent stops.
     */
    fun fetchRouteStops(routePoints: List<LatLng>): List<LatLng>? {
        if (routePoints.size < 2) return null
        val samples = RouteFeatures.samplePoints(routePoints, spacingM = 200.0)
        val coords = samples.joinToString(",") {
            String.format(java.util.Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
        }
        val ql = "[out:json][timeout:25];node[\"highway\"=\"stop\"](around:40,$coords);out;"
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.OSM_OVERPASS)
            conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("User-Agent", "ParamAuto/1.0")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val payload = "data=" + URLEncoder.encode(ql, "UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Overpass stops fetch failed HTTP ${conn.responseCode}: $err")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val stops = RouteFeatures.parseOverpassStops(JSONObject(body))
            Log.i(TAG, "Route stops loaded: ${stops.size} from ${samples.size} samples")
            stops
        } catch (e: Exception) {
            Log.w(TAG, "Overpass stops fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Fetches speed limits along the route from OpenStreetMap via
     * the Overpass API: ways carrying a maxspeed tag within the
     * corridor of the route line (an around query over sample
     * points spaced ~200 m — the same sweep as the stop signs, the
     * same endpoint, no key). The key-free last resort of the phone
     * speed-limit source order (Addendum 161). Returns the series,
     * or null on any failure or when OSM has no tagged limit along
     * the route — no limit is shown then, never an invented one.
     */
    fun fetchOsmRouteLimits(routePoints: List<LatLng>): MaxspeedAnnotations.Series? {
        if (routePoints.size < 2) return null
        val samples = RouteFeatures.samplePoints(routePoints, spacingM = 200.0)
        val coords = samples.joinToString(",") {
            String.format(java.util.Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
        }
        val ql = "[out:json][timeout:25];way[\"maxspeed\"]" +
            "(around:${OsmMaxspeed.CORRIDOR_M.toInt()},$coords);out geom;"
        var conn: HttpURLConnection? = null
        return try {
            UsageCounters.count(UsageCounters.OSM_OVERPASS)
            conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("User-Agent", "ParamAuto/1.0")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val payload = "data=" + URLEncoder.encode(ql, "UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Overpass maxspeed fetch failed HTTP ${conn.responseCode}: $err")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val series = OsmMaxspeed.buildSeries(
                routePoints, OsmMaxspeed.parseWays(JSONObject(body))
            )
            Log.i(TAG, "OSM route limits loaded: ${series?.limitsMph?.count { it != null }} limits for ${series?.points?.size} points")
            series
        } catch (e: Exception) {
            Log.w(TAG, "Overpass maxspeed fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Resolves exact coordinates for a SearchBox suggestion via /retrieve/{mapbox_id}.
     */
    fun retrievePlace(dest: NavDestination, token: String): NavDestination? {
        val mapboxId = dest.mapboxId ?: return if (dest.hasCoordinates) dest else null
        val sessionToken = dest.sessionToken ?: activeSessionToken
        var conn: HttpURLConnection? = null
        val resolved: NavDestination? = try {
            val encId = URLEncoder.encode(mapboxId, "UTF-8")
            val urlStr = "https://api.mapbox.com/search/searchbox/v1/retrieve/$encId" +
                "?access_token=$token&session_token=$sessionToken"
            UsageCounters.count(UsageCounters.MAPBOX_RETRIEVE)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return null
            if (features.length() == 0) return null
            val f = features.getJSONObject(0)
            val geom = f.optJSONObject("geometry")
            val coords = geom?.optJSONArray("coordinates")
            val props = f.optJSONObject("properties")
            val coordObj = props?.optJSONObject("coordinates")

            val lng = coords?.optDouble(0)
                ?: coordObj?.optDouble("longitude")
                ?: 0.0
            val lat = coords?.optDouble(1)
                ?: coordObj?.optDouble("latitude")
                ?: 0.0

            val resolvedName = props?.optString("name", dest.name) ?: dest.name
            val fullAddr = props?.optString("full_address", dest.placeName) ?: dest.placeName

            refreshSessionToken()

            dest.copy(
                name = resolvedName,
                placeName = fullAddr,
                latitude = lat,
                longitude = lng
            )
        } catch (e: Exception) {
            Log.e(TAG, "SearchBox retrieve error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
        // Write-back (review A4): persist the resolved coordinates into
        // SearchCache so a repeat selection of this place skips retrievePlace.
        if (resolved != null && resolved.hasCoordinates) {
            try {
                NavStateManager.appContext?.let { ctx -> SearchCache.putResolved(ctx, resolved) }
            } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
        }
        return resolved
    }

    private fun fallbackGeocodeV5(
        trimmed: String,
        proximityLat: Double,
        proximityLng: Double,
        token: String
    ): List<NavDestination> {
        var conn: HttpURLConnection? = null
        try {
            val enc = URLEncoder.encode(trimmed, "UTF-8")
            val urlStr = "https://api.mapbox.com/geocoding/v5/mapbox.places/$enc.json" +
                "?proximity=$proximityLng,$proximityLat&types=poi,address,place&limit=5&access_token=$token"
            UsageCounters.count(UsageCounters.MAPBOX_GEOCODE)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
            }
            if (conn.responseCode != 200) return searchPlacesPhoton(trimmed, proximityLat, proximityLng)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val features = root.optJSONArray("features") ?: return searchPlacesPhoton(trimmed, proximityLat, proximityLng)
            val results = ArrayList<NavDestination>()

            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val center = f.getJSONArray("center")
                val lng = center.getDouble(0)
                val lat = center.getDouble(1)
                val placeName = f.optString("place_name", trimmed)
                val text = f.optString("text", placeName)
                results.add(
                    NavDestination(
                        name = text,
                        placeName = placeName,
                        latitude = lat,
                        longitude = lng
                    )
                )
            }
            if (results.isNotEmpty()) return results
        } catch (e: Exception) {
            Log.e(TAG, "Fallback geocoding error: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        return searchPlacesPhoton(trimmed, proximityLat, proximityLng)
    }

    /**
     * OpenStreetMap Photon search fallback (Komoot).
     * 100% free, requiring no API key or token, providing reliable POI and address search worldwide.
     */
    fun searchPlacesPhoton(
        query: String,
        proximityLat: Double,
        proximityLng: Double
    ): List<NavDestination> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val enc = URLEncoder.encode(trimmed, "UTF-8")
            val urlStr = "https://photon.komoot.io/api/?q=$enc&lat=$proximityLat&lon=$proximityLng&limit=8"
            UsageCounters.count(UsageCounters.PHOTON_SEARCH)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "ParamAuto/1.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val features = root.optJSONArray("features") ?: return emptyList()
                val results = ArrayList<NavDestination>()
                for (i in 0 until features.length()) {
                    val f = features.getJSONObject(i)
                    val geom = f.optJSONObject("geometry")
                    val coords = geom?.optJSONArray("coordinates")
                    val lng = coords?.optDouble(0) ?: continue
                    val lat = coords.optDouble(1)

                    val props = f.optJSONObject("properties") ?: JSONObject()
                    val name = props.optString("name", trimmed)
                    val street = props.optString("street", "")
                    val houseNumber = props.optString("housenumber", "")
                    val city = props.optString("city", "")
                    val state = props.optString("state", "")

                    val addrParts = mutableListOf<String>()
                    val st = "$houseNumber $street".trim()
                    if (st.isNotEmpty()) addrParts.add(st)
                    if (city.isNotEmpty()) addrParts.add(city)
                    if (state.isNotEmpty()) addrParts.add(state)
                    val displayAddress = if (addrParts.isNotEmpty()) addrParts.joinToString(", ") else name

                    results.add(
                        NavDestination(
                            name = name,
                            placeName = displayAddress,
                            latitude = lat,
                            longitude = lng
                        )
                    )
                }
                results
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon search fallback error: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Reverse geocode via Photon (key-free) — names the point a
     * long-press drops on the map (Addendum 115). Same property
     * assembly as [searchPlacesPhoton], first feature only. Any
     * failure returns null and the caller keeps the coordinates
     * as the label.
     */
    fun reverseGeocodePhoton(
        latitude: Double,
        longitude: Double
    ): NavDestination? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr =
                "https://photon.komoot.io/reverse?lat=$latitude&lon=$longitude"
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "ParamAuto/1.0")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val features = JSONObject(body).optJSONArray("features")
                ?: return null
            if (features.length() == 0) return null
            val props = features.getJSONObject(0)
                .optJSONObject("properties") ?: return null
            val street = props.optString("street", "")
            val houseNumber = props.optString("housenumber", "")
            val city = props.optString("city", "")
            val state = props.optString("state", "")
            val addrParts = mutableListOf<String>()
            val st = "$houseNumber $street".trim()
            if (st.isNotEmpty()) addrParts.add(st)
            if (city.isNotEmpty()) addrParts.add(city)
            if (state.isNotEmpty()) addrParts.add(state)
            val address = addrParts.joinToString(", ")
            val name = props.optString("name", "")
                .ifEmpty { st }
                .ifEmpty { address }
            if (name.isEmpty() && address.isEmpty()) {
                null
            } else {
                NavDestination(
                    name = name,
                    placeName = address.ifEmpty { name },
                    latitude = latitude,
                    longitude = longitude,
                    source = "photon"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Photon reverse error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Searches places on the comma itself: POST /api/navigation/search with
     * the session payload {"query", "searchId", "clientId", "autocomplete"}.
     * The comma's payload contract is strict — any other key set is a 400 —
     * and with ids present it answers TEMPORARY suggestions ({id, name,
     * description, searchId}, NO coordinates): choosing one goes through
     * [selectCommaPlaceSg], which lets the comma retrieve the place itself.
     * A row that does carry coordinates (one-shot geocode shape) is returned
     * as a direct-select destination instead.
     */
    fun searchPlacesCommaSg(query: String, baseUrl: String): List<NavDestination> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val cacheKey = trimmed.lowercase()
        commaSearchCache[cacheKey]?.let { cached ->
            Log.d(TAG, "searchPlacesCommaSg cache hit (memory) for '$trimmed': ${cached.size} items")
            return cached.map { SearchCache.applyResolved(it) }
        }

        NavStateManager.appContext?.let { ctx ->
            val diskCached = SearchCache.lookup(ctx, trimmed)
            if (diskCached != null && diskCached.isNotEmpty()) {
                Log.d(TAG, "searchPlacesCommaSg cache hit (disk) for '$trimmed': ${diskCached.size} items")
                val resolvedList = diskCached.map { SearchCache.applyResolved(it) }
                commaSearchCache[cacheKey] = resolvedList
                return resolvedList
            }
        }

        // Record the search-time revision + query BEFORE the POST. The
        // comma binds the session entry server-side to the revision in
        // effect when the search runs, and selectPlace must present that
        // same revision (see selectCommaPlaceSg). Recording on every call
        // is correct, not just the first: when the revision moves
        // mid-typing, the server stops treating the session as continuing
        // and binds a fresh entry to the then-current revision — the same
        // value re-recorded here.
        commaSearchQuery = trimmed
        commaSearchRevision = NavStateManager.sgRevision
        val client = SgGalaxyClient(baseUrl)
        var attempt = 0
        while (true) {
            attempt++
            val (searchId, clientId) = commaSearchIds()
            val payload = JSONObject().apply {
                put("query", trimmed)
                put("searchId", searchId)
                put("clientId", clientId)
                put("autocomplete", true)
            }
            val (code, resp) = try {
                client.postJson(SgGalaxyClient.PATH_NAV_SEARCH, payload)
            } catch (e: Exception) {
                Log.w(TAG, "searchPlacesCommaSg error: ${e.message}")
                lastCommaSearchError = e.message ?: e.javaClass.simpleName
                return emptyList()
            }
            if (code == 200 && resp != null) {
                lastCommaSearchError = null
                val results = parseCommaSearchResults(resp, trimmed, searchId)
                if (results.isNotEmpty()) {
                    commaSearchCache[cacheKey] = results
                    NavStateManager.appContext?.let { ctx ->
                        SearchCache.put(ctx, trimmed, results)
                        results.filter { it.hasCoordinates }.forEach {
                            SearchCache.putResolved(ctx, it)
                        }
                    }
                }
                return results
            }
            val error = resp?.optString("error", "")?.ifEmpty { null }
            // A stale session is recoverable exactly once: rotate the
            // searchId and retry with the fresh session.
            if (code == 400 && attempt == 1 && error?.contains("Start a new destination search") == true) {
                rotateCommaSearchId()
                continue
            }
            lastCommaSearchError = error ?: "comma answered HTTP $code"
            Log.w(TAG, "searchPlacesCommaSg failed, code: $code, error: $error")
            return emptyList()
        }
    }

    private fun parseCommaSearchResults(
        resp: JSONObject,
        query: String,
        sessionSearchId: String
    ): List<NavDestination> {
        val resultsArr = resp.optJSONArray("results") ?: resp.optJSONArray("features")
            ?: resp.optJSONArray("suggestions") ?: return emptyList()
        val results = ArrayList<NavDestination>()
        for (i in 0 until resultsArr.length()) {
            val obj = resultsArr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").ifEmpty { obj.optString("title", query) }
            val lat = obj.optDouble("latitude", obj.optDouble("lat", Double.NaN))
            val lng = obj.optDouble("longitude", obj.optDouble("lng", Double.NaN))
            if (!lat.isNaN() && !lng.isNaN()) {
                // Geocode-shaped row: coordinates present, direct-select.
                val addr = obj.optString("address", "")
                    .ifEmpty { obj.optString("description", "") }
                    .ifEmpty { obj.optString("place_name", name) }
                results.add(
                    NavDestination(name = name, placeName = addr, latitude = lat, longitude = lng, source = "comma")
                )
            } else {
                // Temporary suggestion: no coordinates; the id + searchId
                // are the selection handles for selectCommaPlaceSg. They
                // ride in mapboxId/sessionToken, and source="comma" is what
                // distinguishes them from Mapbox SearchBox suggestions
                // (which populate the same two fields with Mapbox ids).
                val id = obj.optString("id", "").ifEmpty { null } ?: continue
                val description = obj.optString("description", "")
                val candidate = NavDestination(
                    name = name,
                    placeName = description.ifEmpty { name },
                    latitude = 0.0,
                    longitude = 0.0,
                    mapboxId = id,
                    sessionToken = obj.optString("searchId", "").ifEmpty { null } ?: sessionSearchId,
                    source = "comma"
                )
                results.add(SearchCache.applyResolved(candidate))
            }
        }
        return results
    }

    fun geocode(query: String, proxLat: Double, proxLng: Double, token: String): NavDestination? =
        searchPlaces(query, proxLat, proxLng, token).firstOrNull()

    /**
     * Phone-side Mapbox routing (Addendum 162: restored, reversing
     * the 2026-10-31 removal — that removal was a comma-testing
     * constraint, Param 2026-11-28). Mapbox Directions with steps
     * and full GeoJSON geometry, parsed by [PhoneRoutes]. Empty on
     * any failure: the plan walk in [fetchPhoneRoute] falls through
     * to the next provider.
     */
    fun fetchRoutes(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        token: String
    ): List<RouteOption> {
        val cleanToken = token.trim()
        if (cleanToken.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val coordsStr = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f", startLng, startLat, destLng, destLat)
            val urlStr = "https://api.mapbox.com/directions/v5/mapbox/driving/" +
                coordsStr +
                "?steps=true&geometries=geojson&overview=full&banner_instructions=true" +
                "&access_token=$cleanToken"
            UsageCounters.count(UsageCounters.MAPBOX_DIRECTIONS)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Mapbox routing failed HTTP ${conn.responseCode}: $err")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val routes = PhoneRoutes.parseMapboxDirections(JSONObject(body))
            Log.i(TAG, "Mapbox routing loaded: ${routes.size} route(s), ${routes.firstOrNull()?.steps?.size ?: 0} steps")
            routes
        } catch (e: Exception) {
            Log.w(TAG, "Mapbox routing failed: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Phone-side OSRM routing (Addendum 162: restored together with
     * the Mapbox fetcher) — the key-free last resort of the routing
     * plan, from the public OSRM demo server. Empty on any failure.
     */
    fun fetchRoutesOSRM(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double
    ): List<RouteOption> {
        var conn: HttpURLConnection? = null
        return try {
            val coordsStr = String.format(java.util.Locale.US, "%.6f,%.6f;%.6f,%.6f", startLng, startLat, destLng, destLat)
            val urlStr = "https://router.project-osrm.org/route/v1/driving/" +
                coordsStr +
                "?overview=full&geometries=geojson&steps=true"
            UsageCounters.count(UsageCounters.OSRM_DIRECTIONS)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "OSRM routing failed HTTP ${conn.responseCode}: $err")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val routes = PhoneRoutes.parseOsrmRoutes(JSONObject(body))
            Log.i(TAG, "OSRM routing loaded: ${routes.size} route(s), ${routes.firstOrNull()?.steps?.size ?: 0} steps")
            routes
        } catch (e: Exception) {
            Log.w(TAG, "OSRM routing failed: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Phone-side Google routing (Addendum 162) — the first provider
     * of the routing plan when the phone Google key is usable.
     * NOTE: the Directions API is a separate enablement from the
     * APIs the phone key is documented for (Map Tiles, Places (New),
     * Roads); without it Google answers REQUEST_DENIED, the parse
     * yields no route, and the plan falls through to Mapbox/OSRM.
     * Empty on any failure.
     */
    fun fetchGoogleRoutes(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        key: String
    ): List<RouteOption> {
        val cleanKey = key.trim()
        if (cleanKey.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val origin = String.format(java.util.Locale.US, "%.6f,%.6f", startLat, startLng)
            val destination = String.format(java.util.Locale.US, "%.6f,%.6f", destLat, destLng)
            val urlStr = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=$origin&destination=$destination" +
                "&key=${URLEncoder.encode(cleanKey, "UTF-8")}"
            UsageCounters.count(UsageCounters.GOOGLE_DIRECTIONS)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Google routing failed HTTP ${conn.responseCode}: $err")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val routes = PhoneRoutes.parseGoogleDirections(json)
            if (routes.isEmpty()) {
                Log.w(TAG, "Google routing returned no route (status=${json.optString("status")})")
            } else {
                Log.i(TAG, "Google routing loaded: ${routes.size} route(s), ${routes.firstOrNull()?.steps?.size ?: 0} steps")
            }
            routes
        } catch (e: Exception) {
            Log.w(TAG, "Google routing failed: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The phone-route fetch (Addendum 162): walks the layer-2
     * routing plan ([PhoneSourceOrder.routingPlan], resolved by the
     * caller) in order — Google, Mapbox, OSRM — and returns the
     * first route any provider produces. A provider error or empty
     * result falls through to the next; when every provider fails
     * the result is null, [lastPhoneRouteError] says so, and phone
     * guidance stays in its degraded mode (no fake route is ever
     * installed). Runs on the caller's background thread.
     */
    fun fetchPhoneRoute(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        plan: List<PhoneSourceOrder.Source>,
        googleKey: String?,
        mapboxToken: String?
    ): RouteOption? {
        for (source in plan) {
            val routes = when (source) {
                PhoneSourceOrder.Source.GOOGLE ->
                    googleKey?.let { fetchGoogleRoutes(startLat, startLng, destLat, destLng, it) }
                        ?: emptyList()
                PhoneSourceOrder.Source.MAPBOX ->
                    mapboxToken?.let { fetchRoutes(startLat, startLng, destLat, destLng, it) }
                        ?: emptyList()
                PhoneSourceOrder.Source.OSM ->
                    fetchRoutesOSRM(startLat, startLng, destLat, destLng)
            }
            val route = routes.firstOrNull()
            if (route != null) {
                lastPhoneRouteError = null
                lastPhoneRouteSource = PhoneSourceOrder.limitSourceLabel(source)
                return route
            }
        }
        lastPhoneRouteError = "no provider in the routing plan returned a route"
        return null
    }

    fun fetchRoute(startLat: Double, startLng: Double, destLat: Double, destLng: Double, token: String): List<LatLng>? =
        fetchRoutes(startLat, startLng, destLat, destLng, token).firstOrNull()?.points

    /**
     * Sends navigation destination payload to Comma Galaxy API: POST /api/navigation.
     * Supports both direct local LAN (http://<ip>:8082) and Galaxy Cloud proxy (https://galaxy.firestar.link).
     * Includes automatic deduplication to suppress rapid duplicate calls.
     */
    fun sendToComma(target: String, dest: NavDestination, galaxyCookie: String? = null, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val isSameDest = lastSentDest?.isSameAs(dest) == true
        val isSameTarget = lastSentTarget == target
        val isSameRoute = lastSentRouteId == dest.routeId
        val isRecent = (now - lastSentTimeMs) < 5000L

        if (!force && isSameDest && isSameTarget && isSameRoute && isRecent) {
            Log.d(TAG, "Suppressed duplicate destination send to $target for '${dest.name}' (sent ${now - lastSentTimeMs}ms ago)")
            return true
        }

        // Legacy send follows the heartbeat-identified transport
        // (HeartbeatTransport): BLE only when the heartbeat identified
        // Bluetooth, the network path otherwise.
        val ble = if (HeartbeatTransport.useBle(NavStateManager.activeTransport, NavStateManager.connectionMode)) {
            BleBridgeClient.getConnectedClient()
        } else {
            null
        }
        if (ble != null) {
            try {
                val payload = JSONObject().apply {
                    put("latitude", dest.latitude)
                    put("longitude", dest.longitude)
                    put("name", dest.name)
                    put("place_name", dest.placeName)
                    put("routeId", dest.routeId)
                }
                val resp = ble.request(
                    method = "POST",
                    path = "/api/navigation",
                    body = payload.toString().toByteArray(Charsets.UTF_8),
                    headers = mapOf("Content-Type" to "application/json", "Accept" to "*/*"),
                    timeoutMs = 15000
                )
                if (resp.status in 200..299) {
                    lastSendError = null
                    lastSentDest = dest
                    lastSentTarget = "bluetooth"
                    lastSentRouteId = dest.routeId
                    lastSentTimeMs = System.currentTimeMillis()
                    Log.i(TAG, "Destination sent successfully over Bluetooth BLE: ${dest.name}")
                    return true
                } else {
                    Log.w(TAG, "Bluetooth BLE destination returned HTTP ${resp.status}")
                }
            } catch (e: Exception) {
                // The frame may have reached the comma before the
                // wait timed out (bug fix, 2026-11-09): do not
                // re-send the same destination POST over HTTP when
                // the comma still answers — its execution state is
                // unknowable from here, and a blind second send
                // restarts the route. Only a comma that no longer
                // answers at all provably never executed it.
                if (SgGalaxyClient.bestEffortExecConfirmed(target)) {
                    lastSendError = "Bluetooth send outcome unknown — not re-sent"
                    Log.w(TAG, "Bluetooth BLE send destination failed: ${e.message}; comma still reachable, NOT falling back to network")
                    return false
                }
                Log.w(TAG, "Bluetooth BLE send destination failed: ${e.message}, falling back to network")
            }
        }

        var conn: HttpURLConnection? = null
        return try {
            val isCloud = target.contains("galaxy.firestar.link") || target.startsWith("http")
            val url = if (isCloud) {
                val base = if (target.startsWith("http")) target else "https://$target"
                URL("$base/api/navigation")
            } else {
                URL("http://$target:$PORT/api/navigation")
            }
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = if (isCloud) 6000 else 3000
                readTimeout = if (isCloud) 8000 else 3000
                doOutput = true
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Content-Type", "application/json")
                val token = if (isCloud) {
                    GalaxyCloudSession.currentToken() ?: galaxyCookie?.removePrefix("${GalaxyCloudSession.COOKIE_NAME}=")
                } else {
                    GalaxyCloudSession.currentLocalToken()
                }
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                }
                if (isCloud) {
                    setRequestProperty("Origin", NetworkScanner.GALAXY_URL)
                    setRequestProperty("Referer", "${NetworkScanner.GALAXY_URL}/")
                    setRequestProperty("User-Agent", NetworkScanner.GALAXY_USER_AGENT)
                }
            }

            val payload = JSONObject().apply {
                put("latitude", dest.latitude)
                put("longitude", dest.longitude)
                put("name", dest.name)
                put("place_name", dest.placeName)
                put("routeId", dest.routeId)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            lastSendCode = code
            val ok = code == 200
            if (ok) {
                lastSendError = null
                lastSentDest = dest
                lastSentTarget = target
                lastSentRouteId = dest.routeId
                lastSentTimeMs = System.currentTimeMillis()
                Log.i(TAG, "Destination sent successfully to $target: ${dest.name}")
            } else {
                lastSendError = "comma answered HTTP $code"
                Log.w(TAG, "Failed to send destination to $target, response code: $code")
            }
            ok
        } catch (e: Exception) {
            lastSendError = e.message ?: e.javaClass.simpleName
            lastSendCode = -1
            Log.e(TAG, "Error sending destination to $target: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun clearCommaNavigation(target: String, galaxyCookie: String? = null): Boolean {
        resetLastSent()
        // Same heartbeat-transport rule as the legacy send above.
        val ble = if (HeartbeatTransport.useBle(NavStateManager.activeTransport, NavStateManager.connectionMode)) {
            BleBridgeClient.getConnectedClient()
        } else {
            null
        }
        if (ble != null) {
            try {
                val resp = ble.request(
                    method = "DELETE",
                    path = "/api/navigation",
                    headers = mapOf("Accept" to "*/*"),
                    timeoutMs = 10000
                )
                if (resp.status in 200..299) {
                    lastSendError = null
                    Log.i(TAG, "Cleared navigation over Bluetooth BLE")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth BLE clear nav failed: ${e.message}, falling back to network")
            }
        }
        var conn: HttpURLConnection? = null
        return try {
            val isCloud = target.contains("galaxy.firestar.link") || target.startsWith("http")
            val url = if (isCloud) {
                val base = if (target.startsWith("http")) target else "https://$target"
                URL("$base/api/navigation")
            } else {
                URL("http://$target:$PORT/api/navigation")
            }
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = if (isCloud) 6000 else 3000
                readTimeout = if (isCloud) 8000 else 3000
                val token = if (isCloud) {
                    GalaxyCloudSession.currentToken() ?: galaxyCookie?.removePrefix("${GalaxyCloudSession.COOKIE_NAME}=")
                } else {
                    GalaxyCloudSession.currentLocalToken()
                }
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Cookie", "${GalaxyCloudSession.COOKIE_NAME}=$token")
                }
                if (isCloud) {
                    setRequestProperty("Origin", NetworkScanner.GALAXY_URL)
                    setRequestProperty("Referer", "${NetworkScanner.GALAXY_URL}/")
                    setRequestProperty("User-Agent", NetworkScanner.GALAXY_USER_AGENT)
                }
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------------
    // SecretGoodStarPilot typed navigation actions (POST /api/navigation/action)
    // Used when NavStateManager.isSgGalaxy is true; LAN/hotspot only — Galaxy
    // Cloud cannot serve these. Every body carries the revision from the
    // latest status snapshot; a 409 triggers one re-fetch + retry.
    // ------------------------------------------------------------------------

    /**
     * Selects a destination on an SG comma: {"action":"select", ...}.
     * The destination counts as accepted only when the comma confirms it —
     * the action response or a follow-up status poll must show this
     * destination in the comma's own navigation state. Duplicate suppression
     * likewise keys off the last device-confirmed destination, never off a
     * merely-sent one.
     */
    /** Revision-less facade (audit finding 20): UI callers used to
     *  thread NavStateManager.sgRevision through every call site;
     *  the current revision is shared state and is read here. */
    fun sendToCommaSg(baseUrl: String, dest: NavDestination): Boolean =
        sendToCommaSg(baseUrl, dest, NavStateManager.sgRevision)

    fun sendToCommaSg(baseUrl: String, dest: NavDestination, revision: String?): Boolean {
        val now = System.currentTimeMillis()
        val isConfirmedSameDest = lastConfirmedDest?.isSameAs(dest) == true
        val isConfirmedSameTarget = lastConfirmedTarget == baseUrl
        val isConfirmationRecent = (now - lastConfirmedAtMs) < 5000L
        if (isConfirmedSameDest && isConfirmedSameTarget && isConfirmationRecent) {
            Log.d(TAG, "Suppressed duplicate SG destination send to $baseUrl for '${dest.name}' (already confirmed by comma)")
            return true
        }

        val destJson = JSONObject().apply {
            put("name", dest.name)
            put("latitude", dest.latitude)
            put("longitude", dest.longitude)
            val address = dest.placeName
            if (address.isNotBlank() && address != dest.name) put("address", address)
        }
        val (code, resp) = postNavActionWithRetry(SgGalaxyClient(baseUrl), revision) { rev ->
            JSONObject().apply {
                put("action", "select")
                put("destination", destJson)
                put("revision", rev)
            }
        }
        lastSendCode = code
        if (code != 200) {
            lastSendError = if (code == 401) {
                "comma refused the request (HTTP 401) — Galaxy sign-in/password needed for this connection"
            } else {
                "comma answered HTTP $code"
            }
            Log.w(TAG, "SG destination send to $baseUrl failed, code: $code")
            if (resp != null) applySgSnapshot(resp)
            return false
        }

        // Fire POST and forget: accept HTTP 200 without polling /api/navigation/status
        // to check whether the destination was confirmed on the comma.
        if (resp != null) applySgSnapshot(resp)
        lastConfirmedDest = dest
        lastConfirmedTarget = baseUrl
        lastConfirmedAtMs = System.currentTimeMillis()
        // Addendum 155: a successful send refreshes the pending
        // grace — the comma may still need time to publish the
        // destination in its status snapshots.
        NavStateManager.refreshDestinationPending(dest)
        NavStateManager.markTripStarted(dest)
        lastSendError = null
        lastSentDest = dest
        lastSentTarget = baseUrl
        lastSentRouteId = dest.routeId
        lastSentTimeMs = System.currentTimeMillis()
        Log.i(TAG, "SG destination sent to $baseUrl: ${dest.name}")
        return true
    }

    /**
     * Writes comma navigation settings: {"action":"configure","patch":…}
     * (Google update, Nov 2026 — see [GoogleNav] for the patch shapes).
     * Returns (httpCode, serverError?): on 200 the response snapshot
     * has been applied and the error is null; on failure the second
     * value is the comma's own error text (e.g. "Park your car before
     * changing a map key") for the caller to surface verbatim.
     */
    fun configureNavSettingsSg(baseUrl: String, patch: JSONObject): Pair<Int, String?> {
        val (code, resp) = postNavActionWithRetry(
            SgGalaxyClient(baseUrl), NavStateManager.sgRevision
        ) { rev ->
            JSONObject().apply {
                put("action", "configure")
                put("patch", patch)
                put("revision", rev)
            }
        }
        if (resp != null) applySgSnapshot(resp)
        val error = if (code == 200) {
            null
        } else {
            resp?.optString("error", "")?.ifEmpty { null } ?: "comma answered HTTP $code"
        }
        if (code != 200) {
            Log.w(TAG, "SG navigation configure to $baseUrl failed, code: $code, error: $error")
        }
        return code to error
    }


    /**
     * Tests a Google key against the comma's three Google APIs:
     * {"action":"testGoogleKey","key":…}. A null [key] tests the key
     * already saved on the comma. Returns (httpCode, responseJson?);
     * a 200 response is a status snapshot carrying "googleKeyTest"
     * (parse it with [GoogleNav.parseKeyTest]).
     */
    fun testGoogleKeySg(baseUrl: String, key: String?): Pair<Int, JSONObject?> {
        val (code, resp) = postNavActionWithRetry(
            SgGalaxyClient(baseUrl), NavStateManager.sgRevision
        ) { rev ->
            JSONObject().apply {
                put("action", "testGoogleKey")
                if (key != null) put("key", key)
                put("revision", rev)
            }
        }
        if (resp != null) applySgSnapshot(resp)
        return code to resp
    }

    /** Reads the comma's Wi-Fi manager snapshot (GET /api/galaxy/networks). */
    fun fetchNetworksSg(baseUrl: String): GalaxyNetworks? {
        val state = GalaxyNetworks.parse(SgGalaxyClient(baseUrl).fetchNetworks())
        if (state != null) NavStateManager.commaNetworks = state
        return state
    }

    /**
     * Runs one Wi-Fi manager action (POST /api/galaxy/networks) with a
     * body built by [GalaxyNetworks]. On 200 the result carries the
     * comma's fresh snapshot; on failure it carries the comma's own
     * error text verbatim (e.g. "Park before changing network
     * settings."). A BLE timeout whose outcome is unknowable reports
     * that honestly instead of claiming success or failure.
     */
    fun applyNetworksActionSg(
        baseUrl: String,
        body: JSONObject
    ): GalaxyNetworks.ActionResult {
        val (code, resp) = SgGalaxyClient(baseUrl).postNetworks(body)
        val state = if (code == 200) GalaxyNetworks.parse(resp) else null
        if (state != null) NavStateManager.commaNetworks = state
        val error = when {
            code == 200 -> null
            code == SgGalaxyClient.CODE_AMBIGUOUS ->
                "The comma didn't answer — the change may still have gone " +
                    "through. Reopen this page to check."
            else -> resp?.optString("error", "")?.ifEmpty { null }
                ?: "comma answered HTTP $code"
        }
        if (code != 200) {
            Log.w(TAG, "SG networks action to $baseUrl failed, code: $code, error: $error")
        }
        return GalaxyNetworks.ActionResult(code, state, error)
    }

    /** Clears navigation on an SG comma: {"action":"clear", ...}. */
    fun clearCommaNavigationSg(baseUrl: String): Boolean =
        clearCommaNavigationSg(baseUrl, NavStateManager.sgRevision)

    fun clearCommaNavigationSg(baseUrl: String, revision: String?): Boolean {
        resetLastSent()
        val (code, resp) = postNavActionWithRetry(SgGalaxyClient(baseUrl), revision) { rev ->
            JSONObject().apply {
                put("action", "clear")
                put("revision", rev)
            }
        }
        if (resp != null) applySgSnapshot(resp)
        if (code != 200) lastSendError = "comma answered HTTP $code" else lastSendError = null
        return code == 200
    }

    /** Selects a route alternative on an SG comma: {"action":"selectRoute", ...}. */
    fun selectRouteSg(baseUrl: String, index: Int): Boolean =
        selectRouteSg(baseUrl, index, NavStateManager.sgRevision)

    fun selectRouteSg(baseUrl: String, index: Int, revision: String?): Boolean {
        val (code, resp) = postNavActionWithRetry(SgGalaxyClient(baseUrl), revision) { rev ->
            JSONObject().apply {
                put("action", "selectRoute")
                put("index", index)
                put("revision", rev)
            }
        }
        if (resp != null) applySgSnapshot(resp)
        if (code != 200) lastSendError = "comma answered HTTP $code" else lastSendError = null
        return code == 200
    }

    // ------------------------------------------------------------------------
    // Saved places on the comma (Addendum 158): favorites / recents /
    // Home / Work live on the device — the phone keeps no copy. Bodies
    // are built by [CommaPlaceActions]; each answer is a status
    // snapshot, applied like every other action's, so the shared
    // comma lists refresh from the comma's own response.
    // ------------------------------------------------------------------------

    /**
     * Saves [dest] as a comma favorite, optionally labeled "home" /
     * "work". A temporary comma search suggestion (no coordinates of
     * its own) goes through favoritePlace — the comma retrieves the
     * place itself — and, exactly like selectPlace, must present the
     * search-time revision; the server keeps the search entry alive
     * and rebinds it to the save's new revision, which is recorded
     * here so a later selectPlace for the same suggestion still
     * matches. Any other place goes through favorite with its
     * coordinates. On failure [lastSendError] carries the comma's
     * own error text where it sent one.
     */
    fun favoriteCommaPlaceSg(
        baseUrl: String,
        dest: NavDestination,
        label: String? = null
    ): Boolean {
        val client = SgGalaxyClient(baseUrl)
        val placeId = dest.mapboxId
        val searchId = dest.sessionToken
        val isSuggestion = dest.source == "comma" && !dest.hasCoordinates &&
            !placeId.isNullOrEmpty() && !searchId.isNullOrEmpty()
        val (code, resp) = when {
            isSuggestion -> postNavActionWithRetry(
                client, commaSearchRevision ?: NavStateManager.sgRevision
            ) { rev -> CommaPlaceActions.favoritePlace(placeId!!, searchId!!, rev, label) }
            dest.hasCoordinates -> postNavActionWithRetry(
                client, NavStateManager.sgRevision
            ) { rev -> CommaPlaceActions.favorite(dest, rev, label) }
            else -> {
                lastSendError = "This place has no coordinates to save"
                return false
            }
        }
        lastSendCode = code
        if (resp != null) applySgSnapshot(resp)
        if (code != 200) {
            lastSendError = resp?.optString("error", "")?.ifEmpty { null }
                ?: if (code == 401) {
                    "comma refused the request (HTTP 401) — Galaxy sign-in/password needed for this connection"
                } else {
                    "comma answered HTTP $code"
                }
            Log.w(TAG, "SG favorite save to $baseUrl failed, code: $code")
            return false
        }
        if (isSuggestion && resp != null) {
            // The save rebound the search entry server-side to the
            // new revision; keep the recorded search revision in step.
            SgSnapshot.revisionOf(resp)?.let { commaSearchRevision = it }
        }
        lastSendError = null
        Log.i(TAG, "SG favorite saved to $baseUrl: ${dest.name}")
        return true
    }

    /** Removes a saved favorite: {"action":"removeFavorite", "id", ...}. */
    fun removeCommaFavoriteSg(baseUrl: String, favorite: NavDestination): Boolean =
        commaPlaceActionSg(baseUrl, "removeFavorite", favorite.commaId) { rev, id ->
            CommaPlaceActions.removeFavorite(id, rev)
        }

    /**
     * Sets or clears a favorite's Home/Work label:
     * {"action":"labelFavorite", "id", "label", ...}. A null [label]
     * clears it; the server keeps at most one favorite per label.
     */
    fun labelCommaFavoriteSg(
        baseUrl: String,
        favorite: NavDestination,
        label: String?
    ): Boolean {
        val id = favorite.commaId
        if (id.isNullOrEmpty()) {
            lastSendError = "This place is not saved on the comma"
            return false
        }
        val (code, resp) = postNavActionWithRetry(
            SgGalaxyClient(baseUrl), NavStateManager.sgRevision
        ) { rev -> CommaPlaceActions.labelFavorite(id, label, rev) }
        return finishCommaPlaceAction("labelFavorite", code, resp)
    }

    /** Removes one recent: {"action":"removeRecent", "id", ...}. */
    fun removeCommaRecentSg(baseUrl: String, recent: NavDestination): Boolean =
        commaPlaceActionSg(baseUrl, "removeRecent", recent.commaId) { rev, id ->
            CommaPlaceActions.removeRecent(id, rev)
        }

    /** Clears the comma's recents list: {"action":"clearRecents", ...}. */
    fun clearCommaRecentsSg(baseUrl: String): Boolean {
        val (code, resp) = postNavActionWithRetry(
            SgGalaxyClient(baseUrl), NavStateManager.sgRevision
        ) { rev -> CommaPlaceActions.clearRecents(rev) }
        return finishCommaPlaceAction("clearRecents", code, resp)
    }

    /** Shared tail for the id-addressed saved-place actions. */
    private fun commaPlaceActionSg(
        baseUrl: String,
        action: String,
        commaId: String?,
        buildBody: (String, String) -> JSONObject
    ): Boolean {
        if (commaId.isNullOrEmpty()) {
            lastSendError = "This place is not saved on the comma"
            return false
        }
        val (code, resp) = postNavActionWithRetry(
            SgGalaxyClient(baseUrl), NavStateManager.sgRevision
        ) { rev -> buildBody(rev, commaId) }
        return finishCommaPlaceAction(action, code, resp)
    }

    private fun finishCommaPlaceAction(
        action: String,
        code: Int,
        resp: JSONObject?
    ): Boolean {
        lastSendCode = code
        if (resp != null) applySgSnapshot(resp)
        if (code != 200) {
            lastSendError = resp?.optString("error", "")?.ifEmpty { null }
                ?: "comma answered HTTP $code"
            Log.w(TAG, "SG $action failed, code: $code")
            return false
        }
        lastSendError = null
        return true
    }

    /**
     * Chooses a temporary comma search suggestion:
     * {"action":"selectPlace", "id", "searchId", ...}. The comma retrieves
     * the place itself and sets the destination; the action response is a
     * status snapshot. The selection counts as accepted only when a snapshot
     * shows a destination — the same confirmation rule as [sendToCommaSg].
     *
     * Revision rule: the search session is bound server-side to the
     * navigation revision AT SEARCH TIME, so the selectPlace body carries
     * [commaSearchRevision] when one is recorded — not the caller's latest
     * revision, which may have moved on while the user read the results.
     * If the comma still rejects the selection as a stale search (400
     * "Search again before choosing this place"), the selection is
     * recovered exactly once: fresh session, re-run the recorded query,
     * select the same place under the new session.
     */
    fun selectCommaPlaceSg(baseUrl: String, dest: NavDestination): Boolean =
        selectCommaPlaceSg(baseUrl, dest, NavStateManager.sgRevision)

    fun selectCommaPlaceSg(baseUrl: String, dest: NavDestination, revision: String?): Boolean =
        selectCommaPlace(baseUrl, dest, revision, allowRetry = true)

    private fun selectCommaPlace(
        baseUrl: String,
        dest: NavDestination,
        revision: String?,
        allowRetry: Boolean
    ): Boolean {
        val placeId = dest.mapboxId
        val searchId = dest.sessionToken
        if (placeId.isNullOrEmpty() || searchId.isNullOrEmpty()) {
            lastSendError = "Search again before choosing this place"
            lastCommaSearchError = lastSendError
            lastSendCode = null
            return false
        }
        // Present the search-time revision (see the KDoc on
        // selectCommaPlaceSg); postNavActionWithRetry still re-fetches the
        // status snapshot and retries once with a fresh revision on a 409.
        val effectiveRevision = commaSearchRevision ?: revision
        val (code, resp) = postNavActionWithRetry(SgGalaxyClient(baseUrl), effectiveRevision) { rev ->
            JSONObject().apply {
                put("action", "selectPlace")
                put("id", placeId)
                put("searchId", searchId)
                put("revision", rev)
            }
        }
        lastSendCode = code
        if (code != 200) {
            val error = resp?.optString("error", "")?.ifEmpty { null }
            lastSendError = error ?: if (code == 401) {
                "comma refused the request (HTTP 401) — Galaxy sign-in/password needed for this connection"
            } else {
                "comma answered HTTP $code"
            }
            lastCommaSearchError = lastSendError
            Log.w(TAG, "SG selectPlace to $baseUrl failed, code: $code")
            if (resp != null) applySgSnapshot(resp)
            // A 400 — typically "Search again before choosing this place" —
            // means the search session went stale: its bound revision moved
            // on between searching and tapping. Recover exactly once with a
            // fresh session; when recovery is impossible, fall through and
            // keep this original failure (and its error text) intact.
            if (allowRetry && (code == 400 || lastSendError?.contains("Search again") == true)) {
                recoverCommaSearchSelection(baseUrl, dest, revision)?.let { return it }
            }
            return false
        }

        // Fire POST and forget: accept HTTP 200 without polling /api/navigation/status
        // to check whether the place was confirmed on the comma.
        lastCommaSearchError = null
        if (resp != null) applySgSnapshot(resp)
        val destObj = resp?.optJSONObject("destination")
        val cLat = destObj?.optDouble("latitude", destObj.optDouble("lat", Double.NaN)) ?: Double.NaN
        val cLng = destObj?.optDouble("longitude", destObj.optDouble("lng", Double.NaN)) ?: Double.NaN
        if (!cLat.isNaN() && !cLng.isNaN()) {
            val confirmed = NavDestination(
                name = destObj?.optString("name", "")?.ifEmpty { null } ?: dest.name,
                placeName = destObj?.optString("address", "")?.ifEmpty { null } ?: dest.placeName,
                latitude = cLat,
                longitude = cLng
            )
            lastConfirmedDest = confirmed
            lastConfirmedTarget = baseUrl
            lastConfirmedAtMs = System.currentTimeMillis()
            NavStateManager.markTripStarted(confirmed)

            NavStateManager.appContext?.let { ctx ->
                SearchCache.putResolved(ctx, confirmed)
                dest.mapboxId?.let { id ->
                    SearchCache.putResolved(ctx, confirmed.copy(mapboxId = id))
                }
            }
        } else {
            lastConfirmedDest = dest
            lastConfirmedTarget = baseUrl
            lastConfirmedAtMs = System.currentTimeMillis()
            NavStateManager.markTripStarted(dest)
        }
        // Addendum 155: a successful send refreshes the pending grace.
        NavStateManager.refreshDestinationPending(dest)
        lastSendError = null
        Log.i(TAG, "SG selectPlace sent to $baseUrl: ${dest.name}")
        return true
    }

    /**
     * One-shot recovery for a selectPlace the comma rejected because the
     * search session went stale: begin a fresh session, re-run the recorded
     * query, and select the same place — matched by its stable place id —
     * under the new session. The re-search re-records [commaSearchRevision]
     * on entry, so the retry presents the new session's bound revision.
     * Returns the retry's outcome, or null when recovery is not possible
     * (no recorded query / the place no longer appears) and the caller
     * keeps the original failure.
     */
    private fun recoverCommaSearchSelection(
        baseUrl: String,
        dest: NavDestination,
        revision: String?
    ): Boolean? {
        val query = commaSearchQuery ?: return null
        val placeId = dest.mapboxId ?: return null
        Log.w(TAG, "SG selectPlace: stale search session — re-searching '$query' once to recover")
        beginCommaSearchSession()
        val fresh = searchPlacesCommaSg(query, baseUrl)
            .firstOrNull { it.mapboxId == placeId } ?: return null
        return selectCommaPlace(baseUrl, fresh, revision, allowRetry = false)
    }

    /**
     * Cancels the in-flight comma search session:
     * {"action":"cancelSearch", "searchId", ...}. Fire-and-forget — the
     * result is ignored; callers run this on a background thread.
     */
    fun cancelCommaSearchSg(baseUrl: String) =
        cancelCommaSearchSg(baseUrl, NavStateManager.sgRevision)

    fun cancelCommaSearchSg(baseUrl: String, revision: String?) {
        val searchId = commaSearchId ?: return
        try {
            postNavActionWithRetry(SgGalaxyClient(baseUrl), revision) { rev ->
                JSONObject().apply {
                    put("action", "cancelSearch")
                    put("searchId", searchId)
                    put("revision", rev)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelCommaSearchSg error: ${e.message}")
        }
    }

    /**
     * Posts a navigation action; on a 409 revision conflict, re-fetches the
     * status snapshot for a fresh revision and retries exactly once.
     */
    private fun postNavActionWithRetry(
        client: SgGalaxyClient,
        revision: String?,
        buildBody: (String) -> JSONObject
    ): Pair<Int, JSONObject?> {
        var result = client.postNavAction(buildBody(revision ?: ""))
        if (result.first == 409) {
            val fresh = client.fetchNavStatus(NavStateManager.sgRouteKey)
            if (fresh != null) applySgSnapshot(fresh.json)
            val freshRevision = fresh?.json?.optString("revision", "")?.ifEmpty { null }
            if (freshRevision != null) {
                result = client.postNavAction(buildBody(freshRevision))
            }
        }
        return result
    }


    private fun applySgSnapshotInternal(json: JSONObject) {
        if (json.has("revision") || json.has("status") || json.has("routeKey")) {
            NavStateManager.sgNavStatus = json
        }
        SgSnapshot.revisionOf(json)?.let { NavStateManager.sgRevision = it }
        SgSnapshot.routeKeyOf(json)?.let { NavStateManager.sgRouteKey = it }

        // THE snapshot parse (layer 2, SgSnapshot) — shared with the
        // CommaClient poll's publish path (audit findings 1/10); this
        // function now applies only its own side-effects (arrival,
        // End-Route confidence, trip tracking, packet merge).
        val parsed = SgSnapshot.parse(json)

        applySnapshotMetadata(json)

        val hasValidDest = parsed.hasValidDest
        val statusStr = parsed.status
        val navEnded = parsed.navEnded

        // Rerouting (Feature 9): a routeKey change under the SAME active
        // destination means the comma recomputed the route mid-trip.
        val destKey = if (hasValidDest && !navEnded && parsed.destination != null) {
            String.format(
                java.util.Locale.US, "%.5f,%.5f",
                parsed.destination.latitude, parsed.destination.longitude
            )
        } else {
            null
        }
        val snapshotRouteKey = parsed.routeKey
        if (routeChangeTracker.onSnapshot(destKey, snapshotRouteKey)) {
            NavStateManager.reroutingAtMs = System.currentTimeMillis()
        }

        if (hasValidDest && !navEnded) {
            applySnapshotDestination(parsed)
        } else if (navEnded) {
            applySnapshotRouteEnd(parsed, statusStr)
        }

        applySnapshotGuidance(json)

        if (NavStateManager.activeDestination != null && !NavStateManager.navClearedByUser) {
            applySnapshotRoutes(parsed)
        } else {
            // Navigation not active: never set routes
            if (NavStateManager.activeRoutes.isNotEmpty()) {
                NavStateManager.activeRoutes = emptyList()
                NavStateManager.selectedRouteId = 0
            }
        }
        NavStateManager.notifyChanged()
    }

    /**
     * applySgSnapshotInternal, snapshot metadata (extracted
     * 2026-11-08): the comma's Mapbox usage, its recents/favorites,
     * the selected-route index, and the payload speed limit.
     */
    private fun applySnapshotMetadata(json: JSONObject) {
        // Comma Mapbox budget (Feature 6): the snapshot's mapboxUsage
        // object carries the comma's own monthly search usage; keep the
        // last known value when a snapshot doesn't carry it.
        MapboxUsage.parse(json.optJSONObject("mapboxUsage"))?.let {
            NavStateManager.mapboxUsage = it
        }

        // Comma navigation providers (Google update, Nov 2026): the
        // snapshot's mapProvider/routeProvider/google* fields; keep
        // the last known state when a snapshot doesn't carry them.
        GoogleNav.parse(json)?.let {
            NavStateManager.commaNavProviders = it
            NavStateManager.googleBudgetExhausted = it.budgetSwitchDue()
        }

        // The comma's own places (Feature 8): recents/favorites ride every
        // snapshot; replace the shared lists whenever the keys are present.
        if (json.has("recents")) {
            NavStateManager.commaRecents = CommaPlaces.parse(json.optJSONArray("recents"))
        }
        if (json.has("favorites")) {
            NavStateManager.commaFavorites = CommaPlaces.parse(json.optJSONArray("favorites"))
        }

        if (json.has("selectedRoute")) {
            NavStateManager.selectedRouteId = json.optInt("selectedRoute", 0)
        }

        // The one payload-limit parse (layer 2, SpeedLimitParse) —
        // this block used to re-probe the same keys with the opposite
        // unit heuristic to TelemetryPacket.fromSg (audit finding 2).
        SpeedLimitParse.payloadLimitMph(json)?.let { limMph ->
            if (NavStateManager.usePhoneSpeedLimit) {
                NavStateManager.speedLimitMph = limMph
            }
        }
    }

    /**
     * applySgSnapshotInternal, live-destination adoption
     * (extracted 2026-11-08): history/cache recording plus the
     * shared-state adoption, honoring the user-clear latch.
     */
    private fun applySnapshotDestination(parsed: SgSnapshot.Parsed) {
        val newDest = checkNotNull(parsed.destination) {
            "snapshot reported a valid destination but parsed none"
        }
        NavStateManager.appContext?.let { ctx ->
            SearchCache.putResolved(ctx, newDest)
        }
        val wasCleared = NavStateManager.navClearedByUser &&
            (NavStateManager.clearedDestination?.isSameAs(newDest) == true ||
             NavStateManager.activeDestination?.isSameAs(newDest) == true)
        if (!wasCleared) {
            if (NavStateManager.activeDestination?.isSameAs(newDest) != true) {
                NavStateManager.activeDestination = newDest
                NavStateManager.setNavClearedByUser(false)
                // A destination the app accepts supersedes any end state.
                NavStateManager.noteNewDestination()
            }
        }
        // Addendum 155: the snapshot carried the destination now
        // displayed — the comma has registered it.
        if (NavStateManager.activeDestination?.isSameAs(newDest) == true) {
            NavStateManager.noteDestinationConfirmedBySnapshot()
        }
    }

    /**
     * applySgSnapshotInternal, route-ended branch (extracted
     * 2026-11-08): device-confirmed end + arrival + clearing,
     * gated by the centralized destination-clear guard
     * (Addendum 155, [DestinationClearGuard]).
     */
    private fun applySnapshotRouteEnd(parsed: SgSnapshot.Parsed, statusStr: String) {
        // Addendum 155: same centralized decision the poll writer
        // makes — a bare destination-less snapshot keeps an
        // unconfirmed or queued destination (and a confirmed one
        // until 3 bare empties in a row); an explicit ended status
        // clears immediately.
        val explicitEnded = statusStr in SgSnapshot.ENDED_STATUSES
        if (NavStateManager.evaluateDestinationEnd(explicitEnded) ==
            DestinationClearGuard.Decision.KEEP
        ) return
        // End Route confidence (backlog #3): the comma's own
        // snapshot now reports no destination — the end is
        // confirmed by the device, not just by the clear action's
        // response. Also resolves STUCK when the device finally
        // lets go. No-op in any other state.
        NavStateManager.noteEndRouteSnapshotConfirmed()
        // Arrival (Feature 2): the comma says the trip is done.
        // Build the card content from the tracked trip BEFORE the
        // destination/routes are cleared below; totals the device
        // never provided stay 0 and render as "—".
        if (statusStr == "arrived") {
            NavStateManager.noteCommaArrival()
        }
        if (NavStateManager.activeRoutes.isNotEmpty()) {
            NavStateManager.activeRoutes = emptyList()
            NavStateManager.selectedRouteId = 0
        }
        if (NavStateManager.activeDestination != null) {
            NavStateManager.activeDestination = null
            NavStateManager.setNavClearedByUser(false)
            NavStateManager.clearedDestination = null
            NavStateManager.currentStreet = null
            NavStateManager.routeLimitSeries = null
            NavStateManager.routeSignals = emptyList()
            NavStateManager.routeStops = emptyList()
            NavStateManager.notifyChanged()
        }
    }

    /**
     * applySgSnapshotInternal, guidance merge (extracted
     * 2026-11-08): the instruction parse into the turn cue /
     * current street, the snapshot GPS into the shared position,
     * and both merged into the latest telemetry packet.
     */
    private fun applySnapshotGuidance(json: JSONObject) {
        val instr = json.optJSONObject("instruction")
        // The one layer-2 instruction parse (SgInstruction) — the same
        // one the CommaClient poll uses. This writer used to build the
        // maneuver inline with lanes hardcoded empty, which is what
        // made the AA lane strip blink against the poll's copy
        // (Param's report, 2026-11-06).
        val navManeuver = SgInstruction.parse(instr, json)
        if (navManeuver.valid) {
            val cue = if (navManeuver.street.isNotEmpty()) {
                if (navManeuver.type.isNotEmpty()) "${navManeuver.type} on ${navManeuver.street}" else navManeuver.street
            } else null
            if (cue != null) {
                NavStateManager.activeTurnCue = cue
            }
            if (navManeuver.street.isNotEmpty()) {
                NavStateManager.currentStreet = navManeuver.street
            }
        }

        val loc = json.optJSONObject("location")
        val gps = if (loc != null) {
            val lat = loc.optDouble("latitude", Double.NaN)
            val lng = loc.optDouble("longitude", Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                GpsPosition(
                    latitude = lat,
                    longitude = lng,
                    bearing = loc.optDouble("bearing", 0.0),
                    speed = NavStateManager.speedMs,
                    // Same validity rule as the packet parse
                    // (TelemetryPacket.fromSg): a lastKnown location
                    // carries validForMs 0 and is NOT a live fix
                    // (bug fix, 2026-11-09 — this writer hardcoded
                    // true, so a stale comma position counted as a
                    // live fix here and suppressed the phone fix in
                    // the drawing-position choice).
                    hasFix = if (loc.has("validForMs")) loc.optDouble("validForMs", 0.0) > 0.0 else true
                )
            } else null
        } else null
        if (gps != null) {
            NavStateManager.currentGps = gps
        }

        NavStateManager.updateLatestPacket { curPkt ->
            curPkt.copy(
                nav = if (navManeuver.valid) navManeuver else curPkt.nav,
                gps = gps ?: curPkt.gps,
                destination = NavStateManager.activeDestination ?: curPkt.destination
            )
        }
    }

    /**
     * applySgSnapshotInternal, route assignment (extracted
     * 2026-11-08): publish the snapshot's routes while navigating,
     * remember the selected route's totals for the arrival card,
     * and kick the speed-limit series fetch. The caller owns the
     * not-navigating clear and the final notifyChanged().
     */
    private fun applySnapshotRoutes(parsed: SgSnapshot.Parsed) {
        val selectedIdx = parsed.selectedRouteIndex
        val routesList = parsed.routes
        if (routesList.isNotEmpty()) {
            NavStateManager.activeRoutes = routesList
            // Arrival card totals (Feature 2): remember the comma's
            // selected-route totals for the tracked trip.
            val selRoute = routesList.firstOrNull { it.id == selectedIdx } ?: routesList.firstOrNull()
            if (selRoute != null) {
                NavStateManager.noteTripRouteTotals(selRoute.durationMin, selRoute.distanceMiles)
            }
            if (NavStateManager.usePhoneSpeedLimit && NavStateManager.routeLimitSeries == null) {
                CommaClient.instance?.maybeFetchRouteLimits(selRoute ?: routesList.first())
            }
            if (NavStateManager.usePhoneSignals || NavStateManager.usePhoneStops) {
                CommaClient.instance?.maybeFetchRouteFeatures(selRoute ?: routesList.first())
            }
        } else if (!parsed.routeUnchanged &&
            !parsed.hasRouteGeometry &&
            !parsed.hasValidDest &&
            NavStateManager.activeRoutes.isNotEmpty()
        ) {
            NavStateManager.activeRoutes = emptyList()
            NavStateManager.selectedRouteId = 0
        }
    }

    private fun parseDirectCoords(str: String): LatLng? {
        val parts = str.split(",")
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat in -90.0..90.0 && lng in -180.0..180.0) {
            return LatLng(lat, lng)
        }
        return null
    }

    companion object {
        private const val TAG = "NavigationManager"

        fun applySgSnapshot(json: JSONObject) {
            NavigationManager().applySgSnapshotInternal(json)
        }

        /**
         * Metadata-only snapshot application for the CommaClient
         * poll: refreshes the comma's provider + Google budget state
         * (and with it the 95% switch flag) without re-running the
         * full snapshot side-effects the poll deliberately splits
         * out (audit findings 1/10).
         */
        fun applySnapshotMetadataOnly(json: JSONObject) {
            NavigationManager().applySnapshotMetadata(json)
        }
        /** Shared reroute detector (Feature 9); see [RouteChangeTracker]. */
        private val routeChangeTracker = RouteChangeTracker()

        /**
         * No-op: the separate 1-second navigation refresh loop while navigating
         * has been removed so it does not duplicate CommaClient's poll loop or
         * flood the main thread during map gestures.
         */
        @Synchronized
        fun startNavigationRefresh(@Suppress("UNUSED_PARAMETER") endpointSupplier: () -> String?) {
            // Intentionally no-op: 1-second NavRefreshThread polling removed.
        }

        val isRefreshing: Boolean
            get() = false

        @Synchronized
        fun stopNavigationRefresh() {
            // Intentionally no-op.
        }
        private const val PORT = 8082
        private const val MAX_STREET_CACHE_ENTRIES = 256

        // Bounded LRU (review C4): access-ordered LinkedHashMap, all access
        // synchronized on the map itself; eldest entry evicted past 256.
        private val stepStreetCache: MutableMap<String, String> =
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, String>?
                ): Boolean = size > MAX_STREET_CACHE_ENTRIES
            }

        // One shared executor for Geocoder misses instead of a Thread per miss.
        private val streetGeocodeExecutor = Executors.newSingleThreadExecutor()

        fun resolveStepStreet(loc: LatLng, context: android.content.Context? = null): String? {
            val key = String.format(java.util.Locale.US, "%.4f,%.4f", loc.latitude, loc.longitude)
            synchronized(stepStreetCache) { stepStreetCache[key] }?.let { return it }

            if (context != null) {
                streetGeocodeExecutor.execute {
                    try {
                        if (android.location.Geocoder.isPresent()) {
                            val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                            @Suppress("DEPRECATION")
                            val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!list.isNullOrEmpty()) {
                                val street = list[0].thoroughfare ?: list[0].subLocality ?: list[0].featureName
                                if (!street.isNullOrBlank()) {
                                    synchronized(stepStreetCache) { stepStreetCache[key] = street }
                                    NavStateManager.notifyChanged()
                                }
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "Ignored failure: ${e.javaClass.simpleName}: ${e.message}") }
                }
            }
            return null
        }

        fun deriveStepsFromPolyline(points: List<LatLng>, destName: String): List<RouteStep> {
            if (points.size < 2) return emptyList()
            val steps = ArrayList<RouteStep>()
            var lastStepIdx = 0
            var accumDist = 0.0

            var i = 1
            while (i < points.size - 1) {
                val pCurr = points[i]
                accumDist += GuidanceMath.haversineM(points[i - 1].latitude, points[i - 1].longitude, pCurr.latitude, pCurr.longitude)

                // Smooth window: look back ~25m (or at least 1 vertex)
                var backIdx = i - 1
                var backDist = 0.0
                while (backIdx > 0 && backDist < 25.0) {
                    backDist += GuidanceMath.haversineM(points[backIdx].latitude, points[backIdx].longitude, points[backIdx - 1].latitude, points[backIdx - 1].longitude)
                    backIdx--
                }
                val pBack = points[backIdx]

                // Smooth window: look ahead ~25m (or at least 1 vertex)
                var fwdIdx = i + 1
                var fwdDist = 0.0
                while (fwdIdx < points.size - 1 && fwdDist < 25.0) {
                    fwdDist += GuidanceMath.haversineM(points[fwdIdx - 1].latitude, points[fwdIdx - 1].longitude, points[fwdIdx].latitude, points[fwdIdx].longitude)
                    fwdIdx++
                }
                val pFwd = points[fwdIdx]

                val bIn = calculateBearing(pBack.latitude, pBack.longitude, pCurr.latitude, pCurr.longitude)
                val bOut = calculateBearing(pCurr.latitude, pCurr.longitude, pFwd.latitude, pFwd.longitude)
                var diff = (bOut - bIn + 180.0) % 360.0 - 180.0
                if (diff < -180.0) diff += 360.0

                if (kotlin.math.abs(diff) >= 25.0) {
                    val mod = when {
                        diff > 105.0 -> "sharp right"
                        diff > 55.0 -> "right"
                        diff > 25.0 -> "slight right"
                        diff < -105.0 -> "sharp left"
                        diff < -55.0 -> "left"
                        else -> "slight left"
                    }
                    val actionStr = when (mod) {
                        "sharp right" -> "Sharp right"
                        "right" -> "Turn right"
                        "slight right" -> "Slight right"
                        "sharp left" -> "Sharp left"
                        "left" -> "Turn left"
                        else -> "Slight left"
                    }

                    // Pre-resolve street name for this upcoming intersection
                    val streetName = resolveStepStreet(pCurr, NavStateManager.appContext) ?: ""

                    steps.add(
                        RouteStep(
                            maneuverType = "turn",
                            maneuverModifier = mod,
                            street = streetName,
                            instruction = if (streetName.isNotEmpty()) "$actionStr onto $streetName" else actionStr,
                            distanceM = accumDist,
                            location = pCurr
                        )
                    )
                    lastStepIdx = i
                    accumDist = 0.0
                    // Jump past the current curve so the turn is not counted twice
                    i = fwdIdx
                } else {
                    i++
                }
            }

            val finalDist = GuidanceMath.haversineM(points[lastStepIdx].latitude, points[lastStepIdx].longitude, points.last().latitude, points.last().longitude)
            steps.add(
                RouteStep(
                    maneuverType = "arrive",
                    maneuverModifier = "arrive",
                    street = destName,
                    instruction = "Arrive at $destName",
                    distanceM = finalDist,
                    location = points.last()
                )
            )
            return steps
        }

        fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val y = kotlin.math.sin(Math.toRadians(lon2 - lon1)) * kotlin.math.cos(Math.toRadians(lat2))
            val x = kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.sin(Math.toRadians(lat2)) -
                    kotlin.math.sin(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.cos(Math.toRadians(lon2 - lon1))
            return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
        }

        /** Why the most recent comma send failed (HTTP code or exception); null after a success. */
        @Volatile
        var lastSendError: String? = null
            private set

        /** Why the most recent phone-route fetch failed (Addendum
         *  162); null after a success. The guidance driver journals
         *  it so an all-providers failure is visible, never silent. */
        @Volatile
        var lastPhoneRouteError: String? = null

        /** Which provider produced the current phone route
         *  ("google" / "mapbox" / "osm"); null until one does. */
        @Volatile
        var lastPhoneRouteSource: String? = null

        /** The most recent send's raw outcome code (Addendum 146):
         *  a positive HTTP status when the comma ANSWERED, -1 on a
         *  transport failure (nothing answered), null when no send
         *  has run or it failed before any request. The queue
         *  wording keys off this — see DestinationQueue.sendWasAnswered. */
        @Volatile
        var lastSendCode: Int? = null
            private set

        /** Records a send-path failure that happened before any send was attempted (e.g. an unresolvable selection), so the UI can surface the real reason. */
        fun noteSendError(message: String) {
            lastSendError = message
        }

        @Volatile
        private var lastSentDest: NavDestination? = null

        @Volatile
        private var lastSentTarget: String? = null

        @Volatile
        private var lastSentRouteId: String? = null

        @Volatile
        private var lastSentTimeMs: Long = 0L

        // Device-confirmed destination bookkeeping: a send counts as accepted
        // only when the comma's own navigation status shows this destination.
        // Duplicate suppression keys off this record, never off a mere send.
        @Volatile
        private var lastConfirmedDest: NavDestination? = null

        @Volatile
        private var lastConfirmedTarget: String? = null

        @Volatile
        private var lastConfirmedAtMs: Long = 0L

        /** Server-reported error ({error}) from the most recent comma search or selectPlace call; null after a 200. */
        @Volatile
        var lastCommaSearchError: String? = null

        // Comma search session state (POST /api/navigation/search): with an
        // autocomplete payload the server requires searchId and clientId to
        // be 36-char UUIDv4 strings. clientId is minted once per app run;
        // searchId rotates per destination entry (beginCommaSearchSession)
        // and once more on the server's stale-session 400.
        @Volatile
        private var commaClientId: String? = null

        @Volatile
        private var commaSearchId: String? = null

        // The comma binds a search session entry to the navigation
        // revision at search time, and selectPlace must present that same
        // revision — the latest snapshot revision (what callers pass in)
        // may already have moved on by the time the user taps a result.
        // Recorded on every searchPlacesCommaSg entry (see there for why
        // per-call recording tracks the server's entry binding).
        @Volatile
        var commaSearchRevision: String? = null

        /** The query that produced the current search session's results; used by the selectPlace stale-session recovery. */
        @Volatile
        var commaSearchQuery: String? = null

        /** In-memory cache for comma search results so repeat queries never hit comma again. */
        private val commaSearchCache = ConcurrentHashMap<String, List<NavDestination>>()

        /** Starts a fresh comma search session (rotates the searchId). */
        fun beginCommaSearchSession() {
            if (commaClientId == null) commaClientId = UUID.randomUUID().toString()
            commaSearchId = UUID.randomUUID().toString()
        }

        /** Current comma search ids, creating a session on first use. */
        private fun commaSearchIds(): Pair<String, String> {
            if (commaSearchId == null) beginCommaSearchSession()
            return commaSearchId!! to commaClientId!!
        }

        private fun rotateCommaSearchId() {
            commaSearchId = UUID.randomUUID().toString()
        }

        fun resetLastSent() {
            lastSentDest = null
            lastSentTarget = null
            lastSentRouteId = null
            lastSentTimeMs = 0L
            lastConfirmedDest = null
            lastConfirmedTarget = null
            lastConfirmedAtMs = 0L
        }

        // ------------------------------------------------------------------
        // Route cache (review A2): 3-minute TTL, keyed by start/dest rounded
        // to 4 decimals plus the provider variant ("mapbox" / "osrm").
        // Static so all NavigationManager instances share one cache, and the
        // per-key locks give in-flight dedup across threads/instances.
        // ------------------------------------------------------------------

        private const val ROUTE_CACHE_TTL_MS = 3L * 60 * 1000

        private val routeCache = ConcurrentHashMap<String, Pair<Long, List<RouteOption>>>()
        private val routeLocks = ConcurrentHashMap<String, Any>()

        fun routeCacheKey(
            startLat: Double,
            startLng: Double,
            destLat: Double,
            destLng: Double,
            variant: String
        ): String = String.format(
            java.util.Locale.US,
            "%.4f,%.4f>%.4f,%.4f:%s",
            startLat, startLng, destLat, destLng, variant
        )

        fun cachedRoute(key: String): List<RouteOption>? {
            val entry = routeCache[key] ?: return null
            return if (System.currentTimeMillis() - entry.first < ROUTE_CACHE_TTL_MS) {
                entry.second
            } else {
                routeCache.remove(key, entry)
                null
            }
        }

        fun routeLock(key: String): Any = routeLocks.computeIfAbsent(key) { Any() }

        fun storeRoute(key: String, routes: List<RouteOption>) {
            if (routes.isNotEmpty()) {
                routeCache[key] = System.currentTimeMillis() to routes
            }
        }

        fun decodePolyline(encoded: String): List<LatLng> {
            val poly = ArrayList<LatLng>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lng = 0

            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20)
                val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dlng

                poly.add(LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5))
            }
            return poly
        }

        fun encodePolyline(points: List<LatLng>): String {
            val str = StringBuilder()
            var prevLat = 0
            var prevLng = 0
            for (p in points) {
                val lat = kotlin.math.round((p.latitude * 1E5)).toInt()
                val lng = kotlin.math.round((p.longitude * 1E5)).toInt()
                encodeSignedValue(str, lat - prevLat)
                encodeSignedValue(str, lng - prevLng)
                prevLat = lat
                prevLng = lng
            }
            return str.toString()
        }

        private fun encodeSignedValue(str: StringBuilder, v: Int) {
            var s = if (v < 0) (v shl 1).inv() else (v shl 1)
            while (s >= 0x20) {
                str.append((((s and 0x1f) or 0x20) + 63).toChar())
                s = s shr 5
            }
            str.append((s + 63).toChar())
        }


        fun findDivergencePoint(altRoute: RouteOption, selRoute: RouteOption): LatLng? {
            if (altRoute.points.isEmpty()) return null
            if (selRoute.points.isEmpty()) return altRoute.points[altRoute.points.size / 2]

            var maxDistMeters = 0.0
            var bestPoint: LatLng = altRoute.points[altRoute.points.size / 2]

            val step = (altRoute.points.size / 30).coerceAtLeast(1)
            for (i in 0 until altRoute.points.size step step) {
                val p = altRoute.points[i]
                var minDistToSel = Double.MAX_VALUE
                val selStep = (selRoute.points.size / 30).coerceAtLeast(1)
                for (j in 0 until selRoute.points.size step selStep) {
                    val sp = selRoute.points[j]
                    val d = GuidanceMath.haversineM(p.latitude, p.longitude, sp.latitude, sp.longitude)
                    if (d < minDistToSel) minDistToSel = d
                }
                if (minDistToSel > maxDistMeters) {
                    maxDistMeters = minDistToSel
                    bestPoint = p
                }
            }
            return bestPoint
        }
    }
}
