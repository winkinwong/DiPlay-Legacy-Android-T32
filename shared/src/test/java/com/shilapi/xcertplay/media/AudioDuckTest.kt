package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject

/**
 * duckAudio/unduckAudio: while guidance speaks, the iPhone asks the receiver
 * to lower the music stream. Only the MEDIA channel may change, the volume
 * restores on unduck or after the duck duration, and a second duck must keep
 * the pre-duck volume as its restore base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [AudioDuckTest.VolumeTrackingAudioTrack::class])
class AudioDuckTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private val tracks = mutableListOf<AudioTrack>()
    private val coordinators = mutableListOf<AudioFocusCoordinator>()

    @Before fun reset() {
        VolumeTrackingAudioTrack.volumes.clear()
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        coordinators.forEach { coordinator -> tracks.forEach(coordinator::release) }
        tracks.forEach(AudioTrack::release)
        VolumeTrackingAudioTrack.volumes.clear()
    }

    @Test fun duckLowersMediaOnlyAndUnduckRestores() {
        val coordinator = coordinator()
        val media = track(); val navigation = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.acquire(navigation, AudioChannel.NAVIGATION, attributes)
        coordinator.duckMedia(1_000, 0.25f)
        assertEquals(listOf(0.25f), volumes(media))
        assertTrue("navigation must not duck", volumes(navigation).isEmpty())
        coordinator.unduckMedia()
        assertEquals(listOf(0.25f, 1f), volumes(media))
        assertTrue("navigation must stay untouched", volumes(navigation).isEmpty())
    }

    /** Audio focus is opt-in and off by default; duck must still lower the music in that state. */
    @Test fun duckLowersMediaEvenWhenAudioFocusIsOff() {
        val coordinator = coordinator(enabled = false)
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.duckMedia(1_000, 0.2f)
        assertEquals("duck must lower the music with audio focus off", listOf(0.2f), volumes(media))
        coordinator.unduckMedia()
        assertEquals(listOf(0.2f, 1f), volumes(media))
    }

    @Test fun duckRestoresAfterItsDurationWithoutAnUnduckCommand() {
        val coordinator = coordinator()
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.duckMedia(500, 0.3f)
        assertEquals(listOf(0.3f), volumes(media))
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        assertEquals("duration elapsed, the pre-duck volume must return", listOf(0.3f, 1f), volumes(media))
    }

    @Test fun secondDuckKeepsTheOriginalRestoreBaseAndReplacesTheTimer() {
        val coordinator = coordinator()
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.duckMedia(2_000, 0.5f)
        coordinator.duckMedia(300, 0.25f)
        assertEquals(listOf(0.5f, 0.25f), volumes(media))
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals("second duck restores to the pre-duck volume", listOf(0.5f, 0.25f, 1f), volumes(media))
        shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        assertEquals("the replaced timer must not restore twice", listOf(0.5f, 0.25f, 1f), volumes(media))
    }

    /**
     * The iPhone sends duckAudio with volume=0.0 (measured 45/45 times on the 2026-10-22 USB
     * session). Applying 0.0 silences the music for the whole announcement, which the driver
     * hears as "the music stutters during guidance" instead of "the music was lowered".
     * A zero request must clamp to [DUCK_FLOOR] so music stays audible underneath.
     */
    @Test fun aZeroVolumeRequestDucksToTheFloorInsteadOfSilence() {
        val coordinator = coordinator()
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.duckMedia(500, 0f)
        assertEquals(
            "volume=0 must duck to the audible floor, never to silence",
            listOf(AudioFocusCoordinator.DUCK_FLOOR),
            volumes(media),
        )
        assertTrue("the floor must stay audible", AudioFocusCoordinator.DUCK_FLOOR > 0f)
        coordinator.unduckMedia()
        assertEquals(listOf(AudioFocusCoordinator.DUCK_FLOOR, 1f), volumes(media))
    }

    /** A volume below the floor is lifted to it; a higher one is honoured as asked. */
    @Test fun onlySubFloorVolumesAreRaisedToTheFloor() {
        val coordinator = coordinator()
        val media = track()
        coordinator.acquire(media, AudioChannel.MEDIA, attributes)
        coordinator.duckMedia(500, 0.05f)
        coordinator.duckMedia(500, 0.6f)
        assertEquals(
            "0.05 clamps to the floor, 0.6 is kept",
            listOf(AudioFocusCoordinator.DUCK_FLOOR, 0.6f),
            volumes(media),
        )
    }

    private fun coordinator(enabled: Boolean = true, autoYield: Boolean = true) =
        AudioFocusCoordinator(context, enabled, autoYield).also(coordinators::add)
    private fun track(): AudioTrack = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build().also(tracks::add)
    private fun volumes(track: AudioTrack): List<Float> = VolumeTrackingAudioTrack.volumes[track].orEmpty()

    @Implements(AudioTrack::class)
    class VolumeTrackingAudioTrack {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun setStereoVolume(left: Float, right: Float): Int {
            volumes.getOrPut(track) { mutableListOf() }.add(left)
            return AudioTrack.SUCCESS
        }
        companion object { val volumes = linkedMapOf<AudioTrack, MutableList<Float>>() }
    }
}
