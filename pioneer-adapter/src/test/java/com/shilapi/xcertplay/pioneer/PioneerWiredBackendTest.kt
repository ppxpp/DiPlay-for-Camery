package com.shilapi.xcertplay.pioneer

import org.junit.Before
import org.junit.Assert.*
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
    @Before fun prepare() {
        context.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
        context.getSharedPreferences("pioneer-debug", 0).edit().clear().commit()
        ShadowBuild.setModel("SABRESD_MX6DQ"); ShadowBuild.setDevice("tamago")
    }
    @Test fun defaultIsVpnAndHasNoLwipFallback() {
        assertEquals(PioneerWiredBackend.Mode.VPN, PioneerWiredBackend.mode(context))
        assertFalse(PioneerLwipRuntime.enabled(context))
    }
    @Test fun availableManualChoicesAreDistinctAndStable() {
        for (mode in PioneerWiredBackend.availableModes()) {
            PioneerWiredBackend.save(context, mode)
            assertEquals(mode, PioneerWiredBackend.mode(context))
            assertEquals(mode == PioneerWiredBackend.Mode.LWIP_OPTIMIZED, PioneerWiredBackend.optimizedEnabled(context))
            assertTrue(PioneerWiredBackend.usesVehicleAdaptation(context))
            repeat(3) { assertEquals(mode, PioneerWiredBackend.mode(context)) }
        }
    }
    @Test fun removedBackendPreferenceFallsBackToVpn() {
        context.getSharedPreferences("pioneer-adapter", 0).edit().putString("wired_backend", "NATIVE_NCM").commit()
        assertEquals(PioneerWiredBackend.Mode.VPN, PioneerWiredBackend.mode(context))
        assertEquals(listOf(PioneerWiredBackend.Mode.LWIP, PioneerWiredBackend.Mode.LWIP_OPTIMIZED,
            PioneerWiredBackend.Mode.VPN), PioneerWiredBackend.availableModes())
    }
    @Test fun selectorIsOnlyAvailableInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, PioneerWiredBackend.selectorVisible)
    }
    @Test fun explicitChoiceIsNotChangedByOtherBackendPreferences() {
        context.getSharedPreferences("pioneer-debug", 0).edit().putString("network_backend", "VPN").commit()
        PioneerWiredBackend.save(context, PioneerWiredBackend.Mode.LWIP)
        assertTrue(PioneerWiredBackend.explicitLwip(context))
        assertTrue(PioneerLwipRuntime.enabled(context))
        PioneerWiredBackend.save(context, PioneerWiredBackend.Mode.VPN)
        assertFalse(PioneerWiredBackend.explicitLwip(context))
        assertFalse(PioneerLwipRuntime.enabled(context))
    }
}
