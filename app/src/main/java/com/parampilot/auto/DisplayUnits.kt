package com.parampilot.auto

import kotlin.math.roundToInt

/**
 * The one distance-formatting rule every display uses (Param,
 * 2026-11-03: "use same logic in both displays"). Imperial throughout,
 * matching the phone UI: feet (rounded to the nearest 10) under
 * 528 ft, miles above — one decimal under 10 mi, whole miles beyond.
 *
 * Before this, the Android Auto template handed the head unit raw
 * METERS for any distance under one mile, so the car showed
 * "1505.9 m" while the phone showed "0.9 mi" for the same trip leg.
 */
object DisplayUnits {

    enum class TemplateUnit { FEET, MILES }

    /** A distance ready for the car-app Distance class: value + unit. */
    data class TemplateDistance(val value: Double, val unit: TemplateUnit)

    private const val FEET_PER_METER = Units.FEET_PER_METER
    private const val METERS_PER_MILE = Units.METERS_PER_MILE
    private const val FEET_CUTOFF = 528.0

    /** Banner text: "350 ft", "0.9 mi", "12 mi". Empty for no distance. */
    fun bannerDistance(meters: Double): String {
        if (meters <= 0.0) return ""
        val ft = meters * FEET_PER_METER
        if (ft < FEET_CUTOFF) return "${((ft / 10).roundToInt() * 10).coerceAtLeast(10)} ft"
        val mi = meters / METERS_PER_MILE
        return if (mi < 10) "${"%.1f".format(mi)} mi" else "${mi.roundToInt()} mi"
    }

    /**
     * The same rule expressed for the head unit's own renderer: feet
     * under the cutoff (same 10-ft rounding as the banner), miles
     * above. Never meters — the phone never shows meters, so the car
     * must not either.
     */
    fun templateDistance(meters: Double): TemplateDistance {
        val ft = meters * FEET_PER_METER
        return if (ft < FEET_CUTOFF) {
            TemplateDistance(((ft / 10).roundToInt() * 10).coerceAtLeast(10).toDouble(), TemplateUnit.FEET)
        } else {
            TemplateDistance(meters / METERS_PER_MILE, TemplateUnit.MILES)
        }
    }
}
