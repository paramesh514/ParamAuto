package com.parampilot.auto

import kotlin.math.ln

/**
 * Layer-2 pinch-zoom step for the car map (Addendum 154,
 * diagnosis D3).
 *
 * The pre-154 handler mapped ANY pinch to a flat ±0.5 zoom step
 * per event regardless of how far the fingers actually moved,
 * so pinch felt steppy and unresponsive next to the phone. The
 * step is now proportional to the gesture: the scale factor in
 * octaves (a doubling pinch is one full zoom level), clamped
 * to ±[MAX_STEP] per event so a fast fling cannot jump the map
 * several levels in one event.
 */
object PinchZoom {

    const val MAX_STEP = 0.5

    fun step(scaleFactor: Float): Double {
        if (scaleFactor <= 0f) return 0.0
        val raw = ln(scaleFactor.toDouble()) / ln(2.0)
        return raw.coerceIn(-MAX_STEP, MAX_STEP)
    }
}
