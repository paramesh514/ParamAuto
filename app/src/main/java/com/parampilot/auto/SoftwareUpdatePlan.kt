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
 * comma accepted the install, went down, and came back).
 *
 * Addendum 160: the reboot cycle alone proved not to be enough — a
 * restart the app never observed (and an install the comma performed
 * after refusing the app's duplicate POST) left the state stuck. So
 * before any (re-)POST, on the same attempt cadence, the engine now
 * also reconciles the intent against one software status read (see
 * [reconcile]); that read is part of the install workflow itself and
 * is the only addition to the heartbeat traffic.
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
    val attemptUnconfirmed: Boolean = false,
    /**
     * True when the comma answered an install POST by saying its
     * updater is already busy / a request is already pending
     * (Addendum 160): the work is happening server-side, so the engine
     * must not re-POST into another refusal — it waits and reconciles
     * against the software status instead (see [SoftwareUpdatePlan.reconcile]).
     */
    val serverBusy: Boolean = false
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

    // ---- Addendum 160: status reconciliation + attempt outcomes ----
    //
    // The heartbeat-only decisions above cannot see two things: a
    // restart the app never observed (the engine itself only runs
    // after a successful nav-status fetch, so a reboot — which takes
    // nav status down with the heartbeat — is invisible to the
    // commaWentDown latch), and an install the comma carried out
    // after refusing the app's duplicate POST with a 409. The engine
    // therefore reconciles against GET /api/software/status before
    // any (re-)POST: one status read per attempt cadence, part of
    // the install workflow itself. Everything below is the pure
    // decision; CommaClient executes it.

    /** The facts of one successful software status read that the reconciliation needs. */
    data class StatusFacts(
        /** Installed commit (short form) the status reports; "" when unreadable. */
        val installedCommit: String,
        /** The comma's updater is idle (not checking/downloading/installing). */
        val updaterIdle: Boolean,
        /** The comma's updater has a request of its own still pending. */
        val requestPending: Boolean,
        /** A downloaded update is still finalized and awaiting install. */
        val finalizedReady: Boolean
    )

    enum class Reconcile {
        /** The installed commit moved past the intent's base: the install landed. */
        COMPLETE_COMMIT_CHANGED,

        /** Updater idle, nothing pending, nothing left to install: the intent has no job left. */
        COMPLETE_NOTHING_LEFT,

        /** The comma is still working (updater busy or its own request pending): wait, don't POST. */
        WAIT,

        /** A finalized update is still waiting and the updater is free: the intent may POST. */
        PROCEED
    }

    fun reconcile(pending: SoftwareUpdatePending, facts: StatusFacts): Reconcile {
        if (facts.installedCommit.isNotEmpty() && pending.baseCommit.isNotEmpty() &&
            facts.installedCommit != pending.baseCommit
        ) {
            return Reconcile.COMPLETE_COMMIT_CHANGED
        }
        if (facts.requestPending || !facts.updaterIdle) return Reconcile.WAIT
        if (facts.finalizedReady) return Reconcile.PROCEED
        return Reconcile.COMPLETE_NOTHING_LEFT
    }

    /** The server reports an idle updater as "idle" — or as no state at all. */
    fun isIdleUpdaterState(state: String): Boolean =
        state.isBlank() || state.equals("idle", ignoreCase = true)

    enum class AttemptOutcome { ACCEPTED, UNCONFIRMED, BUSY_REFUSAL, REFUSED }

    /**
     * Classifies an install POST's result. [code] uses SgGalaxyClient's
     * result contract: 2xx answered success, an HTTP status is an
     * answered refusal, -1 is no answer at all and -3 is the BLE
     * ambiguous code (the POST may have executed). A refusal whose
     * [reason] (the server's own error text) says the updater is busy
     * or a request is pending is a BUSY_REFUSAL: the install is
     * already happening server-side, so the engine waits on status
     * instead of re-arming the same POST.
     */
    fun classifyAttempt(code: Int, reason: String?): AttemptOutcome = when {
        code in 200..299 -> AttemptOutcome.ACCEPTED
        code == -1 || code == -3 -> AttemptOutcome.UNCONFIRMED
        reason != null && (reason.contains("busy", ignoreCase = true) ||
            reason.contains("pending", ignoreCase = true)) ->
            AttemptOutcome.BUSY_REFUSAL
        else -> AttemptOutcome.REFUSED
    }
}
