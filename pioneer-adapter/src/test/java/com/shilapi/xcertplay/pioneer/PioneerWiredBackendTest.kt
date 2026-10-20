package com.shilapi.xcertplay.pioneer

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerWiredBackendTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("pioneer-adapter", 0)
    @Before fun prepare() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("pioneer-debug", 0).edit().clear().commit()
        ShadowBuild.setModel("SABRESD_MX6DQ"); ShadowBuild.setDevice("tamago")
    }
    @After fun cleanup() { prefs.edit().clear().commit(); context.getSharedPreferences("pioneer-debug", 0).edit().clear().commit() }

    @Test fun legacyNativeChoiceIsPreservedAndDefaultIsOriginal() {
        assertEquals(PioneerWiredBackend.Mode.LWIP, PioneerWiredBackend.mode(context))
        prefs.edit().putBoolean("native_ncm_enabled", true).commit()
        assertEquals(PioneerWiredBackend.Mode.NATIVE_NCM, PioneerWiredBackend.mode(context))
    }

    @Test fun allThreeManualChoicesAreDistinctAndStable() {
        for (mode in PioneerWiredBackend.Mode.entries) {
            PioneerWiredBackend.save(context, mode)
            assertEquals(mode, PioneerWiredBackend.mode(context))
            assertEquals(mode == PioneerWiredBackend.Mode.NATIVE_NCM, PioneerWiredBackend.nativeEnabled(context))
            assertEquals(mode == PioneerWiredBackend.Mode.LWIP_OPTIMIZED, PioneerWiredBackend.optimizedEnabled(context))
            assertTrue(PioneerWiredBackend.usesVehicleAdaptation(context))
            repeat(3) { assertEquals(mode, PioneerWiredBackend.mode(context)) }
        }
    }

    @Test fun explicitLwipChoiceCannotFallIntoLegacyVpnPreference() {
        context.getSharedPreferences("pioneer-debug", 0).edit().putString("network_backend", "VPN").commit()
        for (mode in listOf(PioneerWiredBackend.Mode.LWIP, PioneerWiredBackend.Mode.LWIP_OPTIMIZED)) {
            PioneerWiredBackend.save(context, mode)
            assertTrue(PioneerLwipRuntime.enabled(context))
            assertEquals(mode, PioneerWiredBackend.mode(context))
        }
    }
}
