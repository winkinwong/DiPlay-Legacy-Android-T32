package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A silent iPhone sender must be reported (so the engine can request a
 * keyframe) without ending the stream: measured stalls clear on their
 * own, and closing the screen stream only tears down the whole CarPlay
 * session because the iPhone does not re-SETUP it.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenStreamStallTest {
    private class RecordingListener : ScreenStream.Listener {
        @Volatile
        var closedCause: Throwable? = null
        private val closedLatch = CountDownLatch(1)
        private val lock = Any()
        val stallReports = mutableListOf<Long>()

        override fun onReceiveStall(millis: Long) {
            synchronized(lock) { stallReports.add(millis) }
        }

        override fun onClosed(cause: Throwable?) {
            closedCause = cause
            closedLatch.countDown()
        }

        fun awaitClosed(timeoutMillis: Long): Boolean =
            closedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    @Test
    fun silentStreamReportsStallWithoutEndingTheStream() {
        val listener = RecordingListener()
        val stream = stalledTestStream()
        val port = stream.listen(listener)
        Socket("127.0.0.1", port).use { client ->
            client.outputStream.write(configMessage())
            client.outputStream.flush()
            Thread.sleep(1_000)
            assertTrue("stall report expected", listener.stallReports.isNotEmpty())
            assertTrue(
                "stall report should carry the silent duration",
                listener.stallReports.all { it >= STALL_REPORT_MILLIS },
            )
            assertNull("stream must stay open through a receive stall", listener.closedCause)
            // A payload after the stall resets the stall clock: within
            // less than the report threshold, no new report may fire
            // (an unreset clock would report within one report interval).
            client.outputStream.write(configMessage())
            client.outputStream.flush()
            val reportsAtReset = listener.stallReports.size
            Thread.sleep(150)
            assertTrue(
                "stall clock resets after a payload",
                listener.stallReports.size == reportsAtReset,
            )
        }
        // The client EOF ends the stream.
        assertTrue(listener.awaitClosed(5_000))
        stream.close()
    }

    @Test
    fun flowingStreamDoesNotReportStall() {
        val listener = RecordingListener()
        val stream = stalledTestStream()
        val port = stream.listen(listener)
        Socket("127.0.0.1", port).use { client ->
            repeat(40) {
                client.outputStream.write(configMessage())
                client.outputStream.flush()
                Thread.sleep(25)
            }
        }
        // The client EOF ends the stream; no stall report may fire while
        // payloads keep arriving.
        assertTrue(listener.awaitClosed(5_000))
        assertTrue("no stall report expected", listener.stallReports.isEmpty())
        stream.close()
    }

    @Test
    fun stallReportsAreRateLimited() {
        val listener = RecordingListener()
        val stream = ScreenStream(ByteArray(32)).apply {
            readTimeoutMillis = 100
            stallReportMillis = STALL_REPORT_MILLIS
            stallReportIntervalMillis = 400
        }
        val port = stream.listen(listener)
        Socket("127.0.0.1", port).use { client ->
            client.outputStream.write(configMessage())
            client.outputStream.flush()
            Thread.sleep(1_400)
        }
        // 1.4 s of silence with a 400 ms interval: at most a handful of
        // reports, never one per 100 ms read timeout.
        assertTrue("stall report expected", listener.stallReports.isNotEmpty())
        assertTrue(
            "reports must be rate-limited to the interval",
            listener.stallReports.size <= 5,
        )
        stream.close()
    }

    private fun stalledTestStream(): ScreenStream =
        ScreenStream(ByteArray(32)).apply {
            readTimeoutMillis = 100
            stallReportMillis = STALL_REPORT_MILLIS
            stallReportIntervalMillis = 100
        }

    private companion object {
        const val HEADER_LEN = 128
        const val OP_VIDEO_CONFIG = 1
        const val STALL_REPORT_MILLIS = 200L

        /** A clear VideoConfig message: a 128-byte header plus an avcC body. */
        fun configMessage(): ByteArray {
            val header = ByteArray(HEADER_LEN)
            val body = ByteArray(20)
            "avcC".toByteArray(Charsets.US_ASCII).copyInto(body, 4)
            header[0] = (body.size and 0xff).toByte()
            header[1] = (body.size ushr 8 and 0xff).toByte()
            header[2] = (body.size ushr 16 and 0xff).toByte()
            header[3] = (body.size ushr 24).toByte()
            header[4] = OP_VIDEO_CONFIG.toByte()
            return header + body
        }
    }
}
