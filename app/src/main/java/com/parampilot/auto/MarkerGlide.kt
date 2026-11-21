package com.parampilot.auto

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Layer-2 math for the AA vehicle marker glide (field fix,
 * 2026-11-06: "Car actual location and location on Auto is not
 * matching — map lagging behind me", on both transports, worse on
 * Bluetooth).
 *
 * The marker used to animate toward each new GPS fix over a FIXED
 * 1000 ms, then sit still until the next packet. Two consequences:
 * the displayed position always trailed reality by about one fix
 * interval, and on Bluetooth (fixes seconds apart) the marker
 * froze between 1 s glides — lag of the full packet gap.
 *
 * The glide now (a) lasts as long as the measured interval between
 * fixes (clamped), so the marker keeps moving for the whole gap,
 * and (b) aims at the position projected one glide ahead along the
 * fix's bearing at the current speed (capped), so the middle of
 * the glide sits near where the car actually is instead of where
 * it was. Pure math: [VehicleMarkerAnimator] supplies the clock
 * and the Android animator; everything decidable lives here.
 */
object MarkerGlide {

    const val MIN_GLIDE_MS = 600L
    const val MAX_GLIDE_MS = 4000L
    const val DEFAULT_GLIDE_MS = 1000L

    /** Furthest the glide target may run ahead of the fix itself. */
    const val MAX_PROJECTION_M = 75.0

    /** Below this speed a projection is noise; aim at the fix. */
    const val MIN_PROJECT_SPEED_MS = 1.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Glide duration for a fix that arrived [intervalMs] after the previous one. */
    fun durationMs(intervalMs: Long): Long =
        if (intervalMs <= 0L) DEFAULT_GLIDE_MS
        else intervalMs.coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)

    /** How far ahead of the fix the glide should aim, in meters. */
    fun projectionM(speedMs: Double, glideMs: Long): Double {
        if (speedMs < MIN_PROJECT_SPEED_MS || glideMs <= 0L) return 0.0
        return (speedMs * glideMs / 1000.0).coerceAtMost(MAX_PROJECTION_M)
    }

    /**
     * Destination point from ([lat], [lng]) travelling [distanceM]
     * meters along [bearingDeg] (spherical earth). Zero distance
     * returns the start point unchanged.
     */
    fun project(lat: Double, lng: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        if (distanceM <= 0.0) return lat to lng
        val d = distanceM / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lng2 = lng1 + atan2(
            sin(br) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }
}
