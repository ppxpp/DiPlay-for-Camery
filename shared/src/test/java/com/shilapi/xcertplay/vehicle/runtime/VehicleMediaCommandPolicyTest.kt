package com.shilapi.xcertplay.vehicle.runtime

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test

class VehicleMediaCommandPolicyTest {
    @Test fun matchesOnlyCompanionExplicitPlayback() {
        for (command in listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE)) {
            assertTrue(VehicleMediaCommandPolicy.isVehiclePlayback(command, "vehicle_companion"))
            for (source in listOf("pal", "unspecified", "carplay", "vehicle_companion_other"))
                assertFalse(VehicleMediaCommandPolicy.isVehiclePlayback(command, source))
        }
    }
    @Test fun trackChangesAndToggleRemainIndependent() {
        for (command in listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.PLAY_PAUSE, -1))
            assertFalse(VehicleMediaCommandPolicy.isVehiclePlayback(command, "vehicle_companion"))
    }
}
