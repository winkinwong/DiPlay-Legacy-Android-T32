package com.shilapi.xcertplay.airplay

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #288 (upstream v0.2.86): on slow-decrypt hardware the i.MX6 decrypts at ~1.9 MB/s, so
 * a synchronous decrypt stalls the receive loop; a burst then fills the decode queue and the
 * overflow drops shatter the H.264 reference chain — mosaic blocks and a frozen picture while
 * rx stays healthy. Frames now wait briefly for a decrypt slot instead of being dropped, the
 * receive loop stalls, and TCP backpressure paces the iPhone.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenStreamDecryptBackpressureTest {
    private class RecordingListener : ScreenStream.Listener {
        val frames = mutableListOf<Long>() // arrivalNanos per delivered frame
        val drops = mutableListOf<Long>()
        val firstFrameLatch = CountDownLatch(1)
        @Volatile var closedCause: Throwable? = null

        override fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            synchronized(frames) { frames.add(arrivalNanos) }
            firstFrameLatch.countDown()
        }

        override fun onClosed(cause: Throwable?) {
            closedCause = cause
            firstFrameLatch.countDown()
        }
    }

    @Test
    fun aGatedWorkerDropsTheOverflowButDeliversWhatFitsInOrder() {
        // The decrypt worker is gated: the queue fills to capacity and the receive loop waits
        // 120 ms per overflowing frame before dropping it. Delivered frames must be exactly the
        // queued ones, in send order.
        val gate = CountDownLatch(1)
        val listener = RecordingListener()
        val stream = ScreenStream(ByteArray(32)).apply { decryptGate = { gate.await() } }
        val port = stream.listen(listener)
        Socket("127.0.0.1", port).use { client ->
            repeat(FRAMES) {
                client.outputStream.write(frameMessage(it))
                client.outputStream.flush()
            }
            // The receive loop must have finished enqueueing (and dropping) without deadlock:
            // with a gated worker every write above returned within ~120 ms per dropped frame.
            gate.countDown()
            assertTrue("queued frames must be delivered", listener.firstFrameLatch.await(10, TimeUnit.SECONDS))
            // The worker drains the queue after the gate opens; wait for it to stop delivering so
            // the count below is the settled number rather than the first-arrival snapshot.
            waitForDeliveryToSettle(listener)
        }
        stream.close()
        // Ordering: arrival timestamps of delivered frames never decrease.
        synchronized(listener.frames) {
            assertEquals("frames must stay in order", listener.frames.sorted(), listener.frames)
            assertTrue(
                "at least the queue capacity is delivered (got ${listener.frames.size})",
                listener.frames.size >= QUEUE_CAPACITY,
            )
            assertTrue("the stream survives the drops", listener.closedCause == null)
        }
    }

    /** Waits until no new frame arrives for [QUIET_MILLIS], bounded by [maxMillis]. */
    private fun waitForDeliveryToSettle(listener: RecordingListener, maxMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + maxMillis
        var lastCount = -1
        var lastChange = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val count = synchronized(listener.frames) { listener.frames.size }
            if (count != lastCount) {
                lastCount = count
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= QUIET_MILLIS) {
                return
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun aFrameDeliveredAfterTheBackpressureDecryptsCleanly() {
        // The gate is released after the burst: the worker drains and delivers a frame whose
        // nonce was captured at reception time, so the decrypt stays in sync.
        val gate = CountDownLatch(1)
        val listener = RecordingListener()
        val stream = ScreenStream(ByteArray(32)).apply { decryptGate = { gate.await() } }
        val port = stream.listen(listener)
        Socket("127.0.0.1", port).use { client ->
            repeat(FRAMES) {
                client.outputStream.write(frameMessage(it))
                client.outputStream.flush()
            }
            gate.countDown()
            assertTrue("a frame must be delivered", listener.firstFrameLatch.await(10, TimeUnit.SECONDS))
        }
        stream.close()
        assertTrue("stream stays open through the burst", listener.closedCause == null)
    }

    private companion object {
        const val HEADER_LEN = 128
        const val OP_VIDEO_FRAME = 0
        const val FRAMES = 30
        const val QUEUE_CAPACITY = 10
        const val QUIET_MILLIS = 300L

        /**
         * A video frame whose body stays BELOW the 16-byte auth tag, so it is delivered as clear
         * payload (ScreenCodec.decryptFrame returns it unchanged). This isolates the queue and
         * backpressure behavior from ChaCha: a random 64-byte body would fail authentication and
         * be skipped by the per-frame recovery path instead of being delivered.
         */
        fun frameMessage(nonce: Int): ByteArray {
            val header = ByteArray(HEADER_LEN)
            val body = ByteArray(8)
            header[0] = (body.size and 0xff).toByte()
            header[1] = (body.size ushr 8 and 0xff).toByte()
            header[2] = (body.size ushr 16 and 0xff).toByte()
            header[3] = (body.size ushr 24).toByte()
            header[4] = OP_VIDEO_FRAME.toByte()
            for (index in body.indices) body[index] = (nonce + index).toByte()
            return header + body
        }
    }
}
