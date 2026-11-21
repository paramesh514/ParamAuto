package com.parampilot.auto

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drive summary card (Day 3, backlog #4): what the car ACTUALLY drove on a
 * finished route, accumulated from the app's own GPS fixes while the trip
 * ran — distance driven, trip duration, top speed, and average speed.
 *
 * This is deliberately different from the arrival card's comma totals
 * (remaining duration/distance the device reported): those say what the
 * route was; this says what driving happened. Every number comes from a
 * real fix; a quantity with no backing data renders as "—", never invented.
 */
data class DriveSummary(
    val destinationName: String,
    val durationMs: Long,
    val distanceMeters: Double,
    val topSpeedMs: Double
) {
    /** Average speed over the whole trip time (distance / elapsed). */
    val avgSpeedMs: Double
        get() = if (durationMs > 0) distanceMeters / (durationMs / 1000.0) else 0.0

    /** "45 s", "12 min", "1 h 15 min", "1 h", or "—" when there was no trip time. */
    fun durationText(): String = when {
        durationMs <= 0 -> "—"
        durationMs < 60_000 -> "${durationMs / 1000} s"
        durationMs < 3_600_000 -> "${durationMs / 60_000} min"
        durationMs % 3_600_000 == 0L -> "${durationMs / 3_600_000} h"
        else -> "${durationMs / 3_600_000} h ${(durationMs % 3_600_000) / 60_000} min"
    }

    /** "3.4 mi", or "—" when nothing was driven (same %.1f style as the arrival card). */
    fun distanceText(): String {
        if (distanceMeters <= 0.0) return "—"
        return String.format(Locale.US, "%.1f mi", distanceMeters / Units.METERS_PER_MILE)
    }

    /** "62 mph", or "—" when no speed was ever reported. */
    fun topSpeedText(): String =
        if (topSpeedMs > 0.0) "${(topSpeedMs * Units.MS_TO_MPH).toInt()} mph" else "—"

    /** "31 mph", or "—" when distance or time is missing. */
    fun avgSpeedText(): String =
        if (avgSpeedMs > 0.0) "${(avgSpeedMs * Units.MS_TO_MPH).toInt()} mph" else "—"

    /** One card line: distance, duration, top speed, average speed. */
    fun detailText(): String =
        "${distanceText()} · ${durationText()} · top ${topSpeedText()} · avg ${avgSpeedText()}"
}

/**
 * Accumulates a [DriveSummary] from successive GPS fixes. Pure logic — no
 * Android types, no clock reads — so it is unit-testable on the JVM.
 *
 * Honesty guards (a fix that would corrupt the totals is dropped, not
 * counted): fixes are only accepted while a trip is tracking; a segment
 * is only counted when the gap since the previous fix is at most 60 s
 * (a longer gap means the feed stopped — sleep, reconnect — not driving)
 * and at most 2000 m (a GPS jump, not a road segment).
 */
class DriveSummaryTracker {

    companion object {
        const val MAX_SEGMENT_GAP_MS = 60_000L
        const val MAX_SEGMENT_METERS = 2000.0
        const val MIN_DRIVEN_METERS = 50.0
    }

    private var tracking: Boolean = false
    private var destinationName: String = ""
    private var startMs: Long = 0L
    private var lastLat: Double = 0.0
    private var lastLng: Double = 0.0
    private var lastMs: Long = 0L
    private var hasLast: Boolean = false
    private var distanceMeters: Double = 0.0
    private var topSpeedMs: Double = 0.0

    val isTracking: Boolean get() = synchronized(this) { tracking }

    /** Starts a trip, discarding any previous one. */
    fun start(name: String, nowMs: Long) {
        synchronized(this) {
            tracking = true
            destinationName = name
            startMs = nowMs
            lastMs = nowMs
            hasLast = false
            distanceMeters = 0.0
            topSpeedMs = 0.0
        }
    }

    /**
     * Starts only when nothing is tracking, or when the tracked destination
     * changed — a re-confirm of the SAME destination mid-trip must not
     * reset the totals already accumulated.
     */
    fun startIfNew(name: String, nowMs: Long) {
        synchronized(this) {
            if (!tracking || destinationName != name) start(name, nowMs)
        }
    }

    fun onFix(latitude: Double, longitude: Double, speedMs: Double, nowMs: Long) {
        synchronized(this) {
            if (!tracking) return
            if (speedMs > topSpeedMs) topSpeedMs = speedMs
            if (hasLast) {
                val gapMs = nowMs - lastMs
                if (gapMs in 0..MAX_SEGMENT_GAP_MS) {
                    val segment = GuidanceMath.haversineM(lastLat, lastLng, latitude, longitude)
                    if (segment <= MAX_SEGMENT_METERS) distanceMeters += segment
                }
            }
            lastLat = latitude
            lastLng = longitude
            lastMs = nowMs
            hasLast = true
        }
    }

    /**
     * Ends the trip. Returns null when nothing meaningful was driven
     * (under [MIN_DRIVEN_METERS]) — ending a route that never moved shows
     * no card instead of a "0 mi · 0 min" card.
     */
    fun finish(nowMs: Long): DriveSummary? {
        synchronized(this) {
            if (!tracking) return null
            tracking = false
            hasLast = false
            if (distanceMeters < MIN_DRIVEN_METERS) return null
            return DriveSummary(
                destinationName = destinationName,
                durationMs = (nowMs - startMs).coerceAtLeast(0L),
                distanceMeters = distanceMeters,
                topSpeedMs = topSpeedMs
            )
        }
    }

    fun reset() {
        synchronized(this) {
            tracking = false
            hasLast = false
            distanceMeters = 0.0
            topSpeedMs = 0.0
        }
    }


}

/**
 * Android Auto presentation rules for a finished drive summary (Day 5):
 * the car screen shows what was actually driven for the same short
 * window as the arrival card. Pure logic, so the window and the
 * arrival-vs-standalone choice are unit-testable without a surface.
 *
 * - Arrival fresh + summary fresh: the summary rides as a "Driven:"
 *   line on the arrival card (same as the phone's arrival card).
 * - Summary fresh, no fresh arrival (e.g. user End Route): a standalone
 *   "Drive summary" card on the surface.
 * - Anything stale: nothing — a finished trip must not linger on the
 *   head unit after the window closes.
 */
object AaDriveSummary {
    const val WINDOW_MS = 20_000L

    fun isFresh(ageMs: Long): Boolean = ageMs >= 0 && ageMs < WINDOW_MS

    fun drivenLine(summary: DriveSummary?): String? =
        summary?.let { "Driven: ${it.detailText()}" }

    fun showStandalone(arrivalAgeMs: Long?, driveAgeMs: Long?): Boolean {
        if (driveAgeMs == null || !isFresh(driveAgeMs)) return false
        return arrivalAgeMs == null || !isFresh(arrivalAgeMs)
    }
}
