package com.shilapi.xcertplay.media

import android.util.Log

/** Five-second video counters that separate network/iPhone gaps from decoder throughput. */
internal class VideoStats(
    private val label: String = "",
    private val nanoTime: () -> Long = System::nanoTime,
    /** Stream frame size; the quality flag judges bits per PIXEL, so the same bitrate at 100% is judged harsher. */
    private val framePixels: Int = DEFAULT_FRAME_PIXELS,
) {
    private var windowStartNs = nanoTime()
    private var lastArrivalNs = 0L
    private var received = 0
    private var rendered = 0
    private var recoveries = 0
    private var bytes = 0L
    private var maxArrivalGapNs = 0L
    private var touchSamples = 0
    private var touchLatencySumNs = 0L
    private var maxTouchLatencyNs = 0L
    private val decodeMicros = IntArray(1024)
    private var decodeSamples = 0
    private var late = 0
    private var pacingDelayNanos = 0L

    @Synchronized fun onReceived(size: Int) {
        val now = nanoTime()
        val gap = now - lastArrivalNs
        if (lastArrivalNs != 0L && gap < IDLE_GAP_NS) maxArrivalGapNs = maxOf(maxArrivalGapNs, gap)
        lastArrivalNs = now
        // Touches only change the main screen; a second stream must not consume their samples.
        val touchLatency = if (label.isEmpty()) TouchLatencyProbe.onFrame(now) else -1L
        if (touchLatency >= 0) {
            touchSamples++
            touchLatencySumNs += touchLatency
            maxTouchLatencyNs = maxOf(maxTouchLatencyNs, touchLatency)
        }
        received++
        bytes += size
    }

    @Synchronized fun onRendered() { rendered++ }

    @Synchronized fun onRecovery() { recoveries++ }

    /** Time from queueing a frame into the decoder to dequeueing its output. */
    @Synchronized fun onDecodeLatency(ns: Long) {
        if (decodeSamples < decodeMicros.size) decodeMicros[decodeSamples++] = (ns / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** A paced frame left the decoder after its display time and was shown at once. */
    @Synchronized fun onLate() { late++ }

    /** Smooth video's current display delay ([PacingDelay]). */
    @Synchronized fun onPacingDelay(ns: Long) { pacingDelayNanos = ns }

    private fun decodeSummary(): String {
        if (decodeSamples == 0) return ""
        val sorted = decodeMicros.copyOf(decodeSamples).also { it.sort() }
        fun percentileMs(p: Int) = sorted[(decodeSamples - 1) * p / 100] / 1000
        val p50 = percentileMs(50)
        val delay = if (pacingDelayNanos > 0) " delay=%dms".format(pacingDelayNanos / 1_000_000) else ""
        // A decoder slower than the 24 fps budget cannot keep ANY frame rate
        // smooth; the stats line tells the driver which knob to turn next.
        val hint = if (p50 > DECODE_BUDGET_MILLIS_24FPS) " hint=decoder-too-slow-lower-fps" else ""
        return " decode p50=%dms p90=%dms late=%d".format(p50, percentileMs(90), late) + delay + hint
    }

    /**
     * The iPhone adapts its encode quality on its own (measured 2026-10-21: bits per frame swing 4-5x at a
     * CONSTANT 768x432 stream, 30% of smooth windows visibly blurry). This field makes that visible: a
     * window below [BLUR_BITS_PER_PIXEL] is the "the picture went soft" the driver reports, and a report can
     * align it with stalls and recovery bursts instead of guessing.
     */
    private fun qualitySummary(): String {
        if (rendered == 0 || framePixels <= 0) return ""
        val bitsPerPixel = bytes * 8.0 / rendered / framePixels
        val quality = if (bitsPerPixel < BLUR_BITS_PER_PIXEL) "blur" else "ok"
        return " bitsPerFrame=%.1f quality=%s".format(bytes * 8.0 / rendered / 1024, quality)
    }

    @Synchronized fun logIfDue(): String? {
        val now = nanoTime()
        val elapsedNs = now - windowStartNs
        if (elapsedNs < WINDOW_NS) return null
        val seconds = elapsedNs / 1e9
        if (received == 0 && touchSamples == 0) { windowStartNs = now; return null }
        val touchAvgMs = if (touchSamples == 0) -1 else touchLatencySumNs / touchSamples / 1_000_000
        val line = ("video stats$label rx=%.1ffps shown=%.1ffps maxGap=%dms kbps=%d recoveries=%d " +
            "touch2frame avg=%dms max=%dms n=%d touchSendMax=%dms").format(
            received / seconds, rendered / seconds, maxArrivalGapNs / 1_000_000,
            (bytes * 8 / 1000 / seconds).toLong(), recoveries,
            touchAvgMs, maxTouchLatencyNs / 1_000_000, touchSamples, TouchLatencyProbe.maxSendNs / 1_000_000,
        ) + decodeSummary() + qualitySummary()
        TouchLatencyProbe.resetMaxSendNs()
        Log.i(TAG, line)
        windowStartNs = now
        received = 0; rendered = 0; recoveries = 0; bytes = 0; maxArrivalGapNs = 0
        touchSamples = 0; touchLatencySumNs = 0; maxTouchLatencyNs = 0
        decodeSamples = 0; late = 0
        return line
    }

    private companion object {
        const val TAG = "DiPlay-VideoStats"
        const val WINDOW_NS = 5_000_000_000L
        const val IDLE_GAP_NS = 2_000_000_000L // longer gaps are a static screen, not lag
        /** 24 fps frame budget: a slower decoder stutters at every offered frame rate. */
        private const val DECODE_BUDGET_MILLIS_24FPS = 40
        /** 768x432 — the X-Trail's 60% stream; a fallback when the caller does not pass the real size. */
        const val DEFAULT_FRAME_PIXELS = 768 * 432
        /**
         * 12 kbit/frame at 768x432 (the 2026-10-21 field boundary between "clear" and "visibly soft"
         * windows at a constant resolution), expressed per pixel so 1280x720 is judged by the same yardstick.
         */
        const val BLUR_BITS_PER_PIXEL = 12 * 1024.0 / (768 * 432)
    }
}
