package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    qualifiers = "en-w900dp-h600dp-land",
    shadows = [HostSettingsDisconnectTest.Bootstrap::class],
)
class HostSettingsDisconnectTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clearSession() = CarPlayBackgroundSession.clear()

    @Test fun disconnectFromSettingsStopsTheSessionAndReturnsToDiPlayHome() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        val activity = controller.get()
        var stopped = false
        CarPlayBackgroundSession.store(
            mock(CarPlayController::class.java),
            mock(AndroidMediaSink::class.java),
            1, 1, activity,
            CarPlaySessionDisplay(1, 1, 0, false, false, 1, 1),
        ) { completion ->
            stopped = true
            completion()
        }

        CarPlayHostActivity::class.java.getDeclaredMethod("disconnectSession")
            .apply { isAccessible = true }.invoke(activity)

        assertTrue(stopped)
        val started = generateSequence { shadowOf(activity).nextStartedActivity }
            .take(20)
            .filter { it.component?.className == DiPlayActivity::class.java.name }
            .map { it.getStringExtra("page") }
            .toList()
        assertTrue(started.contains("home"))
        controller.pause().stop().destroy()
    }

    /** The projection screen must start without the private MFi identity used by real cars. */
    @Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    internal class Bootstrap {
        @Implementation fun ensure(context: Context) = Unit

        @Implementation fun deviceId(identity: AirPlayIdentity): String = "02:00:00:00:00:01"
    }
}
