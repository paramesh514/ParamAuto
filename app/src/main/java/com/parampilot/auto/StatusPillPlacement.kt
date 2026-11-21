package com.parampilot.auto

/**
 * Layer-2 placement for the Android Auto status icon stack — the
 * transport chip and the phone-GPS feed chip (Param, 2026-11-06:
 * "Move Bluetooth and gps icons move left end. Instead of middle").
 *
 * The stack hangs off the LEFT edge of the surface, aligned with the
 * maneuver card. While navigating, the maneuver card owns the top
 * left (top 22, height 96 — or 140 with the lane strip), so the
 * stack starts below it; while idle, nothing owns the top left (the
 * arrival / drive-summary cards are centered), so the stack uses
 * the usual top band. The GPS chip stacks 36 px under the transport
 * chip (26 chip + 10 gap).
 */
object StatusPillPlacement {

    const val LEFT: Float = 28f
    const val TOP_BAND: Float = 120f
    const val MANEUVER_TOP: Float = 22f
    const val STACK_GAP: Float = 36f

    /** Maneuver card height for a nav state: taller with lanes. */
    fun maneuverCardHeight(hasLanes: Boolean): Float = if (hasLanes) 140f else 96f

    /** Top of the transport chip for the current display state. */
    fun stackTop(navigating: Boolean, maneuverCardHeight: Float): Float =
        if (navigating) MANEUVER_TOP + maneuverCardHeight + 10f else TOP_BAND

    /** Top of the GPS feed chip, stacked under the transport chip. */
    fun gpsTop(stackTop: Float): Float = stackTop + STACK_GAP
}
