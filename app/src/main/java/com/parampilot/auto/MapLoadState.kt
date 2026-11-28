package com.parampilot.auto

/**
 * Layer-2 decision: what the car map's no-frame state IS, so the
 * renderer can say it in words (Addendum 153, diagnosis B2/B6).
 *
 * Before this, a car map with no frame drew a bare perspective
 * grid — no text, no retry — whether it was still loading, had
 * failed outright, or was simply waiting for the first GPS fix.
 * "Loading" and "dead" looked identical, which is exactly what
 * Param reported as "maps not properly loading".
 */
object MapLoadState {

    enum class Kind { READY, LOADING, UNAVAILABLE, WAITING_FOR_GPS }

    fun kind(
        hasFrame: Boolean,
        fetchInFlight: Boolean,
        planFailed: Boolean,
        hasPosition: Boolean
    ): Kind = when {
        hasFrame -> Kind.READY
        // No position: fetching would load tiles for a wrong,
        // invented place (the old hard-coded Phoenix fallback) —
        // the state is "waiting", not "loading".
        !hasPosition -> Kind.WAITING_FOR_GPS
        fetchInFlight -> Kind.LOADING
        planFailed -> Kind.UNAVAILABLE
        // Nothing tried yet this session: a fetch is about to run.
        else -> Kind.LOADING
    }

    /** The line drawn on the grid state; null when a real frame
     *  is on screen and no words are needed. */
    fun statusText(kind: Kind): String? = when (kind) {
        Kind.LOADING -> "Loading map…"
        Kind.UNAVAILABLE -> "Map unavailable — check connection"
        Kind.WAITING_FOR_GPS -> "Waiting for GPS…"
        Kind.READY -> null
    }

    /** Only the failed state offers the Retry action. */
    fun offersRetry(kind: Kind): Boolean = kind == Kind.UNAVAILABLE
}
