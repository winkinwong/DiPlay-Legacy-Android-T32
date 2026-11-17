package com.shilapi.xcertplay.media

import kotlin.math.pow

/**
 * A short media-volume fade for navigation ducking.
 *
 * The iPhone asks for [-13.98 dB] and the sink used to jump there in one `setStereoVolume` call, so the
 * music dropped -14 dB in a single frame and snapped back the same way: the driver hears a hard
 * "click" where the head unit's own ducking eases in and out (2026-11-17 field report: "音乐和导航
 * 的切换还是有点生硬"). A 200 ms fade removes the step without delaying the announcement.
 *
 * The interpolation runs in DECIBELS, not linear gain: a linear 1.0 → 0.2 fade sounds like it falls
 * fast and then hangs, because loudness perception is logarithmic. Halving the dB distance at the
 * midpoint ([interpolate]) gives the geometric mean, which is what a mixer's fader does.
 */
object MediaVolumeFade {
    /** Short enough to feel instant, long enough to remove the click. */
    const val DURATION_MILLIS = 200L
    /** Ten steps: 20 ms apart, cheap enough to run on the main looper. */
    const val STEPS = 10
    const val STEP_MILLIS = DURATION_MILLIS / STEPS
    private const val MIN_GAIN = 1e-4f
    private const val MAX_GAIN = 1f

    /**
     * Gain [fraction] of the way from [from] to [to], interpolated in decibels.
     * [fraction] is clamped to 0..1, and gains are clamped to a sane (0, 1] range.
     */
    fun interpolate(from: Float, to: Float, fraction: Float): Float {
        val start = from.coerceIn(MIN_GAIN, MAX_GAIN)
        val end = to.coerceIn(MIN_GAIN, MAX_GAIN)
        if (start == end) return end
        val f = fraction.coerceIn(0f, 1f)
        if (f == 0f) return start
        if (f == 1f) return end
        return (start * (end / start).pow(f)).coerceIn(MIN_GAIN, MAX_GAIN)
    }
}

/**
 * One fade in progress. [gainAt] is time-based so a late or coalesced ticker callback still lands on
 * the right gain instead of drifting.
 */
internal class VolumeFade(
    val from: Float,
    val to: Float,
    private val startedAtMillis: Long,
    private val durationMillis: Long = MediaVolumeFade.DURATION_MILLIS,
) {
    fun gainAt(nowMillis: Long): Float {
        if (durationMillis <= 0L) return to
        val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return MediaVolumeFade.interpolate(from, to, elapsed.toFloat() / durationMillis)
    }

    fun finished(nowMillis: Long): Boolean =
        durationMillis <= 0L || nowMillis - startedAtMillis >= durationMillis
}
