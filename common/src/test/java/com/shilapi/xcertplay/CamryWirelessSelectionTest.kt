package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.pioneer.CamryAdaptation
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],manifest=Config.NONE)
class CamryWirelessSelectionTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @After fun reset() { CamryAdaptation.save(context,CamryAdaptation.Mode.AUTO) }
    @Test fun forceOnOverridesWirelessModeWithoutOverwritingTheOrdinaryChoice() {
        CamryAdaptation.save(context,CamryAdaptation.Mode.OFF)
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.WIFI_P2P)
        CamryAdaptation.save(context,CamryAdaptation.Mode.ON)
        assertEquals(WirelessHotspotMode.PIONEER_VEHICLE,AirPlayPersistence.loadWirelessHotspotMode(context))
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.PIONEER_VEHICLE) // Saving other host settings.
        CamryAdaptation.save(context,CamryAdaptation.Mode.OFF)
        assertEquals(WirelessHotspotMode.WIFI_P2P,AirPlayPersistence.loadWirelessHotspotMode(context))
    }
    @Test fun disabledAdaptationCannotSelectVehicleBackend() {
        CamryAdaptation.save(context,CamryAdaptation.Mode.OFF)
        AirPlayPersistence.saveWirelessHotspotMode(context,WirelessHotspotMode.PIONEER_VEHICLE)
        assertEquals(WirelessHotspotMode.MANUAL,AirPlayPersistence.loadWirelessHotspotMode(context))
    }
}
