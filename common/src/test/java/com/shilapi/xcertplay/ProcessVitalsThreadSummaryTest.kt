package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 report: "断开 carplay 之后重新连接很慢，而且重连之后整个车机都很卡".
 * A reconnect builds a new controller, so the exported report must show whether DiPlay's own
 * thread count keeps climbing instead of settling back.
 */
class ProcessVitalsThreadSummaryTest {
    private fun thread(name: String) = Thread({ }, name)

    @Test
    fun onlyDiplayOwnedThreadsAreCounted() {
        val threads = arrayOf(
            thread("carplay-video"),
            thread("carplay-audio"),
            thread("diplay-heartbeat"),
            thread("xcertplay-wireless-restart"),
            thread("main"),
            thread("RenderThread"),
            thread("Binder_1"),
        )
        val summary = ProcessVitals.threadSummary(threads)
        assertTrue("total counts everything: $summary", summary.contains("threads total=7"))
        assertTrue("only our own threads are ours: $summary", summary.contains("diplay=4"))
    }

    @Test
    fun theBusiestNamesAreListed() {
        val threads = Array(3) { thread("carplay-video") } +
            Array(2) { thread("carplay-audio") } +
            arrayOf(thread("main"))
        val summary = ProcessVitals.threadSummary(threads)
        assertTrue("duplicate names collapse into counts: $summary", summary.contains("carplay-video=3"))
        assertTrue(summary.contains("carplay-audio=2"))
        assertFalse("foreign threads never appear", summary.contains("main="))
    }

    @Test
    fun anEmptyProcessStillReports() {
        val summary = ProcessVitals.threadSummary(emptyArray())
        assertEquals("threads total=0 diplay=0", summary)
    }

    @Test
    fun threadNamesFollowTheProjectPrefixes() {
        assertTrue(ProcessVitals.isDiplayThread(thread("carplay-screen-decrypt")))
        assertTrue(ProcessVitals.isDiplayThread(thread("diplay-byd-hud")))
        assertTrue(ProcessVitals.isDiplayThread(thread("xcertplay-wireless-restart")))
        assertFalse(ProcessVitals.isDiplayThread(thread("main")))
        assertFalse(ProcessVitals.isDiplayThread(thread("FinalizerDaemon")))
    }
}
