package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.net.wifi.WifiConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerHotspotConfigurationTest {
    @Test fun firstUseStoresConfirmedCredentialsAndReusesThem() {
        var stored: WifiConfiguration? = null
        var writes = 0
        val write: (WifiConfiguration) -> Boolean = { stored = it; writes++; true }
        val first = PioneerHotspotConfiguration.ensure({ stored }, { 11 }, write)
        assertTrue(first.SSID.matches(Regex("Camery-[A-Za-z0-9]{4}")))
        assertTrue(first.preSharedKey.matches(Regex("[A-Za-z0-9]{16}")))
        assertTrue(first.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK))
        assertSame(first, PioneerHotspotConfiguration.ensure({ stored }, { 13 }, write))
        assertEquals(1, writes)
    }

    @Test fun existingCredentialsAreNotChanged() {
        val existing = WifiConfiguration().apply { SSID = "My car"; preSharedKey = "existing-password" }
        assertSame(existing, PioneerHotspotConfiguration.ensure({ existing }, { error("Must not query AP state") },
            { error("Must not overwrite saved credentials") }))
    }

    @Test fun incompleteConfigurationIsInitializedWhileStopped() {
        var stored: WifiConfiguration? = WifiConfiguration().apply { SSID = ""; preSharedKey = "short" }
        val result = PioneerHotspotConfiguration.ensure({ stored }, { 11 }, { stored = it; true })
        assertTrue(result.SSID.startsWith("Camery-"))
    }

    @Test fun runningOrTransitioningApIsNeverReconfigured() {
        for (state in listOf(10, 12, 13, 14)) {
            assertThrows(IllegalStateException::class.java) {
                PioneerHotspotConfiguration.ensure({ null }, { state }, { error("Must not write") })
            }
        }
    }

    @Test fun rejectedOrUnconfirmedWritesFailBeforeConnection() {
        assertThrows(IllegalStateException::class.java) {
            PioneerHotspotConfiguration.ensure({ null }, { 11 }, { false })
        }
        assertThrows(IllegalStateException::class.java) {
            PioneerHotspotConfiguration.ensure({ null }, { 11 }, { true })
        }
    }
}
