package com.parampilot.auto

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Layer 2: OpenStreetMap `maxspeed` ways as a speed-limit series —
 * the third, key-free rung of the phone speed-limit source order
 * (Addendum 161, Param 2026-11-28). The data comes from the same
 * Overpass API the stop signs already use (NavigationManager
 * fetches `way["maxspeed"]` with `out geom` around the route);
 * this object is the parse + series build.
 *
 * The series is built on the ROUTE's own points, so the shared
 * display path (MaxspeedAnnotations.limitAt via
 * NavStateManager.updateSpeedLimitFromRoute) reads it exactly like
 * the Google and Mapbox series: segment i (route point i → i+1)
 * takes the limit of the tagged way whose geometry runs nearest
 * route point i, and stays null when no tagged way is within
 * [CORRIDOR_M] of it. A stretch OSM has no limit for stays
 * no-limit — never filled in from a distant road.
 *
 * Unit rule (the OSM spec): a maxspeed value is km/h unless it
 * carries an explicit unit — "25 mph" → 25 mph, "50" → 50 km/h →
 * 31 mph, "50 km/h" → 31 mph. Non-numeric tags ("signals", "none",
 * "US:urban", …) carry no plain speed and stay unknown.
 *
 * Pure: JSON in, values out; the network call lives in
 * NavigationManager.
 */
object OsmMaxspeed {

    /** A tagged way farther than this from a route point is not
     *  the road the route runs on (same corridor as the route
     *  feature markers, RouteFeatures.CORRIDOR_M). */
    const val CORRIDOR_M = 45.0

    /** One maxspeed-tagged OSM way: its geometry + limit in mph. */
    data class Way(val points: List<LatLng>, val limitMph: Int)

    private const val METERS_PER_DEG_LAT = 111139.0
    private const val KMH_PER_MPH = 1.60934

    /** A raw OSM `maxspeed` tag → mph, or null when it carries no plain speed. */
    fun parseMaxspeedMph(raw: String?): Int? {
        if (raw == null) return null
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        val numStr = text.takeWhile { it.isDigit() || it == '.' }
        val value = numStr.toDoubleOrNull() ?: return null
        if (value <= 0.0) return null
        val mph = when (text.substring(numStr.length).trim()) {
            "mph" -> value
            "", "km/h", "kmh", "kph", "kmph" -> value / KMH_PER_MPH
            else -> return null
        }
        return mph.roundToInt().takeIf { it > 0 }
    }

    /** The maxspeed-tagged ways of one Overpass `out geom` response. */
    fun parseWays(body: JSONObject): List<Way> {
        val elements = body.optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Way>()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "way") continue
            val limit = parseMaxspeedMph(el.optJSONObject("tags")?.optString("maxspeed"))
                ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            val pts = ArrayList<LatLng>()
            for (j in 0 until geom.length()) {
                val g = geom.optJSONObject(j) ?: continue
                val lat = g.optDouble("lat", Double.NaN)
                val lon = g.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                pts.add(LatLng(lat, lon))
            }
            if (pts.size >= 2) out.add(Way(pts, limit))
        }
        return out
    }

    /**
     * The series for [routePoints] from the tagged [ways] along it,
     * or null when no route point has a tagged way within the
     * corridor (OSM simply has no limit for this route — the caller
     * then shows no limit, as with the other sources' failures).
     */
    fun buildSeries(
        routePoints: List<LatLng>,
        ways: List<Way>
    ): MaxspeedAnnotations.Series? {
        if (routePoints.size < 2 || ways.isEmpty()) return null
        // Flatten the tagged ways' vertices once: the nearest
        // vertex to a route point names the road that point runs
        // along (a way's limit is constant along the way).
        val verts = ArrayList<Pair<LatLng, Int>>()
        for (w in ways) for (p in w.points) verts.add(p to w.limitMph)
        if (verts.isEmpty()) return null
        val corridor2 = CORRIDOR_M * CORRIDOR_M
        val limits = ArrayList<Int?>(routePoints.size - 1)
        var any = false
        for (i in 0 until routePoints.size - 1) {
            val p = routePoints[i]
            val kx = METERS_PER_DEG_LAT * cos(Math.toRadians(p.latitude))
            var best = Double.MAX_VALUE
            var bestLimit: Int? = null
            for ((v, lim) in verts) {
                val dx = (v.longitude - p.longitude) * kx
                val dy = (v.latitude - p.latitude) * METERS_PER_DEG_LAT
                val d = dx * dx + dy * dy
                if (d < best) {
                    best = d
                    bestLimit = lim
                }
            }
            val limit = if (bestLimit != null && best <= corridor2) bestLimit else null
            if (limit != null) any = true
            limits.add(limit)
        }
        return if (any) MaxspeedAnnotations.Series(routePoints, limits) else null
    }
}
