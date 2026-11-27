package com.parampilot.auto

/**
 * Layer-2 mapper for the VISUAL settings surface (Addendum 148,
 * Param 2026-11-26: "Turn comma settings into visual format"):
 * reads a settings page's rows and distills the handful of values
 * the live road scene draws — following gap, lane-centering
 * strength, curve bend, a speed sign, and whether anything is on.
 * Pure and page-agnostic: recognition is by row-label keywords plus
 * the row's own numeric bounds, so every Galaxy page gets a scene
 * that reflects its real values, and pages with nothing mappable
 * get the plain road. Nothing here invents a value: an unmapped
 * element stays null and the scene simply doesn't draw it.
 */
object SettingsScene {

    data class Scene(
        /** Following time gap in seconds (lead car distance). */
        val followGapSec: Double? = null,
        /** Lane-centering strength, normalized 0..1 over its bounds. */
        val laneStrength01: Double? = null,
        /** Curve aggressiveness, normalized 0..1 over its bounds. */
        val curve01: Double? = null,
        /** Speed-sign text (value + unit) from a limit/offset row. */
        val speedSign: String? = null,
        /** True when any switch row on the page is On. */
        val anyOn: Boolean = false
    )

    fun from(rows: List<GalaxySettings.Row>): Scene {
        var gap: Double? = null
        var strength: Double? = null
        var curve: Double? = null
        var sign: String? = null
        var anyOn = false
        for (row in rows) {
            val label = row.label.lowercase()
            if (GalaxySettings.control(row) == GalaxySettings.Control.SWITCH &&
                row.value == "On"
            ) {
                anyOn = true
            }
            val value = row.value.toDoubleOrNull() ?: continue
            val bounds = GalaxySettings.numericBounds(row) ?: continue
            val normalized = ((value - bounds.first) / (bounds.second - bounds.first))
                .coerceIn(0.0, 1.0)
            when {
                gap == null && label.contains("follow") ->
                    gap = value
                strength == null && label.contains("lane") &&
                    (label.contains("strength") || label.contains("center")) ->
                    strength = normalized
                curve == null && label.contains("curve") ->
                    curve = normalized
                sign == null && (label.contains("speed limit") ||
                    label.contains("offset")) ->
                    sign = GalaxySettings.valueWithUnit(row)
            }
        }
        return Scene(gap, strength, curve, sign, anyOn)
    }
}
