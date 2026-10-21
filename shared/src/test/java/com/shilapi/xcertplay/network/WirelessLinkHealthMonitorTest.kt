package com.shilapi.xcertplay.network

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/**
 * The stall line carries the monitor's snapshot, so the snapshot must answer the one question a
 * stall raises: did the radio keep receiving while the video stream starved (a receive path that
 * never fed the stream), or did the radio go quiet (the sender or the air)? Every sample is
 * counted and bounded; no link text or file content reaches the exported report.
 */
class WirelessLinkHealthMonitorTest {
    private fun wireless(signalDbm: Int, quality: Int) =
        "wlan0: $signalDbm $quality $signalDbm 0 0 0 0 0 0"

    @Test fun stallSnapshotCarriesInterfaceCountersAndTheRadioLevel() {
        var counter = 5L
        var clock = 0L
        var signal = 42
        var quality = 5
        val monitor = WirelessLinkHealthMonitor("wlan0", read = { path, _ ->
            when (path) {
                "/proc/net/wireless" -> wireless(signalDbm = signal, quality = quality)
                else -> counter.toString()
            }
        }, nowNs = { clock })
        monitor.stallSnapshot() // baseline
        counter = 10
        signal = 48 // the radio level moved between samples
        quality = 6
        clock = 10_000_000_000L
        val snapshot = monitor.stallSnapshot()
        assertTrue("the wlan counters must be on the stall line: $snapshot", snapshot.contains("ifaceRxPacketsDelta=5"))
        assertTrue("the radio level must be on the stall line: $snapshot", snapshot.contains("radioLevelSignalDbmDelta=6"))
        assertTrue(snapshot.contains("radioLevelQualityDelta=1"))
        assertFalse("no link text may leak", snapshot.contains("wlan0"))
    }

    @Test fun aQuietRadioDuringAStallShowsZeroSignalDelta() {
        var counter = 1L
        var clock = 0L
        val monitor = WirelessLinkHealthMonitor("wlan0", read = { path, _ ->
            when (path) {
                "/proc/net/wireless" -> wireless(signalDbm = 42, quality = 5)
                else -> counter.toString()
            }
        }, nowNs = { clock })
        monitor.stallSnapshot()
        counter = 2
        clock = 10_000_000_000L
        // The kernel counters keep moving while the radio level does not: the receive path is
        // alive and the sender is what stopped.
        val snapshot = monitor.stallSnapshot()
        assertTrue(snapshot.contains("ifaceRxPacketsDelta=1"))
        assertTrue(snapshot.contains("radioLevelSignalDbmDelta=0"))
    }

    @Test fun anUnreadableRadioLevelSaysSoWithoutFailingTheStall() {
        val monitor = WirelessLinkHealthMonitor("wlan0", read = { path, _ ->
            if (path == "/proc/net/wireless") throw IOException("hidden by OEM policy")
            "7"
        })
        val snapshot = monitor.stallSnapshot()
        assertTrue("the wlan counters still answer: $snapshot", snapshot.contains("ifaceRx="))
        assertTrue(snapshot.contains("radio=radioLevel=unavailable"))
        assertFalse(snapshot.contains("hidden"))
    }

    @Test fun aMissingInterfaceIsReportedAsUnavailable() {
        val monitor = WirelessLinkHealthMonitor(null, read = { _, _ -> "" })
        val snapshot = monitor.stallSnapshot()
        assertTrue(snapshot.contains("radio=radioLevel=unavailable"))
    }
}
