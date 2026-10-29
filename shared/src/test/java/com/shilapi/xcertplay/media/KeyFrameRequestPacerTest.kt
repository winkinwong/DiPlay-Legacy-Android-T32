package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFrameRequestPacerTest {
    private val ms = 1_000_000L

    /** Frames every 33 ms and a poll every 100 ms: one request per second, never more. */
    @Test
    fun aHealthyStreamKeepsTheOneSecondCadence() {
        val pacer = KeyFrameRequestPacer()
        var requests = 0
        var now = 0L
        while (now <= 5_000 * ms) {
            if (pacer.shouldRequest(now, now - 33 * ms)) requests++
            now += 100 * ms
        }
        assertTrue("expected 5-6 requests, got $requests", requests in 5..6)
    }

    /**
     * The 2026-10-29 field case: the sender stops for a minute. Sixty polls must not become sixty
     * requests — the ladder 1/2/4/8 s caps it near ten.
     */
    @Test
    fun aStarvedStreamBacksOffInsteadOfFlooding() {
        val pacer = KeyFrameRequestPacer()
        var requests = 0
        var now = 0L
        while (now <= 60_000 * ms) {
            if (pacer.shouldRequest(now, 0L)) requests++
            now += 100 * ms
        }
        assertTrue("expected 8-10 requests over 60 s, got $requests", requests in 8..10)
    }

    @Test
    fun framesResumingRestoreTheOneSecondCadence() {
        val pacer = KeyFrameRequestPacer()
        var now = 0L
        // Starve for 20 s so the ladder climbs to its cap.
        while (now <= 20_000 * ms) {
            pacer.shouldRequest(now, 0L)
            now += 100 * ms
        }
        var requests = 0
        val resumeStart = now
        while (now <= resumeStart + 5_000 * ms) {
            if (pacer.shouldRequest(now, now - 33 * ms)) requests++
            now += 100 * ms
        }
        assertTrue("expected ~5 requests after resume, got $requests", requests in 4..6)
    }

    /** A keyframe request lost on a healthy stream must not wait for the starved ladder. */
    @Test
    fun aRecentFrameUsesTheNormalInterval() {
        val pacer = KeyFrameRequestPacer()
        val t0 = 10_000 * ms
        assertTrue(pacer.shouldRequest(t0, t0 - 33 * ms))
        assertFalse(pacer.shouldRequest(t0 + 200 * ms, t0 + 167 * ms))
        assertTrue(pacer.shouldRequest(t0 + 1_100 * ms, t0 + 1_067 * ms))
    }

    @Test
    fun aRecoveryResetMayAskImmediately() {
        val pacer = KeyFrameRequestPacer()
        assertTrue(pacer.shouldRequest(0L, 0L))
        assertFalse("the 1 s window still applies", pacer.shouldRequest(10 * ms, 0L))
        pacer.forceNext()
        assertTrue("reset clears the wait", pacer.shouldRequest(20 * ms, 0L))
    }

    @Test
    fun theLadderNeverExceedsItsCeiling() {
        val pacer = KeyFrameRequestPacer()
        var now = 0L
        val granted = mutableListOf<Long>()
        while (now <= 300_000 * ms) {
            if (pacer.shouldRequest(now, 0L)) granted += now
            now += 100 * ms
        }
        val gaps = granted.zipWithNext { a, b -> b - a }
        assertEquals(8_000 * ms, gaps.max())
    }
}
