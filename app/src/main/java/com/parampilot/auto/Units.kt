package com.parampilot.auto

/**
 * Layer 2: THE unit-conversion constants and helpers. Before the
 * 2026-11-06 audit fix, the same constants lived at 25+ sites in
 * three different precisions (2.23694 vs 2.2369362920544, 1609.34
 * vs 1609.344) — every new call site picked whichever copy it was
 * written next to. All conversions now come from here.
 */
object Units {

    /** m/s → mph, exact: 3600 / 1609.344. */
    const val MS_TO_MPH = 2.2369362920544

    /** mph → m/s. */
    const val MPH_TO_MS = 0.44704

    /** meters → miles, exact: 1 / 1609.344. */
    const val METERS_TO_MILES = 0.000621371192237334

    /** Exact international mile in meters. */
    const val METERS_PER_MILE = 1609.344

    const val FEET_PER_METER = 3.28084


    /** Whole minutes for a duration in seconds: 0 stays 0 (the device
     *  provided none), any positive duration rounds UP to at least 1 —
     *  the RouteOption/ETA rule that was copy-pasted at 4+ sites. */
    fun durationMin(durationSec: Double): Int =
        if (durationSec > 0.0) maxOf(1, (durationSec / 60.0).toInt()) else 0

    /** Miles with one decimal, truncated (not rounded) — the
     *  RouteOption distance rule that was copy-pasted at 4 sites. */
    fun milesTenths(distanceM: Double): Double =
        ((distanceM * METERS_TO_MILES) * 10.0).toInt() / 10.0
}
