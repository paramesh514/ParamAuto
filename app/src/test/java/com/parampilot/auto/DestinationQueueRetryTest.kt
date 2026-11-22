package com.parampilot.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Addendum 123 (Param, 2026-11-21): a not-accepted queued destination
 * is retried every 15 seconds while the comma is connected, and no
 * new destination is accepted until the queue is cleared.
 */
class DestinationQueueRetryTest {

    private val t0 = 1_000_000L

    @Test
    fun noFlushWhenCommaDisconnectedOrQueueEmpty() {
        assertFalse(DestinationQueue.shouldFlush(commaFound = false, hasQueued = true, lastFailureAtMs = 0L, nowMs = t0))
        assertFalse(DestinationQueue.shouldFlush(commaFound = true, hasQueued = false, lastFailureAtMs = 0L, nowMs = t0))
    }

    @Test
    fun firstAttemptIsImmediate() {
        assertTrue(DestinationQueue.shouldFlush(commaFound = true, hasQueued = true, lastFailureAtMs = 0L, nowMs = t0))
    }

    @Test
    fun retryWaitsFifteenSecondsAfterFailure() {
        assertFalse(
            DestinationQueue.shouldFlush(
                commaFound = true, hasQueued = true,
                lastFailureAtMs = t0, nowMs = t0 + 14_999L
            )
        )
        assertTrue(
            DestinationQueue.shouldFlush(
                commaFound = true, hasQueued = true,
                lastFailureAtMs = t0, nowMs = t0 + 15_000L
            )
        )
        assertTrue(
            DestinationQueue.shouldFlush(
                commaFound = true, hasQueued = true,
                lastFailureAtMs = t0, nowMs = t0 + 120_000L
            )
        )
    }

    @Test
    fun newDestinationBlockedUntilQueueClears() {
        val queued = NavDestination(
            name = "Costco", placeName = "Costco, Phoenix, AZ",
            latitude = 33.6, longitude = -112.1, source = "comma"
        )
        val other = NavDestination(
            name = "Target", placeName = "Target, Phoenix, AZ",
            latitude = 33.7, longitude = -112.2, source = "comma"
        )
        assertTrue(DestinationQueue.blocksNewDestination(queued, other))
        assertFalse(DestinationQueue.blocksNewDestination(queued, queued))
        assertFalse(DestinationQueue.blocksNewDestination(null, other))
    }
}
