package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.*
import org.junit.Test

class HotspotHelperMetricsTest {
    @Test fun onlyTheFixedMachineProtocolIsAccepted() {
        val parsed = HotspotHelperMetrics.parse("""
DIPLAY_METRIC phase=oem_ap_start durationMs=123 result=complete
localOnly=timing phase=frequency elapsedMs=456
DIPLAY_METRIC phase=ssid_secret durationMs=123 result=complete
DIPLAY_METRIC phase=frequency durationMs=-1 result=failed
DIPLAY_METRIC phase=frequency durationMs=900000000000000000000 result=failed
DIPLAY_METRIC phase=frequency durationMs=456 result=failed
""".trimIndent())
        assertEquals(listOf("oem_ap_start", "frequency"), parsed.map { it.phase })
        assertEquals(listOf(123L, 456L), parsed.map { it.millis })
    }
}
