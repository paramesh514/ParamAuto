package com.parampilot.auto

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Layer 2: Google Roads API parsing for the phone speed limit
 * (Param, 2026-11-18 — "Also use Google speed limit").
 *
 * The flow: snap the active route's geometry to roads
 * (snapToRoads, which tags every snapped point with the placeId of
 * its road segment), then ask speedLimits for those placeIds. The
 * result is shaped into the same MaxspeedAnnotations.Series the
 * Mapbox maxspeed flow produces, so the display path
 * (NavStateManager.updateSpeedLimitFromRoute → limitAt) is shared
 * and only the fetch differs. A segment with no Google limit stays
 * null — no limit shown, never an invented one.
 *
 * Pure: values/JSON in, values out; the HTTP lives in
 * NavigationManager.
 */
object GoogleRoads {

    /** snapToRoads accepts at most 100 path points per request. */
    const val MAX_PATH_POINTS = 100

    /**
     * Evenly samples [points] down to at most [max] entries, always
     * keeping the first and the last point.
     */
    fun sample(points: List<LatLng>, max: Int = MAX_PATH_POINTS): List<LatLng> {
        if (points.size <= max) return points
        if (max < 2) return listOf(points.first())
        val out = ArrayList<LatLng>(max)
        val step = (points.size - 1).toDouble() / (max - 1)
        for (i in 0 until max) out.add(points[(i * step).toInt()])
        out[out.size - 1] = points.last()
        return out
    }

    data class Snapped(val point: LatLng, val placeId: String)

    /** Parses a snapToRoads response; points without a placeId carry no limit and are skipped. */
    fun parseSnapped(root: JSONObject): List<Snapped> {
        val arr = root.optJSONArray("snappedPoints") ?: return emptyList()
        val out = ArrayList<Snapped>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val loc = o.optJSONObject("location") ?: continue
            val placeId = o.optString("placeId", "")
            if (placeId.isBlank()) continue
            out.add(
                Snapped(
                    LatLng(
                        loc.optDouble("latitude", 0.0),
                        loc.optDouble("longitude", 0.0)
                    ),
                    placeId
                )
            )
        }
        return out
    }

    /**
     * placeId → speed limit in mph. The request asks for units=MPH;
     * a km/h answer is converted defensively rather than trusted.
     */
    fun parseSpeedLimits(root: JSONObject): Map<String, Int> {
        val arr = root.optJSONArray("speedLimits") ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val placeId = o.optString("placeId", "")
            val limit = o.optDouble("speedLimit", Double.NaN)
            if (placeId.isBlank() || limit.isNaN() || limit <= 0.0) continue
            val units = o.optString("units", "MPH").uppercase()
            val mph = if (units.contains("KPH") || units.contains("KM")) {
                limit / 1.60934
            } else {
                limit
            }
            val rounded = mph.roundToInt()
            if (rounded > 0) out[placeId] = rounded
        }
        return out
    }

    /**
     * Builds the shared Series shape: segment i (point i → i+1)
     * takes the limit of point i's road segment, matching how
     * MaxspeedAnnotations.limitAt reads it.
     */
    fun buildSeries(
        snapped: List<Snapped>,
        limitsByPlace: Map<String, Int>
    ): MaxspeedAnnotations.Series? {
        if (snapped.size < 2) return null
        val limits = snapped.map { limitsByPlace[it.placeId] }
        if (limits.all { it == null }) return null
        return MaxspeedAnnotations.Series(snapped.map { it.point }, limits)
    }
}
