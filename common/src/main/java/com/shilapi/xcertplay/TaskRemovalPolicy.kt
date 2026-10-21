package com.shilapi.xcertplay

/**
 * What removing the DiPlay task from the head unit's recents must do to a running CarPlay session.
 *
 * BYD's recents force-stop the package ~10 ms after removing the task, so there the session has to
 * end cleanly before the kill lands. Every other head unit keeps the process alive: tearing the
 * session down there only forces the next launch to pay a full reconnect. On the HSAE NTDA3 the
 * system removes the task every ~30 s while driving, which froze the picture until DiPlay
 * reconnected from scratch (hotspot + RFCOMM + iAP2 + AirPlay SETUP, tens of seconds of black).
 */
internal object TaskRemovalPolicy {
    enum class Action { TEAR_DOWN, KEEP_ALIVE }

    fun actionFor(isBydFirmware: Boolean): Action =
        if (isBydFirmware) Action.TEAR_DOWN else Action.KEEP_ALIVE
}
