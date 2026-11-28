package com.parampilot.auto

/**
 * Destination-clear guard (Addendum 155): decides whether a
 * navigation observation that carries no destination may clear the
 * displayed destination — the fix for the drive-start flicker
 * (validated 2026-11-27: "no destination, then destination, at the
 * start of the drive").
 *
 * The defect: a status snapshot with no destination parses as
 * "nav ended" ([SgSnapshot.Parsed.navEnded] is true whenever the
 * snapshot carries no valid destination), and the only protection
 * was a short grace window anchored to the user's tap. The send is
 * fire-and-forget and the offline queue retries on its own cadence,
 * so the comma can take far longer than the grace to first publish
 * the destination — the first destination-less snapshot after the
 * grace cleared the display, and the snapshot that finally carried
 * the destination brought it back.
 *
 * The rule now distinguishes the two things an empty snapshot can
 * mean:
 *
 * - The comma EXPLICITLY ended the trip — its status is one of
 *   [SgSnapshot.ENDED_STATUSES] (arrived / cancelled /
 *   nodestination / …). That always clears immediately, confirmed
 *   or not, queued or not, inside the grace or not.
 * - The snapshot is BARE — no destination and no ended status.
 *   That is ambiguous: "not registered yet" looks exactly like
 *   "over". A bare snapshot therefore never clears a destination
 *   the comma has not yet confirmed in a snapshot, and never
 *   clears one still waiting in the offline queue. Once a
 *   destination HAS been snapshot-confirmed, a bare snapshot
 *   clears it only after [EMPTY_SNAPSHOTS_TO_CLEAR] consecutive
 *   bare observations (hysteresis against a single raced read),
 *   and never inside the pending grace, which send activity
 *   keeps refreshing (see NavStateManager.refreshDestinationPending).
 *
 * Layer 2: pure rules only. NavStateManager holds the state
 * (confirmation flag, consecutive-empty count, pending timestamp)
 * and evaluates this guard in one place
 * (NavStateManager.evaluateDestinationEnd); every snapshot writer
 * — CommaClient's poll publish, NavigationManager's snapshot
 * application, and the phone's telemetry-packet handler, whose
 * packets are built from the same status payload — asks it, so
 * the paths cannot drift apart.
 */
object DestinationClearGuard {

    enum class Decision { CLEAR, KEEP }

    /**
     * Consecutive bare destination-less observations required
     * before a snapshot-confirmed destination may be cleared.
     * Observations arrive at roughly the 1 s poll cadence, so
     * this is ~3 s of sustained emptiness — long enough to ride
     * out a raced read, short enough that a genuine comma-side
     * end (which normally also carries an explicit ended status
     * and clears immediately anyway) is not visibly delayed.
     */
    const val EMPTY_SNAPSHOTS_TO_CLEAR = 3

    /**
     * @param hasActiveDestination a destination is currently displayed.
     * @param confirmedBySnapshot a snapshot has carried the current
     *   destination at least once — the comma provably registered it.
     * @param isQueued the current destination is the one waiting in
     *   the offline queue.
     * @param explicitEnded the observation's status is one of
     *   [SgSnapshot.ENDED_STATUSES].
     * @param consecutiveEmptySnapshots bare destination-less
     *   observations in a row, INCLUDING the current one.
     * @param inPendingGrace the destination-pending grace (refreshed
     *   by selection and by every send attempt/success) is running.
     */
    fun decide(
        hasActiveDestination: Boolean,
        confirmedBySnapshot: Boolean,
        isQueued: Boolean,
        explicitEnded: Boolean,
        consecutiveEmptySnapshots: Int,
        inPendingGrace: Boolean
    ): Decision {
        // Nothing displayed: nothing to protect — the caller's
        // end-of-navigation cleanup proceeds as always.
        if (!hasActiveDestination) return Decision.CLEAR
        // The comma said the trip is over, in so many words.
        if (explicitEnded) return Decision.CLEAR
        // Bare emptiness from here: ambiguous, so protect.
        // Not yet registered on the comma (or never confirmed):
        // an empty snapshot is the expected state, not an end.
        if (!confirmedBySnapshot) return Decision.KEEP
        // Still waiting in the offline queue: the comma has not
        // accepted it yet, so its snapshots cannot end it.
        if (isQueued) return Decision.KEEP
        // Send activity is recent: registration may still be
        // landing — the grace is the backstop behind the flag.
        if (inPendingGrace) return Decision.KEEP
        // Confirmed and quiet: require a sustained run of empty
        // observations before believing the trip ended bare.
        return if (consecutiveEmptySnapshots >= EMPTY_SNAPSHOTS_TO_CLEAR) {
            Decision.CLEAR
        } else {
            Decision.KEEP
        }
    }
}
