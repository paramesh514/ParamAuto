package com.parampilot.auto

/**
 * Layer-2 decisions for the Mapbox-backed map displays (3-layer
 * architecture): which static style the Auto screen requests,
 * whether its frame is pitched (3D), the phone camera's tilt, and
 * the phone style's cache identity. The renderers (layer 3) ask
 * here and draw what comes back.
 *
 * 3D is ALWAYS on (Param, 2026-11-10: "3D enriched always in
 * phone and auto" — the 2026-11-04 toggle is retired): pitched
 * frames and tilted cameras whenever there is a heading. Traffic
 * remains a Phone Data toggle. Both need the phone's Mapbox key —
 * the comma's own tiles carry neither.
 */
object MapDisplayOptions {


    /** Static-frame pitch: pitched whenever there is a heading. */
    fun staticPitch(bearingDeg: Int): Int =
        if (bearingDeg != 0) 35 else 0

    /** Phone camera tilt while following: flat north-up, tilted
     *  heading-up. */
    fun phoneTilt(isNorthUp: Boolean): Double =
        if (!isNorthUp) 35.0 else 0.0

    /** Identity of a built phone Mapbox style, so flipping traffic
     *  rebuilds instead of serving the cached variant. */
    fun styleCacheKey(isNight: Boolean, traffic: Boolean): String =
        "night=$isNight,traffic=$traffic"
}
