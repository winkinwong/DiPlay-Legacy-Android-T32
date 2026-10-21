package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }

    /**
     * A stall line names the symptom; the link health monitor names the cause. The report must
     * carry both, and a session without a monitor (wired, tests) must still log the stall itself.
     */
    @Test
    fun stallCarriesTheLinkHealthSnapshotAndSurvivesAMissingMonitor() {
        val logs = mutableListOf<String>()
        val session = testSession(videoInCar = true) { logs += it }
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val screen = ScreenStream(ByteArray(1) { 1 }, {}, session.localAddress)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = screen

        // Without a monitor the stall still reports, just without the link state.
        engine.handleReceiveStall(session, 110, screen, 12_000L)
        assertTrue(logs.any { it.contains("receive stalled 12000ms") })
        assertFalse(logs.any { it.contains("stall link state") })

        // With a monitor the stall line carries the counters and the radio level.
        engine.linkHealthProvider = { monitor }
        engine.handleReceiveStall(session, 110, screen, 12_000L)
        session.close()

        assertTrue("the link state must ride along: $logs", logs.any { it.contains("stall link state") })
        assertTrue(logs.any { it.contains("link health") && it.contains("radio=") })
        assertFalse("no interface name may leak", logs.any { it.contains("wlan0") })
    }

    private val monitor: com.shilapi.xcertplay.network.WirelessLinkHealthMonitor =
        com.shilapi.xcertplay.network.WirelessLinkHealthMonitor("wlan0", read = { path, _ ->
            when (path) {
                "/proc/net/wireless" -> "wlan0: 42 5 42 0 0 0 0 0 0"
                else -> "3"
            }
        }).also { it.stallSnapshot() }

    private fun testSession(videoInCar: Boolean = false, onDebugLog: (String) -> Unit = {}): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { onDebugLog(message) }
        },
        media = object : AirPlayMediaHandler {},
    )
}
