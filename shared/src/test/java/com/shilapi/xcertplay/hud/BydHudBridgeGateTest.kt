package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Issue #4 (2026-11-10): on a Nissan X-Trail (no BYD SOME/IP gateway) the HUD bridge still started
 * its 300 ms pump and retried bindService() forever. Without the gateway package nothing may start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydHudBridgeGateTest {
    @Test
    fun aDeviceWithoutTheGatewayNeverStartsTheHudPump() {
        BydHudBridge.initialize(RuntimeEnvironment.getApplication())
        assertFalse("no SOME/IP gateway means no background bind retries", BydHudBridge.pumpStarted)
    }
}
