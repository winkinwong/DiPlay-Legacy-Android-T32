package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDisplaySettingsTest {
    @Test
    fun maximumObservedWidthScalesPhysicalSizeWithCurrentActivityWidth() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 768,
            currentHeightPixels = 800,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(80, size.widthMm)
        assertEquals(83, size.heightMm)
    }

    @Test
    fun maximumObservedHeightScalesPhysicalSizeWithCurrentActivityHeight() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 978,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 200,
            basis = AirPlayPhysicalSizeBasis.HEIGHT,
        )

        assertEquals(356, size.widthMm)
        assertEquals(181, size.heightMm)
    }

    @Test
    fun maximumResolutionKeepsConfiguredReferenceLength() {
        val size = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = 1920,
            currentHeightPixels = 1080,
            maximumWidthPixels = 1920,
            maximumHeightPixels = 1080,
            referenceMillimeters = 250,
            basis = AirPlayPhysicalSizeBasis.WIDTH,
        )

        assertEquals(250, size.widthMm)
        assertEquals(141, size.heightMm)
    }

    /**
     * The i.MX6 in the NTDA3 decodes a 768x432 frame in ~80 ms, so 15 fps can never be met and
     * every window reported "decoder too slow". 10 fps is offered for exactly that case.
     */
    @Test
    fun tenFramesPerSecondIsOfferedAndOutOfRangeValuesSnapToIt() {
        assertEquals(listOf(10, 15, 30, 45, 60), AirPlayDisplaySettings.FPS_OPTIONS)
        assertEquals(10, AirPlayDisplaySettings.MIN_FPS)
        assertEquals(10, AirPlayDisplaySettings.sanitizeFps(10))
        assertEquals(10, AirPlayDisplaySettings.sanitizeFps(12))
        assertEquals(10, AirPlayDisplaySettings.sanitizeFps(0))
        assertEquals(15, AirPlayDisplaySettings.sanitizeFps(15))
        assertEquals(30, AirPlayDisplaySettings.sanitizeFps(25))
        assertEquals(60, AirPlayDisplaySettings.sanitizeFps(999))
        assertEquals(0, AirPlayDisplaySettings.fpsProgress(10))
        assertEquals(1, AirPlayDisplaySettings.fpsProgress(15))
        assertEquals(2, AirPlayDisplaySettings.fpsProgress(30))
    }
}
