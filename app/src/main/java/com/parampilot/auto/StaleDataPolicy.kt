package com.parampilot.auto

/**
 * Layer-2 staleness policy for the telemetry ribbon (Param's field
 * report, 2026-11-06: "Handle stale data issue with Bluetooth.
 * Looks like Bluetooth has limitations").
 *
 * He is right about the mechanism: a telemetry cycle over the BLE
 * bridge is several sequential bridged requests, so even a healthy
 * Bluetooth link publishes packets seconds apart — the flat 5 s
 * ribbon threshold cried wolf on a working link (his photo: "last
 * update 5s ago" while connected over Bluetooth). The heartbeat
 * identifies the transport (layer 1's one job), and this policy
 * grades staleness against what that transport can actually do:
 * 5 s for Local / Cloud, 15 s for Bluetooth (about three missed
 * bridge cycles). A genuinely dead link still trips the ribbon —
 * just on the transport's own clock.
 */
object StaleDataPolicy {

    const val DEFAULT_MS: Long = 5_000L
    const val BLUETOOTH_MS: Long = 15_000L

    fun staleAfterMs(isBluetooth: Boolean): Long =
        if (isBluetooth) BLUETOOTH_MS else DEFAULT_MS

    fun isStale(ageMs: Long?, isBluetooth: Boolean): Boolean =
        ageMs != null && ageMs >= staleAfterMs(isBluetooth)

    /** Freshness band for a packet age — the ONE banding every
     *  surface uses (audit finding 37: the phone HUD used to hardcode
     *  transport-blind 5 s/15 s bands, so it cried STALE on healthy
     *  Bluetooth links the AA ribbon correctly graded LIVE). */
    enum class Band { LIVE_NOW, LIVE, STALE, OFFLINE }

    fun band(ageMs: Long, isBluetooth: Boolean): Band {
        val staleAt = staleAfterMs(isBluetooth)
        return when {
            ageMs < 1_500L -> Band.LIVE_NOW
            ageMs < staleAt -> Band.LIVE
            ageMs < staleAt * 3 -> Band.STALE
            else -> Band.OFFLINE
        }
    }

    /** "12s" / "2m 5s" — the one age format (was duplicated verbatim
     *  in CarMapRenderer and HudOverlay). */
    fun ageText(ageMs: Long): String {
        val ageSec = (ageMs / 1000).coerceAtLeast(0L)
        return if (ageSec >= 60) "${ageSec / 60}m ${ageSec % 60}s" else "${ageSec}s"
    }
}
