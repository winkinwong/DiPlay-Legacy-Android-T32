package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #4 (2026-11-10): on a non-BYD head unit the HUD bridge retried bindService() every 300 ms
 * for the whole session. Failed binds must back off instead of hammering the system server.
 */
class HudBindBackoffTest {
    @Test
    fun theFirstAttemptIsDueImmediately() {
        val backoff = HudBindBackoff()
        assertTrue(backoff.due(0L))
    }

    @Test
    fun aFailedAttemptWaitsAndThenDoubles() {
        val backoff = HudBindBackoff(initialMillis = 300L, maxMillis = 10_000L)
        backoff.failed(1_000L)
        assertFalse("300 ms is not up yet", backoff.due(1_299L))
        assertTrue(backoff.due(1_300L))

        backoff.failed(1_300L) // next gap is 600 ms
        assertFalse(backoff.due(1_899L))
        assertTrue(backoff.due(1_900L))

        backoff.failed(1_900L) // next gap is 1200 ms
        assertFalse(backoff.due(3_099L))
        assertTrue(backoff.due(3_100L))
    }

    @Test
    fun theGapStopsGrowingAtTheCeiling() {
        val backoff = HudBindBackoff(initialMillis = 300L, maxMillis = 2_000L)
        var now = 0L
        repeat(10) {
            backoff.failed(now)
            now += 2_000L
        }
        backoff.failed(now)
        assertFalse("the ceiling is 2 s, not 3.2 s", backoff.due(now + 1_999L))
        assertTrue(backoff.due(now + 2_000L))
    }

    @Test
    fun aSuccessReturnsToTheFastPath() {
        val backoff = HudBindBackoff(initialMillis = 300L, maxMillis = 10_000L)
        repeat(5) { backoff.failed(0L) }
        backoff.succeeded()
        assertTrue("a fresh gateway retries at once", backoff.due(0L))
        backoff.failed(0L)
        assertFalse("and the gap is back to 300 ms", backoff.due(299L))
        assertTrue(backoff.due(300L))
    }
}
