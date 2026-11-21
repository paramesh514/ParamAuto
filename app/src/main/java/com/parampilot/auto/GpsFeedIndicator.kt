package com.parampilot.auto

/**
 * The "phone is sending location to the comma" status indicator
 * (Param, 2026-11-04): shown on both displays whenever
 * [PhoneGpsFeeder] has an active session. On the Android Auto
 * surface the icon pulses — this object owns the pulse timing so
 * the renderer just asks which phase a timestamp falls in.
 *
 * Pure: no Android types, no state.
 */
object GpsFeedIndicator {

    /** Full bright+dim cycle of the pulse. */
    const val PERIOD_MS = 1200L

    /** Whether the indicator exists at all: only while a feed session is active. */
    fun shouldShow(isFeeding: Boolean): Boolean = isFeeding

    /** True during the bright half of the pulse at [nowMs]. */
    fun isBrightPhase(nowMs: Long): Boolean {
        val phase = ((nowMs % PERIOD_MS) + PERIOD_MS) % PERIOD_MS
        return phase < PERIOD_MS / 2
    }

    /** Icon alpha for the phase: bright 255, dim 110. */
    fun alpha(nowMs: Long): Int = if (isBrightPhase(nowMs)) 255 else 110
}
