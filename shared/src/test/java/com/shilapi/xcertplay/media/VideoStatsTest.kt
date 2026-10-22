package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stats line is the troubleshooting channel for choppy video: when the
 * head unit's hardware decoder cannot keep up (field report 2026-10-15,
 * OMX.Freescale.std decoding at p50=41-47 ms), it must say which knob to
 * turn instead of only showing a low "shown" frame rate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class VideoStatsTest {
    @Test
    fun slowDecoderHintsToLowerTheFrameRate() {
        var clock = 0L
        val stats = VideoStats(label = "", nanoTime = { clock })
        // A 47 ms decode p50 exceeds the 24 fps budget (40 ms): no offered
        // frame rate can render smoothly on this decoder.
        repeat(10) { stats.onDecodeLatency(47_000_000) }
        stats.onReceived(1000)
        clock = 5_000_000_000L
        val line = stats.logIfDue()
        assertTrue("stats line logged", line != null && line.contains("decode p50=47ms"))
        assertTrue("slow decoder hint present", line!!.contains("hint=decoder-too-slow-lower-fps"))
    }

    @Test
    fun fastDecoderKeepsTheStatsLineClean() {
        var clock = 0L
        val stats = VideoStats(label = "", nanoTime = { clock })
        repeat(10) { stats.onDecodeLatency(10_000_000) }
        stats.onReceived(1000)
        clock = 5_000_000_000L
        val line = stats.logIfDue()
        assertTrue("stats line logged", line != null && line.contains("decode p50=10ms"))
        assertFalse("no hint for a healthy decoder", line!!.contains("hint="))
    }

    @Test
    fun softEncodeIsFlaggedAsBlur() {
        var clock = 0L
        val stats = VideoStats(label = "", nanoTime = { clock })
        // 50 frames x 1000 bytes at 768x432 = 8000 bit/frame = 0.024 bit/pixel: below the
        // 12 kbit/frame field boundary, the "picture went soft" window the driver reports.
        repeat(50) { stats.onReceived(1000); stats.onRendered() }
        clock = 5_000_000_000L
        val line = stats.logIfDue()!!
        assertTrue("bits per frame reported", line.contains("bitsPerFrame=7.8"))
        assertTrue("blur flagged", line.contains("quality=blur"))
    }

    @Test
    fun theSameBitrateIsJudgedHarsherAtFullResolution() {
        // 50 frames x 2500 bytes = 19.5 kbit/frame: clearly soft at 1280x720 (0.022 bit/px),
        // comfortably clear at 768x432 (0.060 bit/px).
        fun lineFor(framePixels: Int): String {
            var clock = 0L
            val stats = VideoStats(label = "", nanoTime = { clock }, framePixels = framePixels)
            repeat(50) { stats.onReceived(2500); stats.onRendered() }
            clock = 5_000_000_000L
            return stats.logIfDue()!!
        }
        assertTrue("768x432 stays clear", lineFor(768 * 432).contains("quality=ok"))
        assertTrue("1280x720 flags blur", lineFor(1280 * 720).contains("quality=blur"))
    }

    @Test
    fun aFrozenWindowReportsNoQuality() {
        var clock = 0L
        val stats = VideoStats(label = "", nanoTime = { clock })
        // Received but nothing shown: bits per shown frame is meaningless, the stall lines carry the story.
        repeat(5) { stats.onReceived(5000) }
        clock = 5_000_000_000L
        val line = stats.logIfDue()!!
        assertFalse("no quality on a frozen window", line.contains("quality="))
    }
}
