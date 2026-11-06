package com.shilapi.xcertplay.vehicle.runtime

import com.shilapi.xcertplay.airplay.CarPlayMediaButton

/** Only explicit playback commands from the vehicle bridge are controlled by this setting. */
internal object VehicleMediaCommandPolicy {
    fun isVehiclePlayback(index: Int, source: String): Boolean =
        source == "vehicle_companion" &&
            (index == CarPlayMediaButton.PLAY || index == CarPlayMediaButton.PAUSE)

}
