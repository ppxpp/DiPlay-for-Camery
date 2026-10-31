package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class PioneerWirelessSelectionTest {
    @Test fun enabledAdaptationAlwaysUsesVehicleWireless() {
        WirelessHotspotMode.entries.forEach {
            assertEquals(WirelessHotspotMode.PIONEER_VEHICLE,PioneerWirelessSelection.resolve(it,true))
        }
    }
    @Test fun disabledAdaptationPreservesOrdinaryWirelessChoices() {
        WirelessHotspotMode.entries.filter { it!=WirelessHotspotMode.PIONEER_VEHICLE }.forEach {
            assertEquals(it,PioneerWirelessSelection.resolve(it,false))
        }
        assertEquals(WirelessHotspotMode.MANUAL,PioneerWirelessSelection.resolve(WirelessHotspotMode.PIONEER_VEHICLE,false))
    }
}
