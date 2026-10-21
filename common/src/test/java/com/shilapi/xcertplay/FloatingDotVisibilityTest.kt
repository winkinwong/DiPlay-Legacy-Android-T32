package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floating dot must never draw over CarPlay or the lock screen — the two places the driver
 * reported seeing it — and only appears when it is enabled and overlay permission allows it.
 */
class FloatingDotVisibilityTest {
    @Test fun showsOnlyWhenEnabledPermittedAndUnopposed() {
        assertTrue(FloatingDotOverlay.shouldShowDot(
            enabled = true, permitted = true, carPlayVisible = false, screenSuppressed = false))
    }

    @Test fun hiddenWhileCarPlayIsOnScreen() {
        assertFalse(FloatingDotOverlay.shouldShowDot(
            enabled = true, permitted = true, carPlayVisible = true, screenSuppressed = false))
    }

    @Test fun hiddenWhileLockedOrScreenOff() {
        assertFalse(FloatingDotOverlay.shouldShowDot(
            enabled = true, permitted = true, carPlayVisible = false, screenSuppressed = true))
    }

    @Test fun hiddenWhenDisabledOrWithoutOverlayPermission() {
        assertFalse(FloatingDotOverlay.shouldShowDot(
            enabled = false, permitted = true, carPlayVisible = false, screenSuppressed = false))
        assertFalse(FloatingDotOverlay.shouldShowDot(
            enabled = true, permitted = false, carPlayVisible = false, screenSuppressed = false))
    }
}
