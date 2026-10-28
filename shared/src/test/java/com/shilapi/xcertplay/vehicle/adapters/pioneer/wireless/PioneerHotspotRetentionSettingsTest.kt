package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],manifest=Config.NONE)
class PioneerHotspotRetentionSettingsTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() { app.getSharedPreferences("diplay_car_hotspot",Context.MODE_PRIVATE).edit().clear().commit() }
    @Test fun defaultIsThirtySeconds() { assertEquals(30,PioneerHotspotRetention.seconds(app)) }
    @Test fun savedDelaySurvivesAnotherReadAndClampsToRange() {
        PioneerHotspotRetention.setSeconds(app,12)
        assertEquals(12,PioneerHotspotRetention.seconds(app))
        PioneerHotspotRetention.setSeconds(app,-1)
        assertEquals(0,PioneerHotspotRetention.seconds(app))
        PioneerHotspotRetention.setSeconds(app,61)
        assertEquals(60,PioneerHotspotRetention.seconds(app))
    }
}
