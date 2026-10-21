package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FloatingDotPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun cleanUp() {
        AirPlayPersistence.saveFloatingDot(context, false)
    }

    @Test
    fun loadWhenNeverSetReturnsFalse() {
        assertFalse(AirPlayPersistence.loadFloatingDot(context))
    }

    @Test
    fun saveFloatingDotRoundTrips() {
        AirPlayPersistence.saveFloatingDot(context, true)
        assertTrue(AirPlayPersistence.loadFloatingDot(context))
        AirPlayPersistence.saveFloatingDot(context, false)
        assertFalse(AirPlayPersistence.loadFloatingDot(context))
    }

    @Test
    fun setEnabledPersistsStateWithoutOverlayPermission() {
        // Robolectric denies Settings.canDrawOverlays by default, so the
        // dot cannot show here, but the setting itself must persist.
        FloatingDotOverlay.setEnabled(context, true)
        assertTrue(FloatingDotOverlay.isEnabled(context))
        assertFalse(FloatingDotOverlay.isShowing())
        FloatingDotOverlay.setEnabled(context, false)
        assertFalse(FloatingDotOverlay.isEnabled(context))
    }
}
