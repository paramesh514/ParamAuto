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
        // Addendum 134: a successful install takes the comma down to
        // reboot, which can eat the POST's own success response — so
        // acceptance-by-response is unreliable for this action. An
        // attempt the comma acted on (accepted 2xx, or unconfirmed
        // because no answer came back) followed by the reboot cycle
        // (heartbeat down, then back) IS the completion evidence. An
        // attempt the comma answered with an error is a refusal: its
        // later reboots prove nothing, so it never completes this way.
        val actedOn = pending.acceptedAtMs > 0L || pending.attemptUnconfirmed
        if (pending.commaWentDown && actedOn) {
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
