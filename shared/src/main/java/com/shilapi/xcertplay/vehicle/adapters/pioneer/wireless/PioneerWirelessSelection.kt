package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** The common Camry setting owns selection; stored ordinary modes survive force-on. */
object PioneerWirelessSelection {
    fun resolve(requested: WirelessHotspotMode, adaptationEnabled: Boolean): WirelessHotspotMode = when {
        adaptationEnabled -> WirelessHotspotMode.PIONEER_VEHICLE
        requested == WirelessHotspotMode.PIONEER_VEHICLE -> WirelessHotspotMode.MANUAL
        else -> requested
    }
}
