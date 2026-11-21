package com.parampilot.auto

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Mapbox Directions `annotations=maxspeed` parsing + lookup — the
 * data behind the "Use phone speed limit" toggle.
 *
 * Why this exists: StarPilot's payload carries no speed limit
 * anywhere (source-checked 2026-11-04: the nav status snapshot,
 * the instruction object and /api/plots/live have no limit field,
 * and the comma's route geometry carries no annotations). The
 * comma's own route came from Mapbox Directions, so the phone
 * re-asks Mapbox Directions for the same origin/destination WITH
 * maxspeed annotations and reads the limit for the segment the car
 * is on. The result is labeled source "route" (never "comma").
 *
 * The annotation array aligns with the response geometry's
 * coordinates: entry i is the segment from coordinate i to i+1.
 * Entries are {"speed": n, "unit": "mph"|"km/h"} or
 * {"unknown": true}. Unknown/unparseable entries stay null — a
 * missing limit is shown as no limit, never invented.
 *
 * Pure: JSON in, values out; the network call lives in
 * NavigationManager.
 */
object MaxspeedAnnotations {

    /** The fetched series: geometry points + per-segment limits (mph, null = unknown). */
    data class Series(val points: List<LatLng>, val limitsMph: List<Int?>)

    /** Parses one leg's `annotation.maxspeed` array into per-segment mph values. */
    fun parseMaxspeed(annotation: JSONArray?): List<Int?> {
        if (annotation == null) return emptyList()
        val out = ArrayList<Int?>(annotation.length())
        for (i in 0 until annotation.length()) {
            out.add(parseEntry(annotation.optJSONObject(i)))
        }
        return out
    }

    private fun parseEntry(entry: JSONObject?): Int? {
        if (entry == null) return null
        if (entry.optBoolean("unknown", false)) return null
        if (!entry.has("speed") || entry.isNull("speed")) return null
        val speed = entry.optDouble("speed", Double.NaN)
        if (speed.isNaN() || speed <= 0.0) return null
        val unit = entry.optString("unit", "mph").lowercase()
        val mph = when {
            unit.contains("km") || unit.contains("kph") -> speed / 1.60934
            else -> speed
        }
        return mph.roundToInt().takeIf { it > 0 }
    }

    /** Parses a full Directions response (routes[0]) into a [Series], or null when it carries none. */
    fun parseDirectionsRoute(route: JSONObject?): Series? {
        if (route == null) return null
        val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        if (coords.length() < 2) return null
        val points = ArrayList<LatLng>(coords.length())
        for (i in 0 until coords.length()) {
            val pair = coords.optJSONArray(i) ?: return null
            if (pair.length() < 2) return null
            val lng = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return null
            points.add(LatLng(lat, lng))
        }
        val legs = route.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        val limits = ArrayList<Int?>()
        for (i in 0 until legs.length()) {
            val leg = legs.optJSONObject(i) ?: continue
            val ann = leg.optJSONObject("annotation")?.optJSONArray("maxspeed")
                ?: leg.optJSONObject("annotations")?.optJSONArray("maxspeed")
            limits.addAll(parseMaxspeed(ann))
        }
        if (limits.isEmpty() || limits.all { it == null }) return null
        return Series(points, limits)
    }

    /**
     * The limit (mph) for the segment the car is on: the segment
     * starting at the geometry point nearest the car. When that segment's
     * limit is unknown (common at intersections, driveways, parking lots or ramps in OSM),
     * searches backward (last traversed road) then forward (upcoming road along the route)
     * for the nearest known limit.
     */
    fun limitAt(series: Series, lat: Double, lng: Double): Int? {
        val points = series.points
        if (points.size < 2 || series.limitsMph.isEmpty()) return null
        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in points.indices) {
            val dLat = points[i].latitude - lat
            val dLng = (points[i].longitude - lng) * 0.85 // ~Phoenix latitude shrink for equirectangular distance
            val d = dLat * dLat + dLng * dLng
            if (d < best) {
                best = d
                nearest = i
            }
        }
        val segment = nearest.coerceAtMost(series.limitsMph.size - 1)
        val direct = series.limitsMph[segment]
        if (direct != null && direct > 0) return direct

        // Fallback 1: search backwards along the route (the road the car was just on)
        for (step in 1..segment) {
            val behind = segment - step
            val lim = series.limitsMph[behind]
            if (lim != null && lim > 0) return lim
        }

        // Fallback 2: search forward along the route (the upcoming road on this trip)
        for (ahead in (segment + 1) until series.limitsMph.size) {
            val lim = series.limitsMph[ahead]
            if (lim != null && lim > 0) return lim
        }

        return null
    }
}
