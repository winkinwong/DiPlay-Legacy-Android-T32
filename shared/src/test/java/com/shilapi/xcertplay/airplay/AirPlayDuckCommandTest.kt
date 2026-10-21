package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * duckAudio/unduckAudio commands arrive on the control channel: the session
 * must parse durationMs/volume and hand them to the media handler, and the
 * media engine must forward them to its sink (AndroidMediaSink lowers the
 * MEDIA AudioTracks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlayDuckCommandTest {
    private class RecordingMediaHandler : AirPlayMediaHandler {
        val ducks = mutableListOf<Pair<Long, Float>>()
        var unducks = 0
        override fun onDuckAudio(durationMs: Long, volume: Float) { ducks += durationMs to volume }
        override fun onUnduckAudio() { unducks++ }
    }

    private class RecordingSink : MediaSink {
        var duck: Pair<Long, Float>? = null
        var unducks = 0
        override fun duckMedia(durationMs: Long, volume: Float) { duck = durationMs to volume }
        override fun unduckMedia() { unducks++ }
    }

    @Test fun duckAudioCommandReachesTheMediaHandlerWithItsParameters() {
        val handler = RecordingMediaHandler()
        val session = session(handler)
        command(session, "duckAudio", mapOf("durationMs" to 1234L, "volume" to 0.25))
        assertEquals(listOf(1234L to 0.25f), handler.ducks)
        assertEquals(0, handler.unducks)
        command(session, "unduckAudio", emptyMap())
        assertEquals(1, handler.unducks)
        session.close()
    }

    @Test fun mediaEngineForwardsDuckCommandsToItsSink() {
        val sink = RecordingSink()
        val engine = CarPlayMediaEngine(sink)
        engine.onDuckAudio(1500, 0.2f)
        engine.onUnduckAudio()
        assertEquals(1500L to 0.2f, sink.duck)
        assertEquals(1, sink.unducks)
    }

    private fun session(handler: AirPlayMediaHandler): AirPlaySession {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
        return AirPlaySession(Socket(), config, identity, PairingStore(), null, object : AirPlaySessionListener {}, handler)
    }

    private fun command(session: AirPlaySession, type: String, params: Map<String, Any?>) {
        val body = BplistCodec.encode(mapOf("type" to type, "params" to params))
        val request = RtspMessage.Request("POST", "/ctrl-request/command", "RTSP/1.0", emptyMap(), body)
        session.javaClass.getDeclaredMethod("handleCommand", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session, request)
    }
}
