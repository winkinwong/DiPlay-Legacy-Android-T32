package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class VideoCodec { H264, H265 }

/**
 * Receives one CarPlay screen stream on a TCP data port.
 *
 * Each message is a 128-byte AirPlayScreenHeader followed by a body: a clear VideoConfig
 * (avcC/hvcC) or a ChaCha20-Poly1305 sealed VideoFrame. The key is the DataStream output key
 * and the per-frame nonce is an 8-byte little-endian counter.
 */
class ScreenStream(
    private val key: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val bindReference: InetAddress? = null,
) : Closeable {
    interface Listener {
        fun onCodec(codec: VideoCodec) {}
        fun onConfig(codecData: ByteArray) {}
        fun onFrame(naluBytes: ByteArray) {}
        /**
         * A frame with the iPhone's frame time ([senderNanos], from the header) and when its body finished
         * arriving ([arrivalNanos], System.nanoTime). Listeners that do not pace frames ignore both.
         */
        fun onFrame(naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) = onFrame(naluBytes)
        fun onClosed(cause: Throwable?) {}
        /**
         * No payload has arrived for [millis] although the stream is connected:
         * the iPhone's video sender may be stalled. Reported at most once per
         * stall report interval until a payload arrives or the stream resets.
         */
        fun onReceiveStall(millis: Long) {}
    }

    private val closed = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)
    private val firstFrameLogged = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var thread: Thread? = null
    @Volatile private var listener: Listener = object : Listener {}
    /** Test seam: production uses [READ_TIMEOUT_MILLIS]. */
    internal var readTimeoutMillis: Int = READ_TIMEOUT_MILLIS
    /** Test seam: production uses [STALL_REPORT_MILLIS]. */
    internal var stallReportMillis: Long = STALL_REPORT_MILLIS
    /** Test seam: production uses [STALL_REPORT_INTERVAL_MILLIS]. */
    internal var stallReportIntervalMillis: Long = STALL_REPORT_INTERVAL_MILLIS
    /** Test seam: the decrypt worker awaits this before each frame; production leaves it null. */
    internal var decryptGate: (() -> Unit)? = null
    /** Receive-thread owned: when the last payload finished arriving. */
    private var lastPayloadNanos = 0L
    /** Receive-thread owned: the last reported stall duration. */
    private var lastStallReportMillis = 0L

    /**
     * Port of upstream #249/#288: on slow-decrypt hardware (the X-Trail's i.MX6 decrypts at
     * ~1.9 MB/s measured) an inline decrypt stalls the socket receive loop, so frames arrive late
     * and a burst fills the decode queue, whose overflow drops shatter the H.264 reference chain
     * — mosaic blocks and a frozen picture while rx stays healthy. Video frames now go through
     * this queue to a dedicated decrypt thread; the receive loop only enqueues. When the queue is
     * full the receive loop waits briefly for a slot instead of dropping: the loop stalls, TCP
     * backpressure paces the iPhone, and spikes degrade into latency rather than lost frames.
     */
    private data class PendingFrame(
        val header: ByteArray,
        val body: ByteArray,
        val stats: StreamReceiveStats,
        val arrivalNanos: Long,
        /** Captured at reception time so a dropped frame never corrupts later frames' decryption. */
        val frameNonce: Long,
    )
    private val decryptQueue = LinkedBlockingQueue<PendingFrame>(DECRYPT_QUEUE_CAPACITY)
    @Volatile private var decryptThread: Thread? = null
    private val droppedFrames = AtomicLong(0)
    private val decryptFailures = AtomicInteger(0)

    private fun startDecryptWorker() {
        if (decryptThread != null) return
        decryptThread = Thread({ decryptLoop() }, "airplay-screen-decrypt").apply {
            isDaemon = true
            start()
        }
    }

    private fun decryptLoop() {
        while (!closed.get()) {
            val pending = try {
                decryptQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            try {
                decryptGate?.invoke()
                decryptAndDeliver(pending)
            } catch (error: Exception) {
                // decryptAndDeliver handles per-frame auth failures; anything escaping here is
                // stream-level (the consecutive-failure cap).
                if (!closed.get()) {
                    Log.e(TAG, "video decrypt worker failed", error)
                    listener.onClosed(error)
                    return
                }
            }
        }
    }

    fun listen(listener: Listener): Int {
        this.listener = listener
        startDecryptWorker()
        val bound = AirPlaySocketBinder.server(bindReference, "AirPlay screen")
        server = bound
        thread = Thread({ accept(bound) }, "airplay-screen").apply { isDaemon = true; start() }
        return bound.localPort
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        safeClose(server)
        thread?.interrupt()
        decryptThread?.interrupt()
        decryptQueue.clear()
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.accept()
            socket = accepted
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) listener.onClosed(error)
        }
    }

    private fun run(sock: Socket) {
        var failure: Throwable? = null
        val stats = StreamReceiveStats("video", onDiagnostic)
        try {
            // Reads time out so a silent iPhone sender is noticed and
            // nudged with a keyframe; see onReadTimeout. Partial reads
            // keep their progress across timeouts, so a slow link is
            // unaffected.
            sock.soTimeout = readTimeoutMillis
            val input = sock.getInputStream()
            while (!closed.get()) {
                stats.reading()
                val header = readFully(input, HEADER_LEN) ?: break
                val bodySize = readU32Le(header, 0)
                if (bodySize > MAX_BODY) break
                val body = readFully(input, bodySize) ?: break
                val arrivalNanos = System.nanoTime()
                lastPayloadNanos = arrivalNanos
                stats.received(HEADER_LEN + bodySize)
                AirPlayMediaActivity.onPayload()
                onMessage(header, body, stats, arrivalNanos)
                stats.processed()
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            stats.flush(ended = true)
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get()) listener.onClosed(failure)
        }
    }

    private fun onMessage(header: ByteArray, body: ByteArray, stats: StreamReceiveStats, arrivalNanos: Long) {
        when (header[OPCODE_OFFSET].toInt() and 0xff) {
            OP_VIDEO_FRAME -> {
                // Every received video frame advances the ChaCha nonce by 1. The nonce is captured
                // at reception time so each frame keeps its exact nonce regardless of worker queue
                // depth, delays, or drops (upstream #249).
                val frameNonce = frameCounter.getAndIncrement()
                // Upstream #288: a plain offer() drops the frame the moment the queue is full. On
                // units whose software decryption is barely as fast as the stream, one slow frame
                // (GC/JIT spikes reach 300 ms+) fills the 10-frame queue and the overflow drops
                // shatter the reference chain. Wait briefly for a slot instead: the receive loop
                // stalls, TCP backpressure paces the phone, and spikes degrade into latency rather
                // than lost frames. Sustained overload still drops after the wait, and the nonce
                // was already captured, so a drop never corrupts later frames' decryption.
                val queued = try {
                    decryptQueue.offer(
                        PendingFrame(header, body, stats, arrivalNanos, frameNonce),
                        DECRYPT_QUEUE_OFFER_WAIT_MILLIS, TimeUnit.MILLISECONDS,
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
                if (!queued) {
                    val dropped = droppedFrames.incrementAndGet()
                    if (dropped == 1L || dropped % 60L == 0L) {
                        onDiagnostic("video decrypt queue full; dropped frames=$dropped")
                    }
                }
            }
            OP_VIDEO_CONFIG -> {
                val (codec, codecData) = ScreenCodec.detectConfig(body)
                Log.i(TAG, "video codec config codec=$codec body=${body.size} data=${codecData.size}")
                listener.onCodec(codec)
                listener.onConfig(codecData)
            }
        }
    }

    /** Decrypts one queued video frame and delivers it; runs on the decrypt worker thread. */
    private fun decryptAndDeliver(pending: PendingFrame) {
        val header = pending.header
        val body = pending.body
        val stats = pending.stats
        val arrivalNanos = pending.arrivalNanos
        val payload = if (body.size >= ScreenCodec.TAG_SIZE) {
            val start = System.nanoTime()
            try {
                ScreenCodec.decryptFrame(key, pending.frameNonce, header, body)
                    .also {
                        stats.decrypted(System.nanoTime() - start, body.size)
                        decryptFailures.set(0)
                    }
            } catch (error: Exception) {
                // A single corrupt frame (transit bit error, upstream #180) must not kill the whole
                // screen stream: the per-frame nonce is already assigned, so skipping this frame
                // keeps us in sync with the iPhone and the stream survives. A systematic desync
                // still fails every frame, so cap consecutive skips as before.
                val consecutive = decryptFailures.incrementAndGet()
                Log.w(TAG, "video frame auth failed, skipping frame (consecutive=$consecutive, nonce=${pending.frameNonce})", error)
                onDiagnostic("video frame auth failed, skipped consecutive=$consecutive")
                if (consecutive >= MAX_CONSECUTIVE_DECRYPT_FAILURES) throw error
                return
            }
        } else {
            body
        }
        if (firstFrameLogged.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "video first decrypted frame sealed=${body.size} plain=${payload.size} " +
                    "head=${payload.hexPrefix(16)} chacha=${AirPlayCrypto.chachaImplementation}",
            )
        }
        listener.onFrame(ScreenCodec.lengthPrefixedToAnnexB(payload), ScreenCodec.senderNanos(header), arrivalNanos)
    }

    private fun readFully(input: InputStream, length: Int): ByteArray? {
        if (length < 0) return null
        val output = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = try {
                input.read(output, offset, length - offset)
            } catch (timeout: SocketTimeoutException) {
                onReadTimeout()
                continue
            }
            if (read < 0) return null
            offset += read
        }
        return output
    }

    /**
     * A read saw no data for [readTimeoutMillis]. Repeated timeouts
     * mean the iPhone stopped sending video: report the stall so the
     * engine can request a keyframe. The stream itself stays open -
     * measured stalls up to ~18 s clear on their own, and ending the
     * screen stream does not make the iPhone re-SETUP; it only tears
     * down the whole CarPlay session (t32.4 regression).
     */
    private fun onReadTimeout() {
        val now = System.nanoTime()
        if (lastPayloadNanos == 0L) {
            // Nothing has arrived since the stream was accepted; arm
            // the stall clock instead of reporting an epoch-sized gap.
            lastPayloadNanos = now
            return
        }
        val stalledMillis = (now - lastPayloadNanos) / 1_000_000
        if (stalledMillis < stallReportMillis) return
        if (stalledMillis - lastStallReportMillis < stallReportIntervalMillis) return
        lastStallReportMillis = stalledMillis
        Log.w(TAG, "video receive stalled ${stalledMillis}ms; requesting keyframe")
        onDiagnostic("video receive stalled ${stalledMillis}ms")
        listener.onReceiveStall(stalledMillis)
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val HEADER_LEN = 128
        const val OPCODE_OFFSET = 4
        const val OP_VIDEO_FRAME = 0
        const val OP_VIDEO_CONFIG = 1
        const val MAX_BODY = 8 * 1024 * 1024
        /** Per-read wait; shorter than any legitimate frame interval. */
        const val READ_TIMEOUT_MILLIS = 1_000
        /** Silence before a stall is reported (and a keyframe requested). */
        const val STALL_REPORT_MILLIS = 2_000L
        /** At most one stall report per interval. */
        const val STALL_REPORT_INTERVAL_MILLIS = 1_000L
        /**
         * Consecutive corrupt video frames tolerated before giving up: a transient error skips
         * one frame, a systematic nonce/key desync still ends the stream (upstream #180).
         */
        const val MAX_CONSECUTIVE_DECRYPT_FAILURES = 30
        /** Bounded decrypt backlog (upstream #249): at 30 fps this is ~0.3 s of video. */
        const val DECRYPT_QUEUE_CAPACITY = 10
        /** How long the receive loop waits for a decrypt slot before dropping a frame (#288). */
        const val DECRYPT_QUEUE_OFFER_WAIT_MILLIS = 120L
    }
}

private fun ByteArray.hexPrefix(length: Int): String =
    take(length).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Extracts the avcC/hvcC codec-data record from a VideoConfig payload. */
object ScreenCodec {
    /**
     * The frame time in a screen header: bytes 8..15, a little-endian NTP 32.32 value, in nanoseconds.
     * On a Tang with iOS 27 consecutive main-screen frames are exactly 1/60 s apart (1/30 s on the
     * cluster stream), and skipped frames leave whole multiples.
     */
    fun senderNanos(header: ByteArray): Long {
        if (header.size < SENDER_TIME_OFFSET + 8) return 0L
        var raw = 0L
        for (index in 7 downTo 0) raw = (raw shl 8) or (header[SENDER_TIME_OFFSET + index].toLong() and 0xff)
        return (raw ushr 32) * 1_000_000_000L + ((raw and 0xffff_ffffL) * 1_000_000_000L ushr 32)
    }

    private const val SENDER_TIME_OFFSET = 8

    fun decryptFrame(key: ByteArray, counter: Long, header: ByteArray, body: ByteArray): ByteArray =
        if (body.size < TAG_SIZE) body
        else AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(counter), body, header)

    /**
     * Replaces each four-byte NAL length with an Annex B start code in place.
     *
     * The payload is left untouched unless every length-prefixed NAL is valid, so malformed
     * input keeps its original bytes for the normal decoder error path.
     */
    fun lengthPrefixedToAnnexB(payload: ByteArray): ByteArray {
        if (payload.size < 4 || payload.startsWithStartCode()) return payload

        var offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            offset += 4
            if (length <= 0 || offset + length > payload.size) return payload
            offset += length
        }
        if (offset != payload.size) return payload

        offset = 0
        while (offset + 4 <= payload.size) {
            val length = readU32Be(payload, offset)
            payload[offset] = 0
            payload[offset + 1] = 0
            payload[offset + 2] = 0
            payload[offset + 3] = 1
            offset += 4 + length
        }
        return payload
    }

    fun detectConfig(payload: ByteArray): Pair<VideoCodec, ByteArray> {
        for (index in 4..payload.size - 4) {
            val fourcc = String(payload, index, 4, Charsets.US_ASCII)
            when (fourcc) {
                "hvcC" -> return VideoCodec.H265 to payload.copyOfRange(index + 4, payload.size)
                "avcC" -> return VideoCodec.H264 to payload.copyOfRange(index + 4, payload.size)
            }
        }
        return if (looksLikeAvcC(payload)) VideoCodec.H264 to payload else VideoCodec.H265 to payload
    }

    private fun looksLikeAvcC(payload: ByteArray): Boolean {
        if (payload.size < 9) return false
        if ((payload[5].toInt() and 0x1f) < 1) return false
        val spsLength = readU16Be(payload, 6)
        if (8 + spsLength > payload.size) return false
        return (payload[8].toInt() and 0x1f) == 7
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    const val TAG_SIZE = 16
}

private fun ByteArray.startsWithStartCode(): Boolean =
    size >= 4 &&
        this[0] == 0.toByte() &&
        this[1] == 0.toByte() &&
        this[2] == 0.toByte() &&
        this[3] == 1.toByte()

private fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

private fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)
