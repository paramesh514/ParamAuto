package com.parampilot.auto

/**
 * End Route confidence (backlog #3): the nav header shows the
 * comma-confirmed state of an ended route instead of going silently
 * blank — so a stuck route is visible at a glance.
 *
 * The gap it closes: ending a route used to be a toast and a vanishing
 * header. While the clear was in flight nothing showed the tap had
 * landed; after it the header just disappeared; and when the comma
 * kept the destination anyway (the clear action answered OK but the
 * device kept guiding — the ghost-route failure class), nothing on
 * either display said so. Now the header carries the state the comma
 * itself confirmed:
 *
 * - ENDING while the clear action is in flight;
 * - ENDED once the comma confirms the clear (action response, and a
 *   snapshot with no destination when one arrives — either confirms);
 * - STUCK when a snapshot arriving after the grace window still
 *   carries the cleared destination, i.e. the end didn't stick.
 *
 * Layer 2: pure rules only. NavStateManager holds the state fields;
 * MainActivity / NavScreen drive the transitions; TurnBannerView and
 * CarMapRenderer render what [isVisible] + [title]/[subtitle] say.
 * No state is ever invented: ENDED requires the comma's own
 * confirmation, and STUCK requires the comma's own snapshot.
 */
object EndRouteConfidence {

    /** The comma-confirmed end states the nav header can show. */
    enum class State {
        /** No end in progress; the header shows normal navigation. */
        IDLE,
        /** The user tapped End Route; the clear action is in flight. */
        ENDING,
        /** The comma confirmed the clear. */
        ENDED,
        /** After a confirmed end, the comma's snapshots still carry
         *  the cleared destination — the route is stuck on the device. */
        STUCK
    }

    /**
     * How long "Route ended ✓" stays in the nav header. Same 20 s
     * window as the arrival and drive-summary cards, so the
     * confirmation and the trip numbers retire together.
     */
    const val ENDED_SHOW_MS = 20_000L

    /**
     * Snapshots arriving this soon after a confirmed end may predate
     * the clear (the 1 s heartbeat has not caught up yet); only
     * older ones prove the route stuck.
     */
    const val STUCK_GRACE_MS = 5_000L

    /** The user tapped End Route. */
    fun onEndRequested(): State = State.ENDING

    /** The comma confirmed the clear action (also resolves STUCK on a retry). */
    fun onActionConfirmed(state: State): State =
        if (state == State.ENDING || state == State.STUCK) State.ENDED else state

    /** The clear action failed: the route is still live, so the header goes back to normal navigation. */
    fun onEndFailed(state: State): State =
        if (state == State.ENDING) State.IDLE else state

    /**
     * A snapshot with no destination confirms the end — from ENDING
     * (the clear just landed) or STUCK (the device finally let go).
     */
    fun onSnapshotNoDestination(state: State): State =
        if (state == State.ENDING || state == State.STUCK) State.ENDED else state

    /**
     * A snapshot still carrying the cleared destination, after the
     * grace window, proves the end didn't stick.
     */
    fun onSnapshotStillHasDestination(state: State, endedAtMs: Long, nowMs: Long): State =
        if (state == State.ENDED && nowMs - endedAtMs >= STUCK_GRACE_MS) State.STUCK else state

    /** A destination the app accepts supersedes any end state. */
    fun onNewDestination(): State = State.IDLE


    /**
     * True while the header should show this end state. ENDING shows
     * even while the destination is still up (the clear is in flight
     * and the route is still live); the terminal states never show
     * over a live destination — a new trip always wins over a stale
     * end card.
     */
    fun isVisible(state: State, hasActiveDestination: Boolean, endedAtMs: Long, nowMs: Long): Boolean =
        when (state) {
            State.IDLE -> false
            State.ENDING -> true
            State.ENDED -> !hasActiveDestination && nowMs - endedAtMs in 0 until ENDED_SHOW_MS
            State.STUCK -> !hasActiveDestination
        }

    /** Header title line for the state; null when the header shows normal navigation. */
    fun title(state: State): String? = when (state) {
        State.IDLE -> null
        State.ENDING -> "Ending route…"
        State.ENDED -> "Route ended ✓"
        State.STUCK -> "⚠ Comma still navigating"
    }

    /** Header subtitle for the state: the destination name, plus the retry hint while stuck. */
    fun subtitle(state: State, destName: String?): String? {
        val name = destName?.takeIf { it.isNotBlank() } ?: return null
        return when (state) {
            State.IDLE -> null
            State.ENDING -> name
            State.ENDED -> name
            State.STUCK -> "$name — tap to retry"
        }
    }

    /** Android Auto surface card detail line for the state; null when the card has no detail line. */
    fun cardDetail(state: State): String? = when (state) {
        State.ENDED -> "Confirmed by the comma"
        State.STUCK -> "End Route didn't stick — retry on the phone"
        else -> null
    }
}
