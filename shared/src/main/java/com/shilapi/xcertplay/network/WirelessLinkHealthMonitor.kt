package com.shilapi.xcertplay.network

import java.io.IOException

/**
 * Link health for a running wireless CarPlay session.
 *
 * A video stall says frames stopped arriving; it cannot say why. This monitor keeps sampling the
 * kernel receive counters and the AP-side radio level so every stall line in the exported report
 * can carry the answer to the question that matters: did the wlan interface keep receiving while
 * the video stream starved (a receive path that never fed the stream), or did the radio go quiet
 * (the sender or the air, not the decoder)? The earlier build only sampled during bring-up, which
 * left the driver question unanswered for the stalls the driver actually feels.
 *
 * The radio level is the SoftAP side of the link, read from /proc/net/wireless: it needs no
 * WifiInfo (API 19 AP mode exposes none) and no new permission, and it is a count-free number.
 */
class WirelessLinkHealthMonitor(
    interfaceName: String?,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
    private val nowNs: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {    private val receiveDiagnostics = WirelessReceiveDiagnostics(interfaceName, read, nowNs)
    private val radioSource = CounterSource("radioLevel") {
        parseProcNetWireless(read("/proc/net/wireless", MAX_PROC_BYTES), interfaceName)
    }
    @Volatile private var lastSnapshot = "radioLevel=baseline"
    @Volatile private var closed = false
    private val worker = Thread(::observe, "diplay-wireless-link-health").apply { isDaemon = true }

    /** Starts the periodic sampling; the stall snapshot works with or without it. */
    fun start() {
        if (!closed && worker.state == Thread.State.NEW) worker.start()
    }

    /**
     * The snapshot a stall line carries: counter deltas plus the radio level, sampled on the
     * caller's thread so a stall never waits for the sampler's sleep.
     */
    fun stallSnapshot(): String = synchronized(worker) {
        lastSnapshot = capture()
        lastSnapshot
    }

    fun close() {
        if (closed) return
        closed = true
        worker.interrupt()
    }

    private fun observe() {
        try {
            while (!closed) {
                val snapshot = try { capture() } catch (error: Exception) {
                    "linkHealth=unavailable failureClass=${error.javaClass.simpleName}"
                }
                if (closed) return
                lastSnapshot = snapshot
                log(snapshot)
                Thread.sleep(intervalMillis)
            }
        } catch (_: InterruptedException) {
            // Teardown interrupts sleep; a monitor cannot fail the session.
        }
    }

    private fun capture(): String {
        // The receive diagnostics emit one line per source; keep the wlan counters, drop the
        // device-wide UDP counters (the video stream is what the stall is about).
        val ifaceLine = receiveDiagnostics.snapshot().lineSequence().firstOrNull { it.contains("ifaceRx=") }
        val iface = ifaceLine?.let { line ->
            Regex("ifaceRx\\w+Delta=\\d+").findAll(line).joinToString(" ") { it.value }
        }?.takeIf { it.isNotEmpty() } ?: "ifaceRx=unavailable"
        val radio = radioSource.snapshot()
        return "link health $iface radio=$radio"
    }

    companion object {
        const val INTERVAL_MILLIS = 10_000L
        private const val MAX_PROC_BYTES = 8 * 1024

        /**
         * The AP-side radio level from /proc/net/wireless. Signal is the driver's dBm and quality
         * the 0-15 scale; both are counted as numbers, never exported as link text.
         */
        internal fun parseProcNetWireless(text: String, interfaceName: String?): Map<String, Long> {
            val name = interfaceName ?: throw IOException()
            val rows = text.lineSequence()
                .map { line -> line.split(':').takeIf { it.size == 2 }?.let { it[0].trim() to it[1] } }
                .filterNotNull()
                .toList()
            val row = rows.firstOrNull { it.first == name } ?: throw IOException()
            val values = row.second.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            // status | quality | level | noise | discarded packets ...
            if (values.size < 4) throw IOException()
            fun field(index: Int): Long = values[index].removeSuffix(".").toLongOrNull() ?: throw IOException()
            return linkedMapOf(
                "Status" to field(0),
                "Quality" to field(1),
                "SignalDbm" to field(2),
                "NoiseDbm" to field(3),
            )
        }
    }
}
