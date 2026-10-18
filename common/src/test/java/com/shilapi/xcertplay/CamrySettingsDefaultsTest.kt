package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33], manifest = Config.NONE)
class CamrySettingsDefaultsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshInstallReports250Millimeters() {
        assertEquals(250, AirPlayPersistence.loadWidthPhysicalMm(context))
    }

    @Test fun explicitScreenSizeSurvivesRepeatedDefaultReads() {
        AirPlayPersistence.saveWidthPhysicalMm(context, 350)
        repeat(2) {
            assertEquals(350, AirPlayPersistence.loadWidthPhysicalMm(context))
        }
    }

    @Test fun freshHotspotHasNoPersonalCredentials() {
        assertEquals("", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(context))
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(context))
    }
}
