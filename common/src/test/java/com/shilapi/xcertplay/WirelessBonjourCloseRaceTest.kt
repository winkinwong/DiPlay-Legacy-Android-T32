package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * closeWirelessStack runs the blocking JmDNS close in the background so the
 * reconnect is not delayed by it. The next wireless bring-up must wait for
 * that close before binding a fresh mDNS socket: binding while the old
 * JmDNS still holds port 5353 failed the bring-up with
 * "Interface mDNS could not bind on any advertised address" (t32.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessBonjourCloseRaceTest {
    private lateinit var controller: CarPlayController
    private val diagnostics = mutableListOf<String>()

    @Before fun setUp() {
        // Companionscope list: a stray thread from another test must not make the await lie.
        pendingThreads().clear()
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        val listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { diagnostics += message }
        }
        controller = CarPlayController(
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getApplicationContext(): Context = this
                override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
            },
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            config,
            identity,
            PairingStore(),
            listener,
            object : AirPlayMediaHandler {},
            {},
        )
        this.config = config
        this.identity = identity
    }

    private lateinit var config: AirPlayConfig
    private lateinit var identity: AirPlayIdentity

    @After fun tearDown() {
        controller.close()
        controller.awaitClosed(2_000)
        pendingThreads().clear()
    }

    @Test fun closeWirelessStackRegistersTheBackgroundBonjourCloseForTheNextBringUp() {
        val bonjour = CarPlayBonjour(
            context = RuntimeEnvironment.getApplication(),
            config = config,
            identity = identity,
            advertisedHost = "192.168.1.1",
            useInterfaceMdns = false,
            onEvent = {},
            additionalAddresses = emptyList(),
        )
        ReflectionHelpers.setField(controller, "bonjour", bonjour)
        invoke("closeWirelessStack", null, null)
        assertNull("closed Bonjour must be dropped at once", ReflectionHelpers.getField(controller, "bonjour"))
        val pending = pendingThreads()
        assertEquals("the background close must be registered as pending", 1, pending.size)
        assertTrue(awaitPendingBonjourClose(2_000L))
        assertTrue("after the await nothing may stay pending", pendingThreads().isEmpty())
    }

    @Test fun awaitJoinsAPendingCloseThreadAndASecondAwaitIsEmpty() {
        val closeCompleted = AtomicBoolean(false)
        val thread = Thread({
            Thread.sleep(300)
            closeCompleted.set(true)
        }, "xcertplay-bonjour-close").apply { isDaemon = true; start() }
        pendingThreads().add(thread)
        assertTrue("await must wait for the close to finish", awaitPendingBonjourClose(2_000L))
        assertTrue("the awaited close thread must have completed", closeCompleted.get())
        assertTrue("a second await with nothing pending succeeds", awaitPendingBonjourClose(2_000L))
    }

    /**
     * The regression that cost a full reconnect cycle after every settings change: a reconnect
     * builds a NEW controller, and a per-instance list left its bind racing the previous
     * instance's still-closing JmDNS.
     */
    @Test fun aNewControllerAwaitsThePreviousInstancesPendingClose() {
        val closeCompleted = AtomicBoolean(false)
        val thread = Thread({
            Thread.sleep(300)
            closeCompleted.set(true)
        }, "xcertplay-bonjour-close").apply { isDaemon = true; start() }
        pendingThreads().add(thread)
        val next = CarPlayController(
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getApplicationContext(): Context = this
                override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
            },
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            config,
            identity,
            PairingStore(),
            object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {},
            {},
        )
        try {
            assertTrue("the new instance must join the previous instance's close",
                awaitPendingBonjourCloseOn(next, 2_000L))
            assertTrue("the awaited close thread must have completed", closeCompleted.get())
            assertTrue("nothing may stay pending after the join", pendingThreads().isEmpty())
        } finally {
            next.close()
            next.awaitClosed(2_000)
        }
    }

    // Kotlin puts a private companion val on the owning class as a private static final field, so
    // the list is read through plain Java reflection on the class, not on an instance.
    @Suppress("UNCHECKED_CAST")
    private fun pendingThreads(): MutableList<Thread> {
        val field = CarPlayController::class.java.getDeclaredField("pendingBonjourCloses")
        field.isAccessible = true
        return field.get(null) as MutableList<Thread>
    }

    private fun awaitPendingBonjourClose(timeoutMillis: Long): Boolean = awaitPendingBonjourCloseOn(controller, timeoutMillis)

    private fun awaitPendingBonjourCloseOn(target: CarPlayController, timeoutMillis: Long): Boolean =
        target.javaClass
            .getDeclaredMethod("awaitPendingBonjourClose", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(target, timeoutMillis) as Boolean

    private fun invoke(name: String, vararg args: Any?) {
        val method = controller.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        method.invoke(controller, *args)
    }
}
