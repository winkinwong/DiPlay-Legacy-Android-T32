package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The X-Trail is the Wi-Fi STA on an external hotspot: without a high-performance Wi-Fi lock the
 * driver dozes between video bursts, the AP buffers frames, and the picture freezes for seconds
 * although nothing was lost (2026-11-02 field log). The lock must follow the wireless stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerWifiLockTest {
    private val controllers = mutableListOf<CarPlayController>()
    private val application = RuntimeEnvironment.getApplication()

    @After fun tearDown() {
        controllers.forEach { it.close() }
        controllers.clear()
    }

    @Test fun theWirelessLockFollowsAcquireAndRelease() {
        val lock = newLock()
        val controller = controller(lock)
        assertFalse("no lock before wireless starts", lock.isHeld)
        controller.acquireHighPerfWifiLock()
        assertTrue("held during wireless CarPlay", lock.isHeld)
        controller.acquireHighPerfWifiLock()
        assertTrue("a second acquire keeps holding", lock.isHeld)
        controller.releaseHighPerfWifiLock()
        assertFalse("released when the wireless stack closes", lock.isHeld)
        controller.releaseHighPerfWifiLock()
        assertFalse("releasing twice is harmless", lock.isHeld)
    }

    /** Some head units return no Wi-Fi service; the guard must be a no-op, not a crash. */
    @Test fun aMissingLockProviderIsHarmless() {
        val controller = controller(lock = null)
        controller.acquireHighPerfWifiLock()
        controller.releaseHighPerfWifiLock()
    }

    private fun newLock(): WifiManager.WifiLock =
        (application.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carplay-wireless-test")

    private fun controller(lock: WifiManager.WifiLock?): CarPlayController =
        CarPlayController(
            application,
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            PairingStore(),
            object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {},
            { _ -> },
        ).also {
            it.highPerfWifiLockProvider = { lock }
            controllers += it
        }
}
