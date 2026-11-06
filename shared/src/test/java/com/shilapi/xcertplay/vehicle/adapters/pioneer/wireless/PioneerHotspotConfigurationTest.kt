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
    @Test fun existingSystemCredentialsAreBackedUpWithoutReconfiguring() {
        val current = WifiConfiguration().apply { SSID = "My car"; preSharedKey = "existing-password" }
        var backup: PioneerHotspotConfiguration.Credentials? = null
        assertSame(current, PioneerHotspotConfiguration.ensure({ current }, { error("Must not query state") },
            { error("Must not reconfigure") }, save = { backup = it }))
        assertEquals(PioneerHotspotConfiguration.Credentials(current.SSID, current.preSharedKey), backup)
    }

    @Test fun lostOrIncompleteSystemConfigurationRestoresBothSavedValues() {
        val backup = PioneerHotspotConfiguration.Credentials("Camery-saved", "saved-password-123")
        for (initial in listOf(null, WifiConfiguration().apply { SSID = "Other name"; preSharedKey = "" })) {
            var stored = initial
            val result = PioneerHotspotConfiguration.ensure({ stored }, { 11 }, { stored = it; true },
                loadSaved = { backup })
            assertEquals(backup.ssid, result.SSID)
            assertEquals(backup.passphrase, result.preSharedKey)
        }
    }

    @Test fun validSystemChangeUpdatesBackupInsteadOfRestoringOldCredentials() {
        val current = WifiConfiguration().apply { SSID = "Renamed by driver"; preSharedKey = "new-password" }
        var backup = PioneerHotspotConfiguration.Credentials("Old name", "old-password")
        PioneerHotspotConfiguration.ensure({ current }, { error("Must not query state") },
            { error("Must not overwrite driver changes") }, { backup }, { backup = it })
        assertEquals(current.SSID, backup.ssid)
        assertEquals(current.preSharedKey, backup.passphrase)
    }

    @Test fun failedSystemWriteDoesNotChangeGeneratedCredentialsOnRetry() {
        var backup: PioneerHotspotConfiguration.Credentials? = null
        var attempted: WifiConfiguration? = null
        assertThrows(IllegalStateException::class.java) {
            PioneerHotspotConfiguration.ensure({ null }, { 11 }, {
                attempted = it
                assertEquals(it.SSID, backup!!.ssid) // Backup must be committed before touching the system.
                assertEquals(it.preSharedKey, backup!!.passphrase)
                false
            }, { backup }, { backup = it })
        }
        val first = backup!!
        var stored: WifiConfiguration? = null
        val recovered = PioneerHotspotConfiguration.ensure({ stored }, { 11 }, { stored = it; true },
            { backup }, { backup = it })
        assertEquals(first.ssid, recovered.SSID)
        assertEquals(first.passphrase, recovered.preSharedKey)
        assertEquals(attempted!!.preSharedKey, recovered.preSharedKey)
    }

    @Test fun activeHotspotIsNotReconfiguredEvenWhenBackupExists() {
        val backup = PioneerHotspotConfiguration.Credentials("Saved", "saved-password")
        for (state in listOf(10, 12, 13, 14)) {
            assertThrows(IllegalStateException::class.java) {
                PioneerHotspotConfiguration.ensure({ null }, { state }, { error("Must not write") },
                    { backup }, { error("Must not replace backup") })
            }
        }
    }

    @Test fun invalidBackupIsReplacedOnceBeforeWritingSystemConfiguration() {
        var stored: WifiConfiguration? = null
        var backup = PioneerHotspotConfiguration.Credentials("", "short")
        val created = PioneerHotspotConfiguration.ensure({ stored }, { 11 }, {
            assertEquals(it.SSID, backup.ssid)
            assertEquals(it.preSharedKey, backup.passphrase)
            stored = it; true
        }, { backup }, { backup = it })
        assertTrue(created.SSID.startsWith("Camery-"))
        assertEquals(16, backup.passphrase.length)
    }

    @Test fun backupFailurePreventsUnrecoverableSystemChange() {
        assertThrows(IllegalStateException::class.java) {
            PioneerHotspotConfiguration.ensure({ null }, { 11 }, { error("Must not write without backup") },
                save = { error("Backup disk write failed") })
        }
    }
}
