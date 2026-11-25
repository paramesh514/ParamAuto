package com.parampilot.auto

/**
 * Layer-2 gate for phone-side guidance (Addendum 145, Param
 * 2026-11-25: "It should work as navigation app" when the comma is
 * not connected).
 *
 * While a destination is QUEUED — the comma has not accepted it —
 * the phone is the only navigator: MainActivity feeds phone GPS
 * fixes through [NavStateManager.phoneManeuver] and renders the
 * turn banner + ETA exactly like comma guidance. The moment the
 * comma accepts (queue flushed) or is itself guiding with a fresh
 * packet, phone guidance stands down and the comma's instructions
 * rule again.
 *
 * Pure: callers gather the state, this decides.
 */
object PhoneGuidance {

    /** A comma packet counts as "the comma is guiding" only while
     *  it is fresh — the same 6 s staleness bound the car screen
     *  uses for packet freshness (NavScreen STALE_MS). */
    const val COMMA_FRESH_MS = 6000L

    /** An "arrive" maneuver only ends the trip when the destination
     *  is actually close: the arrive STEP can be the next maneuver
     *  while it is still hundreds of meters away. */
    const val ARRIVAL_DISTANCE_M = 75.0

    fun commaIsGuiding(packetAtMs: Long, navValid: Boolean, nowMs: Long): Boolean =
        navValid && packetAtMs > 0L && (nowMs - packetAtMs) < COMMA_FRESH_MS

    fun shouldGuide(queued: Boolean, hasDestination: Boolean, commaGuiding: Boolean): Boolean =
        queued && hasDestination && !commaGuiding

    fun isArrival(maneuver: NavManeuver): Boolean =
        maneuver.valid &&
            (maneuver.type == "arrive" || maneuver.modifier == "arrive") &&
            maneuver.distanceM <= ARRIVAL_DISTANCE_M
}
