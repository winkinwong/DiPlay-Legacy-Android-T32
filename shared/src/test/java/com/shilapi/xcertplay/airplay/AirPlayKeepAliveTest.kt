package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AirPlay keepalive is an echo: the receiver answers every probe with the same payload, and only
 * an echoing receiver may advertise keepAliveLowPower. The HSAE NTDA3 stalls and the peer-initiated
 * teardowns trace back to advertising false while the iPhone asked for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirPlayKeepAliveTest {
    private fun session(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )

    @Test fun aProbeIsEchoedBackWithItsOwnPayload() {
        val session = session()
        try {
            val address = InetAddress.getByName("192.168.43.2")
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val probe = DatagramPacket(payload, payload.size, address, 7010)
            val sent = mutableListOf<DatagramPacket>()

            session.echoKeepAlive(probe) { sent += it }

            assertEquals("exactly one echo per probe", 1, sent.size)
            assertEquals(address, sent[0].address)
            assertEquals(7010, sent[0].port)
            assertEquals(4, sent[0].length)
            assertArrayEquals(payload, sent[0].data.copyOfRange(0, sent[0].length))
        } finally {
            session.close()
        }
    }

    @Test fun anEmptyProbeIsNotEchoed() {
        val session = session()
        try {
            val sent = mutableListOf<DatagramPacket>()
            session.echoKeepAlive(DatagramPacket(ByteArray(4), 0, InetAddress.getByName("192.168.43.2"), 7010)) {
                sent += it
            }
            assertTrue("a zero-length probe carries no echo", sent.isEmpty())
        } finally {
            session.close()
        }
    }

    @Test fun aFailedEchoDoesNotEscape() {
        val session = session()
        try {
            val probe = DatagramPacket(
                byteArrayOf(0x09), 1, InetAddress.getByName("192.168.43.2"), 7010,
            )
            // A send failure must not break the receive loop that calls this.
            session.echoKeepAlive(probe) { error("socket gone") }
        } finally {
            session.close()
        }
    }

    @Test fun theAdvertisedInfoAllowsKeepAliveLowPower() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
        )
        assertEquals(
            "an echoing receiver must advertise the low-power keepalive",
            true,
            info["keepAliveLowPower"],
        )
    }
}
