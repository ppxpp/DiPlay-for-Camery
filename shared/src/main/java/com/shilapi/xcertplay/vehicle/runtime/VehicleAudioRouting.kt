package com.shilapi.xcertplay.vehicle.runtime

import com.shilapi.xcertplay.vehicle.adapters.pioneer.PioneerAudioRouteAdapter
import com.shilapi.xcertplay.vehicle.api.AudioRouteController
import com.shilapi.xcertplay.vehicle.api.DefaultAudioRouteController

/** Detect the firmware once; media code consumes only the selected audio policy. */
object VehicleAudioRouting {
    private fun protectedRoute(implementation: AudioRouteController): AudioRouteController =
        object : AudioRouteController by implementation {
            override fun selectMediaSource(report: (String) -> Unit) {
                VehiclePlaybackProtection.beforeSourceActivation(report)
                implementation.selectMediaSource(report)
            }
        }

    private val pioneerRoute = protectedRoute(PioneerAudioRouteAdapter)
    private val defaultRoute = protectedRoute(DefaultAudioRouteController)
    // Reuse wrappers on the PCM write path, while still honoring adaptation changes.
    val selected: AudioRouteController
        get() = if (PioneerAudioRouteAdapter.available) pioneerRoute else defaultRoute
    val available: Boolean get() = selected.available
}
