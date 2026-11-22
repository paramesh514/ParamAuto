package com.parampilot.auto

/**
 * A user-confirmed comma software install that has not completed yet
 * (Addendum 126, Param 2026-11-21: "Once update clicked switch to
 * updating and keep updating untill comma is available. Updating
 * should survive app restart too."). Recorded the moment Install is
 * confirmed, so the request outlives the dialog, the connection, and
 * the app process.
 *
 * While the intent is pending the app is in quiet mode (Param,
 * 2026-11-21: "When updating state active don't send any requests
 * other then connectivity checks") — the only comma traffic is the
 * 1 s heartbeat. The engine therefore runs off the heartbeat alone:
 * it retries the install POST while the comma answers and the car is
 * parked, and reads completion from the reboot cycle itself (the
 * comma accepted the install, went down, and came back). No status
 * reads are needed — or allowed — while updating.
 *
 * Persistence lives in SoftwareUpdateIntentStore; this file is the
 * pure decision logic.
 */
data class SoftwareUpdatePending(
    /** Installed commit (short form) at the moment the user confirmed. */
    val baseCommit: String,
    /** Branch the install action carries. */
    val branch: String,
    val requestedAtMs: Long,
    /** Last time an install POST was actually sent; 0 = never. */
    val lastAttemptMs: Long,
    /** When the comma accepted an install POST (2xx); 0 = not yet. */
    val acceptedAtMs: Long = 0L,
    /** True once the heartbeat has been seen down after an acted-on attempt. */
    val commaWentDown: Boolean = false,
    /**
     * True when the latest install POST got NO answer back (transport
     * failure or ambiguous outcome — Addendum 134): the comma may have
     * executed it and gone down to reboot before the response arrived.
     * An install the comma answered with an error status is a refusal,
     * NOT unconfirmed — it retries on the normal cadence.
     */
    val attemptUnconfirmed: Boolean = false
)

/**
 * Layer-2 decision for the pending-install engine, evaluated once per
 * heartbeat while an intent is pending. Pure — CommaClient executes
 * the decision.
 */
object SoftwareUpdatePlan {

    enum class Decision { IDLE, WAITING, ATTEMPT, COMPLETED }

    /** Two install POSTs are never closer than this. */
    const val ATTEMPT_INTERVAL_MS = 240_000L

    /**
     * An accepted install that never takes the comma down (no reboot
     * followed) still counts as finished after this long.
     */
    const val NO_REBOOT_WINDOW_MS = 900_000L

    fun evaluate(
        pending: SoftwareUpdatePending?,
        heartbeatUp: Boolean,
        parked: Boolean,
        nowMs: Long
    ): Decision {
        if (pending == null) return Decision.IDLE
        // Param's rule (2026-11-22, Addendum 136): "Once comma goes
        // into updating look for comma restart, once restart clear
        // updating." The restart IS the completion signal — a
        // successful install reboots the comma before the POST's
        // response survives (Addendum 134), so completion never
        // depends on the install response at all: any pending intent
        // whose comma has been seen down completes when the heartbeat
        // is back. The windows below remain only as backstops for a
        // restart the app never observed (app closed throughout).
        if (pending.commaWentDown) {
            return if (heartbeatUp) Decision.COMPLETED else Decision.WAITING
        }
        if (pending.acceptedAtMs > 0L) {
            if (!heartbeatUp) return Decision.WAITING
            return if (nowMs - pending.acceptedAtMs >= NO_REBOOT_WINDOW_MS) {
                Decision.COMPLETED
            } else {
                Decision.WAITING
            }
        }
        if (pending.attemptUnconfirmed && pending.lastAttemptMs > 0L) {
            if (!heartbeatUp) return Decision.WAITING
            return if (nowMs - pending.lastAttemptMs >= NO_REBOOT_WINDOW_MS) {
                Decision.COMPLETED
            } else {
                Decision.WAITING
            }
        }
        if (!heartbeatUp) return Decision.WAITING
        if (!parked) return Decision.WAITING
        return if (nowMs - pending.lastAttemptMs >= ATTEMPT_INTERVAL_MS) {
            Decision.ATTEMPT
        } else {
            Decision.WAITING
        }
    }
}
