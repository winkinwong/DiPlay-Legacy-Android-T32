package com.shilapi.xcertplay.media

/**
 * Paces "please send a keyframe" requests.
 *
 * A healthy stream needs at most one request per [normalIntervalNs]. When frames stop arriving
 * the sender is mid-pause: repeating the request every second does not make it answer, it only
 * adds 30-60 KB IDR bursts to a link that is already struggling. Measured 2026-10-29: 53 requests
 * in one 11-minute wireless session while the picture was frozen for 60 s. While starved the
 * interval doubles up to [starvedMaxNs]; the moment frames flow again it snaps back to normal, so
 * a genuinely lost keyframe still recovers within a second.
 */
internal class KeyFrameRequestPacer(
    private val normalIntervalNs: Long = 1_000_000_000L,
    private val starvedFirstIntervalNs: Long = 1_000_000_000L,
    private val starvedMaxNs: Long = 8_000_000_000L,
    private val starvedAfterNs: Long = 1_000_000_000L,
) {
    private var hasRequested = false
    private var lastRequestNs = 0L
    private var starvedIntervalNs = starvedFirstIntervalNs

    /**
     * True when a request may be sent now. [lastFrameNs] is when a video access unit last arrived
     * (0 when none has yet); a gap of [starvedAfterNs] or more counts as starvation.
     */
    fun shouldRequest(nowNs: Long, lastFrameNs: Long): Boolean {
        val starved = lastFrameNs == 0L || nowNs - lastFrameNs >= starvedAfterNs
        val interval = if (starved) starvedIntervalNs else normalIntervalNs
        if (hasRequested && nowNs - lastRequestNs < interval) return false
        hasRequested = true
        lastRequestNs = nowNs
        starvedIntervalNs = if (starved) {
            (starvedIntervalNs * 2).coerceAtMost(starvedMaxNs)
        } else {
            starvedFirstIntervalNs
        }
        return true
    }

    /** A recovery reset (decoder rebuilt, surface changed) must be able to ask right away. */
    fun forceNext() {
        hasRequested = false
        lastRequestNs = 0L
        starvedIntervalNs = starvedFirstIntervalNs
    }
}
