package com.parampilot.auto

import org.json.JSONObject

/**
 * Layer 2: THE parse of a speed limit out of an SG status/plots
 * payload. StarPilot's payload carries no speed limit today (proven
 * from its source, 2026-11-04) — these are defensive reads for if it
 * ever does — but they existed in TWO copies with OPPOSITE unit
 * heuristics (audit finding 2, 2026-11-06): TelemetryPacket.fromSg
 * treated a value > 50 as mph, NavigationManager's snapshot writer
 * treated a value < 45 as m/s, so a value of 45–50 was classified
 * differently depending on which writer ran last.
 *
 * The one rule now: a bare `speedLimit` / `speed_limit` / `maxspeed`
 * value **> 50 is mph; anything at or below is m/s** (the primary
 * packet path's original rule — a real m/s limit can't exceed ~40,
 * since 90 mph ≈ 40 m/s). A key explicitly named `speedLimitMps`
 * is always m/s. Returns m/s, 0.0 when the payload carries no limit.
 */
object SpeedLimitParse {

    /** Limit from the status payload (status keys, then instruction
     *  keys), in m/s; 0.0 when absent. */
    fun fromStatus(navStatus: JSONObject): Double {
        val s1 = navStatus.optDouble("speedLimit", Double.NaN).takeUnless { it.isNaN() }
            ?: navStatus.optDouble("speed_limit", Double.NaN).takeUnless { it.isNaN() }
            ?: navStatus.optDouble("maxspeed", Double.NaN).takeUnless { it.isNaN() }
            ?: navStatus.optJSONObject("instruction")?.let { instr ->
                instr.optDouble("speedLimit", Double.NaN).takeUnless { it.isNaN() }
                    ?: instr.optDouble("speed_limit", Double.NaN).takeUnless { it.isNaN() }
                    ?: instr.optDouble("maxspeed", Double.NaN).takeUnless { it.isNaN() }
            }
        return if (s1 != null && s1 > 0.0) bareToMs(s1) else 0.0
    }

    /** Limit from the plots payload, in m/s; 0.0 when absent.
     *  `speedLimitMps` wins and is always m/s. */
    fun fromPlots(plots: JSONObject?): Double {
        if (plots == null) return 0.0
        val mps = plots.optDouble("speedLimitMps", Double.NaN).takeUnless { it.isNaN() }
        if (mps != null && mps > 0.0) return mps
        val bare = plots.optDouble("speedLimit", Double.NaN).takeUnless { it.isNaN() }
            ?: plots.optDouble("speed_limit", Double.NaN).takeUnless { it.isNaN() }
        return if (bare != null && bare > 0.0) bareToMs(bare) else 0.0
    }

    /** Status-then-plots, in m/s; 0.0 when neither carries a limit. */
    fun payloadLimitMs(navStatus: JSONObject, plots: JSONObject?): Double {
        val fromStatus = fromStatus(navStatus)
        return if (fromStatus > 0.0) fromStatus else fromPlots(plots)
    }

    /** The status payload's limit as whole mph (for the speedLimitMph
     *  mirror), or null when the payload carries none. */
    fun payloadLimitMph(navStatus: JSONObject): Int? {
        val ms = fromStatus(navStatus)
        return if (ms > 0.0) (ms * Units.MS_TO_MPH).toInt().takeIf { it > 0 } else null
    }

    /** The one unit rule for a bare limit value. */
    fun bareToMs(v: Double): Double = if (v > 50.0) v / Units.MS_TO_MPH else v
}
