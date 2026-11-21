package com.parampilot.auto

/**
 * Layer 2: exponential backoff for connection-RECOVERY attempts
 * (audit finding 6). The poll's failure path used to re-fire the
 * full recovery machinery — AUTO failover, and in LOCAL mode the
 * startup sequence with its 254-probe subnet sweep — every few
 * seconds, unbounded, for as long as the comma was unreachable
 * (e.g. asleep for its 12 h shutdown timer). Recovery attempts now
 * space out: 5 s, 15 s, 30 s, then every 60 s until one succeeds.
 * The ordinary 1 s poll itself never backs off — only the expensive
 * recovery actions do.
 */
object FailureBackoff {

    fun delayMs(failedAttempts: Int): Long = when {
        failedAttempts <= 1 -> 5_000L
        failedAttempts == 2 -> 15_000L
        failedAttempts == 3 -> 30_000L
        else -> 60_000L
    }

    /** True when enough time has passed since the last attempt. */
    fun shouldAttempt(nowMs: Long, lastAttemptMs: Long, failedAttempts: Int): Boolean =
        nowMs - lastAttemptMs >= delayMs(failedAttempts)
}
