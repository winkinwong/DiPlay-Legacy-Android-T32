package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.usb.IpodServiceStopper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayControllerIpodGuardTest {
    private val controllers = mutableListOf<CarPlayController>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
    }

    @Test fun iphoneDiscoveryStopsTheIpodServiceWhenEnabled() {
        val (stopper, killer, sleeper) = recordingGuard()
        val controller = controller(stopIpodOnUsb = true)
        controller.ipodServiceStopper = stopper
        controller.maybeStopIpodService()
        // The kill succeeds in this environment, so the stop finishes on the first round: no retry
        // delay is expected (a successful stop must not wait), and the guard still runs off the
        // caller's thread via the worker pool.
        Thread.sleep(900)
        assertEquals(listOf(IpodServiceStopper.IPOD_PACKAGE), killer.killedPackages)
    }

    @Test fun iphoneDiscoverySkipsTheIpodServiceWhenDisabled() {
        val (stopper, killer, sleeper) = recordingGuard()
        val controller = controller(stopIpodOnUsb = false)
        controller.ipodServiceStopper = stopper
        controller.maybeStopIpodService()
        Thread.sleep(300)
        assertEquals("a disabled guard must not stop anything", 0, killer.killedPackages.size)
        assertTrue(sleeper.delays.isEmpty())
    }

    /** A real stopper wired to recording fakes; no subclassing needed. */
    private fun recordingGuard(): Triple<IpodServiceStopper, RecordingKiller, RecordingSleeper> {
        val killer = RecordingKiller()
        val sleeper = RecordingSleeper()
        val stopper = IpodServiceStopper(
            backgroundKiller = killer,
            sleeper = { millis -> sleeper.record(millis) },
        )
        return Triple(stopper, killer, sleeper)
    }

    private fun controller(stopIpodOnUsb: Boolean): CarPlayController =
        CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
                stopIpodOnUsb = stopIpodOnUsb,
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            PairingStore(),
            object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {},
            { _ -> },
        ).also(controllers::add)

    private class RecordingKiller : IpodServiceStopper.BackgroundProcessKiller {
        val killedPackages = mutableListOf<String>()
        override fun kill(packageName: String): Boolean {
            killedPackages += packageName
            return true
        }
    }

    private class RecordingSleeper {
        private val firstDelay = CountDownLatch(1)
        val delays = mutableListOf<Long>()
        fun record(millis: Long) {
            delays += millis
            firstDelay.countDown()
        }
        fun awaitFirstDelay(timeoutMillis: Long): Boolean =
            firstDelay.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
