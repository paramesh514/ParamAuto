package com.parampilot.auto

/**
 * Detects a genuine mid-trip reroute from successive nav snapshots
 * (Feature 9): the comma recomputed the route for the destination that
 * is already active. A reroute is exactly: the same non-null destination
 * stays active, a previous routeKey existed, and the new routeKey
 * differs. The first publish for a destination, a destination change or
 * clear, and unchanged/unknown keys are never reroutes.
 */
class RouteChangeTracker {

    private var lastDestKey: String? = null
    private var lastRouteKey: String? = null

    /**
     * Feeds one snapshot's destination key (null when no destination is
     * active) and route key (null when the snapshot carries none).
     * Returns true only for a same-destination routeKey change.
     */
    fun onSnapshot(destKey: String?, routeKey: String?): Boolean {
        val rerouted = destKey != null && routeKey != null &&
            lastDestKey == destKey &&
            lastRouteKey != null && lastRouteKey != routeKey
        if (destKey == null) {
            // Destination cleared: the next destination starts fresh.
            lastDestKey = null
            lastRouteKey = null
        } else {
            lastDestKey = destKey
            // An absent key says nothing; keep the last known one.
            if (routeKey != null) lastRouteKey = routeKey
        }
        return rerouted
    }
}
