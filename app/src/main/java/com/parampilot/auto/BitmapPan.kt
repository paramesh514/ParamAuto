package com.parampilot.auto

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.tan

/**
 * Pure geometry for the Android Auto surface map (no Android imports, unit
 * tested): the stitched base map is fetched centered on the vehicle
 * position at fetch time and refreshed only on gated thresholds, which made
 * the map look frozen between fetches (Param, 2026-11-03: "Car location not
 * moving on Auto screen"). [translation] gives the canvas shift that keeps
 * the CURRENT gps position on the same screen anchor (0.5w, 0.6h) the
 * bitmap was fetched around, so the map tracks the car continuously
 * between refetches.
 */
object BitmapPan {

    // ---- Web-Mercator world pixel coordinates -------------------------------

    fun worldX(lng: Double, zoom: Double): Double {
        val world = 256.0 * Math.pow(2.0, zoom)
        return (lng + 180.0) / 360.0 * world
    }

    fun worldY(lat: Double, zoom: Double): Double {
        val world = 256.0 * Math.pow(2.0, zoom)
        val rad = Math.toRadians(lat)
        return (1.0 - asinh(tan(rad)) / PI) / 2.0 * world
    }

    /**
     * Canvas translation (dx, dy) for drawing a stitch bitmap anchored at
     * ([anchorLat], [anchorLng]) so the current position ([lat], [lng])
     * lands on the anchor pixel. [scaleX]/[scaleY] convert bitmap pixels to
     * canvas pixels (canvas size / bitmap size). Stitch bitmaps are 1:1 with
     * world pixels at [zoom], so bitmap-pixel deltas are world-pixel deltas.
     */
    fun translation(
        anchorLat: Double,
        anchorLng: Double,
        lat: Double,
        lng: Double,
        zoom: Double,
        scaleX: Float,
        scaleY: Float
    ): Pair<Float, Float> {
        val dx = (worldX(anchorLng, zoom) - worldX(lng, zoom)).toFloat() * scaleX
        val dy = (worldY(anchorLat, zoom) - worldY(lat, zoom)).toFloat() * scaleY
        return dx to dy
    }
}
