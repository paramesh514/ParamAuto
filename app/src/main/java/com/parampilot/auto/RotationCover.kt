package com.parampilot.auto

import kotlin.math.cos
import kotlin.math.sin

/**
 * Layer-2 geometry for the Auto map's heading-up rotation: how far
 * the stitch bitmap must be scaled (around the vehicle anchor) so
 * rotating it never exposes the canvas. The black wedges in
 * Param's 2026-11-04 photo came from a fixed 1.25x scale that runs
 * out at wider bearings. Pure math; the renderer applies the
 * result to its canvas transform.
 */
object RotationCover {

    const val MAX_SCALE = 1.8f

    /**
     * Smallest scale s in [1, MAX_SCALE] such that every surface
     * corner, mapped through the inverse render transform
     * q = anchor + R(-rotation) * (p - anchor) / s (Android canvas
     * convention), lands inside the bitmap's destination rect
     * [dstLeft, dstRight] x [dstTop, dstBottom].
     */
    fun coverScale(
        w: Float, h: Float,
        anchorX: Float, anchorY: Float,
        rotationDeg: Float,
        dstLeft: Float, dstTop: Float, dstRight: Float, dstBottom: Float
    ): Float {
        if (rotationDeg == 0f) return 1f
        val rad = Math.toRadians(rotationDeg.toDouble())
        val cosR = cos(rad)
        val sinR = sin(rad)
        var s = 1.0
        val corners = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(w, 0f),
            floatArrayOf(0f, h), floatArrayOf(w, h)
        )
        for (corner in corners) {
            val vx = corner[0] - anchorX
            val vy = corner[1] - anchorY
            val rx = cosR * vx + sinR * vy
            val ry = -sinR * vx + cosR * vy
            // Need anchor + r/s inside the destination rect.
            if (rx > 0) {
                val room = dstRight - anchorX
                if (room <= 0) return MAX_SCALE
                s = maxOf(s, rx / room)
            }
            if (rx < 0) {
                val room = anchorX - dstLeft
                if (room <= 0) return MAX_SCALE
                s = maxOf(s, -rx / room)
            }
            if (ry > 0) {
                val room = dstBottom - anchorY
                if (room <= 0) return MAX_SCALE
                s = maxOf(s, ry / room)
            }
            if (ry < 0) {
                val room = anchorY - dstTop
                if (room <= 0) return MAX_SCALE
                s = maxOf(s, -ry / room)
            }
        }
        return s.toFloat().coerceIn(1f, MAX_SCALE)
    }
}
