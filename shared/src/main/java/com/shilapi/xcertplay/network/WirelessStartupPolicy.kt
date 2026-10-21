package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    // Measured on the HSAE NTDA3 (2026-10-18): the iPhone took 20.7 s, 26.7 s and 30.7 s to
    // open the AirPlay TCP after StartSession, so 30 s timed out just before the slowest one and
    // the wasted attempt rebuilt the whole stack. 60 s covers every observed cold/warm start.
    const val FIRST_TCP_MILLIS = 60_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure { HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION }

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
