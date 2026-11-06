package com.shilapi.xcertplay.pioneer

import android.app.Activity
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PioneerMediaControlSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences get() = app.getSharedPreferences("pioneer-media-control", Context.MODE_PRIVATE)

    @Before fun setup() { preferences.edit().clear().commit() }
    @After fun cleanup() { preferences.edit().clear().commit() }

    @Test fun freshInstallDefaultsOnWithoutSavingAnImplicitChoice() {
        repeat(2) { assertTrue(PioneerMediaControlSettings.acceptsPlayback(app)) }
        assertTrue(preferences.all.isEmpty())
    }

    @Test fun explicitOffAndOnPersistAcrossActivityRecreation() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            PioneerMediaControlSettings.setAcceptsPlayback(activity.get(), false)
            assertEquals(false, preferences.all["accept_vehicle_play_pause"])
            activity.recreate()
            assertFalse(PioneerMediaControlSettings.acceptsPlayback(activity.get()))
            assertFalse(PioneerMediaControlSettings.acceptsPlayback(app))

            PioneerMediaControlSettings.setAcceptsPlayback(activity.get(), true)
            assertEquals(true, preferences.all["accept_vehicle_play_pause"])
            activity.recreate()
            assertTrue(PioneerMediaControlSettings.acceptsPlayback(activity.get()))
            assertTrue(PioneerMediaControlSettings.acceptsPlayback(app))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun persistedChoiceIsReadLiveAndReadsNeverReplaceItWithDefault() {
        for (enabled in listOf(false, true, false)) {
            assertTrue(preferences.edit().putBoolean("accept_vehicle_play_pause", enabled).commit())
            repeat(2) { assertEquals(enabled, PioneerMediaControlSettings.acceptsPlayback(app)) }
            assertEquals(mapOf("accept_vehicle_play_pause" to enabled), preferences.all)
        }
    }
}
