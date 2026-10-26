package com.shilapi.xcertplay.vehicle.runtime

import com.shilapi.xcertplay.vehicle.adapters.pioneer.PioneerAudioRouteAdapter
import com.shilapi.xcertplay.vehicle.api.AudioRouteController
import com.shilapi.xcertplay.vehicle.api.DefaultAudioRouteController

/** Detect the firmware once; media code consumes only the selected audio policy. */
object VehicleAudioRouting {
    val selected: AudioRouteController get() {
        val implementation = if (PioneerAudioRouteAdapter.available) PioneerAudioRouteAdapter else DefaultAudioRouteController
        return object : AudioRouteController by implementation {
            override fun selectMediaSource(report: (String) -> Unit) {
                VehiclePlaybackProtection.beforeSourceActivation(report)
                implementation.selectMediaSource(report)
            }
        }
    }
    val available: Boolean get() = selected.available
}
