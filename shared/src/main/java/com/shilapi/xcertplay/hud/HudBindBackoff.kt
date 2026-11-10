package com.shilapi.xcertplay.hud

/**
 * Retry pacing for binding BYD's SOME/IP gateway.
 *
 * The HUD bridge used to retry `bindService()` on every 300 ms tick, forever, whether or not the
 * gateway exists. On a non-BYD head unit that is ~3.3 failed IPC calls and as many log lines per
 * second for the whole session (issue #4, 2026-11-10: an X-Trail owner noticed the wasted work).
 * The gateway either exists or it does not, so a failed bind backs off exponentially and a success
 * returns to the fast path.
 */
internal class HudBindBackoff(
    private val initialMillis: Long = INITIAL_MILLIS,
    private val maxMillis: Long = MAX_MILLIS,
) {
    private var delayMillis = initialMillis
    private var nextAttemptAtMillis = Long.MIN_VALUE

    /** True when the next bind attempt may run at [nowMillis] (uptime base). */
    fun due(nowMillis: Long): Boolean = nowMillis >= nextAttemptAtMillis

    /** Records a failed attempt: the next one waits longer than the last. */
    fun failed(nowMillis: Long) {
        nextAttemptAtMillis = nowMillis + delayMillis
        delayMillis = (delayMillis * 2).coerceAtMost(maxMillis)
    }

    /** A bound (or re-created) gateway retries immediately and quickly again. */
    fun succeeded() {
        delayMillis = initialMillis
        nextAttemptAtMillis = Long.MIN_VALUE
    }

    internal companion object {
        const val INITIAL_MILLIS = 300L
        const val MAX_MILLIS = 10_000L
    }
}
