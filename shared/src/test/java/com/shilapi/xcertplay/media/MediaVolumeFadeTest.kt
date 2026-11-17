package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17 field report: "音乐和导航的声音切换还是有点生硬". The sink used to apply the iPhone's
 * -13.98 dB duck in a single setStereoVolume call; these fades replace that step with a 200 ms ramp.
 */
class MediaVolumeFadeTest {
    @Test
    fun theEndpointsAreExact() {
        assertEquals(1f, MediaVolumeFade.interpolate(1f, 0.2f, 0f), 1e-6f)
        assertEquals(0.2f, MediaVolumeFade.interpolate(1f, 0.2f, 1f), 1e-6f)
    }

    /** Halfway in decibels is the geometric mean, not the arithmetic one (0.6 would sound too loud). */
    @Test
    fun theMidpointIsGeometricNotArithmetic() {
        val halfway = MediaVolumeFade.interpolate(1f, 0.2f, 0.5f)
        assertEquals(sqrt(0.2f), halfway, 1e-4f)
        assertTrue("must sit below the arithmetic mean", halfway < 0.6f)
    }

    @Test
    fun theFadeIsMonotonicDownwardsAndUpwards() {
        var previous = 1f
        for (step in 1..10) {
            val gain = MediaVolumeFade.interpolate(1f, 0.2f, step / 10f)
            assertTrue("duck must not rise: $gain after $previous", gain <= previous + 1e-6f)
            previous = gain
        }
        previous = 0.2f
        for (step in 1..10) {
            val gain = MediaVolumeFade.interpolate(0.2f, 1f, step / 10f)
            assertTrue("unduck must not fall: $gain after $previous", gain >= previous - 1e-6f)
            previous = gain
        }
    }

    @Test
    fun aFadeReportsItsOwnProgress() {
        val fade = VolumeFade(from = 1f, to = 0.2f, startedAtMillis = 1_000L, durationMillis = 200L)
        assertEquals(1f, fade.gainAt(1_000L), 1e-6f)
        assertFalse(fade.finished(1_199L))
        assertTrue(fade.finished(1_200L))
        assertEquals(0.2f, fade.gainAt(1_200L), 1e-6f)
        assertEquals("an overrun still lands on the target", 0.2f, fade.gainAt(5_000L), 1e-6f)
        assertEquals("a clock that steps back must not overshoot", 1f, fade.gainAt(0L), 1e-6f)
    }

    @Test
    fun aZeroLengthFadeIsImmediate() {
        val fade = VolumeFade(from = 1f, to = 0.3f, startedAtMillis = 0L, durationMillis = 0L)
        assertTrue(fade.finished(0L))
        assertEquals(0.3f, fade.gainAt(0L), 1e-6f)
    }

    @Test
    fun degenerateGainsStayInRange() {
        assertTrue(MediaVolumeFade.interpolate(0f, 1f, 0.5f) > 0f)
        assertTrue(MediaVolumeFade.interpolate(1f, 1f, 0.5f) <= 1f)
        assertEquals(1f, MediaVolumeFade.interpolate(1f, 1f, 0.5f), 1e-6f)
        assertTrue(abs(MediaVolumeFade.interpolate(1f, 0f, 0.5f)) <= 1f)
    }
}
