package com.parampilot.auto

/**
 * Poll cadence backoff for the CommaClient loop.
 *
 * The loop polls at its base cadence (1 s, or the SG parked interval)
 * with no regard to failures, so when the comma is unreachable — or
 * Galaxy Cloud keeps answering 503 because the comma is offline — the
 * app fires a request every second indefinitely. The connection
 * journal of 2026-11-20 showed 35+ identical Cloud 503 responses in
 * ~40 s while the car was parked and off: wasted battery and data,
 * and journal noise that buries real events.
 *
 * Rule: once fetches have failed [FAILURE_THRESHOLD] times in a row,
 * the poll interval stretches to at least [BACKED_OFF_MS] until a
 * fetch succeeds again (the caller passes the live failure count,
 * which resets to 0 on success). The base cadence is never shortened.
 */
object PollBackoff {

    const val FAILURE_THRESHOLD = 3
    const val BACKED_OFF_MS = 5000L

    fun effectiveDelayMs(baseMs: Long, consecutiveFailures: Int): Long =
        if (consecutiveFailures >= FAILURE_THRESHOLD) maxOf(baseMs, BACKED_OFF_MS) else baseMs
}
