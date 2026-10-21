package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Steering-wheel key handling must write to the exported diagnostic report, not only to logcat:
 * the reports users submit capture the controller's report stream alone, so wheel-key failures
 * stayed invisible (issue #3). These run on a modern SDK and drive the legacy API 19 broadcast body
 * directly through [CarPlayMediaKeys.handleLegacyMediaButton], which carries no SDK_INT guard, so
 * the KitKat path the HSAE head unit actually uses is covered without a KitKat Robolectric runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaKeyReportTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        controllers.clear()
        CarPlayBackgroundSession.clear()
    }

    @Test fun aLegacyWheelKeyReachesTheReportAndCarPlay() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaKeys.attach(app, controller)

        CarPlayMediaKeys.handleLegacyMediaButton(mediaButton(KeyEvent.KEYCODE_MEDIA_NEXT))

        // Arrival is logged before filtering (the raw keyCode is device-independent), the forward is
        // reported with its result, and CarPlay receives the HID press.
        verify(controller).mediaKeyDiagnostic(contains("legacy RCC receiver keyCode=87"))
        verify(controller).mediaKeyDiagnostic(contains("-> CarPlay"))
        verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
    }

    @Test fun aNonMediaKeyIsLoggedAsArrivedButNotForwarded() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        CarPlayMediaKeys.attach(app, controller)

        CarPlayMediaKeys.handleLegacyMediaButton(mediaButton(KeyEvent.KEYCODE_VOLUME_UP))

        // The report still shows the head unit delivered the key (raw keyCode 24), but CarPlay is not told.
        verify(controller).mediaKeyDiagnostic(contains("legacy RCC receiver keyCode=24"))
        verify(controller, never()).sendMediaButton(anyInt())
    }

    private fun mediaButton(keyCode: Int) = Intent(Intent.ACTION_MEDIA_BUTTON)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0))
}
