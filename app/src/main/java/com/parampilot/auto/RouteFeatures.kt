package com.parampilot.auto

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Route feature markers — traffic signals and stop signs ALONG the
 * active route (Param's ask, 2026-11-10: "Add signals and stop signs
 * to map. On both phone and auto", "Along the route").
 *
 * Sources (both phone-side; the comma's payloads carry neither —
 * source-checked 2026-11-10: the Galaxy live sample is a fixed
 * allowlist with no sign data, and the comma's vision stop signs
 * exist only as camera-frame pixel boxes with no position):
 * - Signals: Mapbox Streets v8 carries traffic-light POINTS
 *   (road layer, type=traffic_signals), queried per route sample
 *   point via the Mapbox Tilequery API. Static locations only —
 *   Mapbox has no live signal state, so a marker never claims a
 *   color. Gated by the saved Mapbox token, like the speed limit.
 * - Stop signs: OpenStreetMap nodes (highway=stop) via the Overpass
 *   API. Mapbox does NOT carry stop signs in Streets v8 (checked
 *   against the v8 layer reference). OSM's US stop coverage is
 *   community-mapped and patchy: a missing marker never proves
 *   there is no stop sign.
 *
 * Each source has its OWN Phone Data toggle ("Don't merge
 * settings"): usePhoneSignals / usePhoneStops. Toggle off = that
 * source is never fetched and nothing of it is drawn.
 *
 * Pure: JSON in, values out; the network calls live in
 * NavigationManager, the state in NavStateManager, the drawing in
 * MainActivity / CarMapRenderer.
 */
object RouteFeatures {

    enum class Kind { SIGNAL, STOP }

    /** One marker to draw: its kind, its real position, and how far
     *  along the route it sits (for ordering and caps). */
    data class Marker(val kind: Kind, val position: LatLng, val alongRouteM: Double)

    /** A candidate farther than this from every route segment is
     *  not "along the route" (it belongs to a side/cross street). */
    const val CORRIDOR_M = 45.0

    /** Two candidates of the same kind closer than this are the
     *  same junction reported twice (tile overlap / OSM splits). */
    const val MERGE_RADIUS_M = 25.0

    /** Tilequery sample spacing along the route. */
    const val SAMPLE_SPACING_M = 300.0

    /** Bounds the per-route Tilequery calls on very long routes. */
    const val MAX_SAMPLES = 90

    const val MAX_MARKERS = 400

    private const val METERS_PER_DEG_LAT = 111139.0

    /**
     * Traffic-signal points from one Tilequery response
     * (mapbox-streets-v8, layers=road): GeoJSON features whose
     * properties.type is "traffic_signals". Everything else the
     * road layer returns (junctions, roundabouts, crossings) is
     * ignored. Coordinates are [lng, lat].
     */
    fun parseTilequerySignals(body: JSONObject): List<LatLng> {
        val features = body.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<LatLng>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val props = f.optJSONObject("properties") ?: continue
            if (props.optString("type") != "traffic_signals") continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lng = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) continue
            out.add(LatLng(lat, lng))
        }
        return out
    }

    /**
     * Stop-sign points from one Overpass response: elements of
     * type "node" (the query asks only for highway=stop nodes, so
     * every node element is one stop). Coordinates are lat/lon
     * fields, not a geometry array.
     */
    fun parseOverpassStops(body: JSONObject): List<LatLng> {
        val elements = body.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<LatLng>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "node") continue
            val lat = el.optDouble("lat", Double.NaN)
            val lon = el.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            out.add(LatLng(lat, lon))
        }
        return out
    }

    /**
     * Evenly spaced sample points along the route for the Tilequery
     * sweep: the first point, one every [spacingM] (widened on long
     * routes so the count stays under [MAX_SAMPLES]), and the last
     * point.
     */
    fun samplePoints(routePoints: List<LatLng>, spacingM: Double = SAMPLE_SPACING_M): List<LatLng> {
        if (routePoints.isEmpty()) return emptyList()
        if (routePoints.size == 1) return listOf(routePoints.first())
        var total = 0.0
        for (i in 1 until routePoints.size) {
            total += GuidanceMath.haversineM(
                routePoints[i - 1].latitude, routePoints[i - 1].longitude,
                routePoints[i].latitude, routePoints[i].longitude
            )
        }
        if (total <= 0.0) return listOf(routePoints.first(), routePoints.last())
        val spacing = maxOf(spacingM, total / MAX_SAMPLES)
        val out = ArrayList<LatLng>()
        out.add(routePoints.first())
        var covered = 0.0
        var nextAt = spacing
        for (i in 1 until routePoints.size) {
            val a = routePoints[i - 1]
            val b = routePoints[i]
            val segLen = GuidanceMath.haversineM(a.latitude, a.longitude, b.latitude, b.longitude)
            if (segLen <= 0.0) continue
            while (covered + segLen >= nextAt) {
                val t = (nextAt - covered) / segLen
                out.add(
                    LatLng(
                        a.latitude + (b.latitude - a.latitude) * t,
                        a.longitude + (b.longitude - a.longitude) * t
                    )
                )
                nextAt += spacing
            }
            covered += segLen
        }
        if (out.last() != routePoints.last()) out.add(routePoints.last())
        return out
    }

    /**
     * A cheap identity for a route's GEOMETRY: total length, point
     * count, and the middle point. Two alternative routes between
     * the same endpoints share the endpoints (and often the point
     * count) but differ in length or middle in practice — the old
     * endpoints+count key could not tell them apart (field report,
     * 2026-11-10: markers were not refetched when an alternative
     * route was selected).
     */
    fun fingerprint(routePoints: List<LatLng>): String {
        if (routePoints.isEmpty()) return "empty"
        var total = 0.0
        for (i in 1 until routePoints.size) {
            total += GuidanceMath.haversineM(
                routePoints[i - 1].latitude, routePoints[i - 1].longitude,
                routePoints[i].latitude, routePoints[i].longitude
            )
        }
        val mid = routePoints[routePoints.size / 2]
        return String.format(
            java.util.Locale.US, "%.4f,%.4f#%d@%d@%.4f,%.4f",
            routePoints.first().latitude, routePoints.first().longitude,
            routePoints.size, total.roundToInt(), mid.latitude, mid.longitude
        )
    }

    /** Max emoji markers baked into one Mapbox static frame (URL budget). */
    const val MAX_STATIC_MARKERS = 24

    /** Twemoji PNGs (72×72) for the AA static-frame markers. */
    private const val EMOJI_BASE =
        "https://cdn.jsdelivr.net/npm/twemoji@14.0.2/assets/72x72"
    const val SIGNAL_EMOJI_URL = "$EMOJI_BASE/1f6a6.png"
    const val STOP_EMOJI_URL = "$EMOJI_BASE/1f6d1.png"

    /**
     * Mapbox Static Images emoji overlays for the route feature
     * markers (Param, 2026-11-18: "Convert pins to traffic 🚦 on
     * auto"). On AA the Mapbox base is a static image rendered with
     * bearing + pitch, a projection the canvas cannot reproduce — so
     * the markers are baked into the image request itself, the same
     * mechanism that already bakes the route path, and Mapbox places
     * them in its own projection at any pitch. Mapbox cannot draw an
     * emoji, so each marker is a `url-` overlay: Mapbox fetches the
     * Twemoji PNG (🚦 for signals, 🛑 for stop signs) and composites
     * it at the coordinate. Both lists are along-route sorted, so
     * the [MAX_STATIC_MARKERS] budget goes to the markers nearest
     * the route start. Returns "" when there is nothing to bake.
     */
    fun staticEmojiOverlay(signals: List<Marker>, stops: List<Marker>): String {
        val parts = ArrayList<String>()
        for (m in signals) {
            if (parts.size >= MAX_STATIC_MARKERS) break
            parts.add(emojiMarker(SIGNAL_EMOJI_URL, m.position))
        }
        for (m in stops) {
            if (parts.size >= MAX_STATIC_MARKERS) break
            parts.add(emojiMarker(STOP_EMOJI_URL, m.position))
        }
        return parts.joinToString(",")
    }

    private fun emojiMarker(imageUrl: String, p: LatLng): String =
        String.format(
            java.util.Locale.US, "url-%s(%.5f,%.5f)",
            java.net.URLEncoder.encode(imageUrl, "UTF-8"), p.longitude, p.latitude
        )

    /** Identity of a marker set, for static-frame cache keys. */
    fun markerSetKey(signals: List<Marker>, stops: List<Marker>): String {
        fun h(list: List<Marker>): Int {
            var x = list.size
            for (m in list) {
                x = x * 31 + (m.position.latitude * 100000).toInt()
                x = x * 31 + (m.position.longitude * 100000).toInt()
            }
            return x
        }
        return "s${h(signals)}t${h(stops)}"
    }

    /**
     * Whether markers fetched for [markerKey] are stale while
     * [currentKey] is the selected route's key: markers exist and
     * were fetched for a DIFFERENT route geometry. Stale markers are
     * wrong data on the map — they come off immediately, and the
     * fetch for the current route replaces them when it lands
     * (field report, 2026-11-12: traffic signals painted along the
     * alternative route after the navigated route changed).
     */
    fun markersStale(markerKey: String?, currentKey: String, hasMarkers: Boolean): Boolean =
        hasMarkers && markerKey != currentKey

    /**
     * Keeps the candidates that lie along the route (within
     * [corridorM] of some segment), tags each with its along-route
     * distance, sorts by it, and merges duplicates of the same
     * junction. The marker keeps the candidate's OWN coordinates —
     * positions are never snapped or invented.
     */
    fun alongRoute(
        routePoints: List<LatLng>,
        candidates: List<LatLng>,
        kind: Kind,
        corridorM: Double = CORRIDOR_M
    ): List<Marker> {
        if (routePoints.size < 2 || candidates.isEmpty()) return emptyList()
        val cum = DoubleArray(routePoints.size)
        for (i in 1 until routePoints.size) {
            cum[i] = cum[i - 1] + GuidanceMath.haversineM(
                routePoints[i - 1].latitude, routePoints[i - 1].longitude,
                routePoints[i].latitude, routePoints[i].longitude
            )
        }
        val found = ArrayList<Marker>()
        for (c in candidates) {
            var bestDist = Double.MAX_VALUE
            var bestAlong = 0.0
            for (i in 1 until routePoints.size) {
                val a = routePoints[i - 1]
                val b = routePoints[i]
                val kx = METERS_PER_DEG_LAT * cos(Math.toRadians(a.latitude))
                val bx = (b.longitude - a.longitude) * kx
                val by = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
                val cx = (c.longitude - a.longitude) * kx
                val cy = (c.latitude - a.latitude) * METERS_PER_DEG_LAT
                val len2 = bx * bx + by * by
                val t = if (len2 <= 0.0) 0.0 else ((cx * bx + cy * by) / len2).coerceIn(0.0, 1.0)
                val dx = cx - t * bx
                val dy = cy - t * by
                val d = sqrt(dx * dx + dy * dy)
                if (d < bestDist) {
                    bestDist = d
                    bestAlong = cum[i - 1] + t * (cum[i] - cum[i - 1])
                }
            }
            if (bestDist <= corridorM) {
                found.add(Marker(kind, c, bestAlong))
            }
        }
        found.sortBy { it.alongRouteM }
        val out = ArrayList<Marker>()
        for (m in found) {
            val last = out.lastOrNull()
            if (last == null ||
                GuidanceMath.haversineM(
                    last.position.latitude, last.position.longitude,
                    m.position.latitude, m.position.longitude
                ) > MERGE_RADIUS_M
            ) {
                out.add(m)
            }
            if (out.size >= MAX_MARKERS) break
        }
        return out
    }
}
