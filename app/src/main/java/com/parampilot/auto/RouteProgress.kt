package com.parampilot.auto

import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Live progress along a route polyline: projects the current GPS position
 * onto the route and reports how much route is left. This is what lets the
 * maneuver card and travel estimate show distance/time to the *destination*
 * (like the comma's own projected UI) instead of only to the next step.
 */
object RouteProgress {

    data class Result(
        val remainingM: Double,
        val fractionRemaining: Double,
        val segmentIndex: Int,
        val segmentT: Double
    )

    /**
     * Memoized segment lengths for one points list. Segment lengths are
     * computed in the GPS-local equirectangular projection, so they scale
     * with cos(gpsLat): the cache key therefore also carries a 0.01° latitude
     * band of the GPS position, which bounds the reuse error to ~0.01% while
     * still hitting for the whole time the car stays in the band.
     */
    private class SegLensCache(
        val points: List<LatLng>,
        val size: Int,
        val latBand: Int,
        val firstLat: Double,
        val firstLng: Double,
        val lastLat: Double,
        val lastLng: Double,
        val segLens: DoubleArray,
        val total: Double
    )

    @Volatile
    private var segLensCache: SegLensCache? = null

    private fun segmentLengths(points: List<LatLng>, gpsLat: Double): Pair<DoubleArray, Double> {
        val latBand = (gpsLat * 100.0).toInt()
        val cached = segLensCache
        if (cached != null &&
            cached.points === points &&
            cached.size == points.size &&
            cached.latBand == latBand &&
            cached.firstLat == points.first().latitude &&
            cached.firstLng == points.first().longitude &&
            cached.lastLat == points.last().latitude &&
            cached.lastLng == points.last().longitude
        ) {
            return cached.segLens to cached.total
        }
        val earthR = 6371000.0
        val cosLat = cos(Math.toRadians(gpsLat))
        val segLens = DoubleArray(points.size - 1)
        var total = 0.0
        for (i in segLens.indices) {
            val dx = earthR * cosLat * Math.toRadians(points[i + 1].longitude - points[i].longitude)
            val dy = earthR * Math.toRadians(points[i + 1].latitude - points[i].latitude)
            val len = sqrt(dx * dx + dy * dy)
            segLens[i] = len
            total += len
        }
        segLensCache = SegLensCache(
            points, points.size, latBand,
            points.first().latitude, points.first().longitude,
            points.last().latitude, points.last().longitude,
            segLens, total
        )
        return segLens to total
    }

    fun compute(points: List<LatLng>, gpsLat: Double, gpsLng: Double): Result? {
        if (points.size < 2) return null
        val earthR = 6371000.0
        val lat0 = Math.toRadians(gpsLat)
        val cosLat = cos(lat0)
        fun x(lng: Double) = earthR * cosLat * Math.toRadians(lng - gpsLng)
        fun y(lat: Double) = earthR * Math.toRadians(lat - gpsLat)

        // Segment lengths are memoized per points-list instance; only the
        // projection search below runs against the live GPS position.
        val (segLens, total) = segmentLengths(points, gpsLat)
        var bestDist = Double.MAX_VALUE
        var bestSeg = 0
        var bestT = 0.0

        for (i in segLens.indices) {
            val ax = x(points[i].longitude)
            val ay = y(points[i].latitude)
            val bx = x(points[i + 1].longitude)
            val by = y(points[i + 1].latitude)
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            // GPS sits at the local origin (0,0).
            val t = (((0.0 - ax) * dx + (0.0 - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestT = t
            }
        }
        if (total <= 0.0) return null

        var remaining = segLens[bestSeg] * (1.0 - bestT)
        for (i in bestSeg + 1 until segLens.size) remaining += segLens[i]
        return Result(
            remainingM = remaining,
            fractionRemaining = remaining / total,
            segmentIndex = bestSeg,
            segmentT = bestT
        )
    }
}
