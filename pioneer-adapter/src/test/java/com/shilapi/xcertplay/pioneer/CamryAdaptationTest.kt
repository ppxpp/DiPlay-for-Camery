package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONObject
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
@Config(sdk = [25])
class CamryAdaptationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        context.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
        ShadowBuild.setModel("SABRESD-MX6DQ")
        ShadowBuild.setDevice("tamago_3T_uart")
        CamryAdaptation.initialize(context)
    }
    @After fun reset() {
        PioneerWiredSession.current?.close()
        context.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
        CamryAdaptation.initialize(context)
    }
    @Test fun autoUsesModelAndGeneralRootOperationsDoNotCheckFirmwareCatalog() {
        assertTrue(PioneerUsbPolicy.matchesDevice())
        PioneerFirmwareCatalog.requireSupportedFirmware()
        ShadowBuild.setDevice("another-device")
        assertFalse(PioneerUsbPolicy.matchesDevice())
    }
    @Test fun explicitChoiceControlsUsbAndRootAuthorizationTogether() {
        ShadowBuild.setModel("unrecognized")
        CamryAdaptation.save(context, CamryAdaptation.Mode.ON)
        assertTrue(PioneerUsbPolicy.enabled(context))
        PioneerFirmwareCatalog.requireSupportedFirmware()
        CamryAdaptation.save(context, CamryAdaptation.Mode.OFF)
        assertFalse(PioneerUsbPolicy.enabled(context))
        assertThrows(java.io.IOException::class.java) { PioneerFirmwareCatalog.requireSupportedFirmware() }
    }
    @Test fun helperRequestCarriesForceOnAndForceOffAcrossProcessBoundary() {
        ShadowBuild.setModel("unrecognized")
        CamryAdaptation.useRequestMode(JSONObject().put("camryAdaptation", "ON"))
        assertTrue(CamryAdaptation.enabled())
        CamryAdaptation.useRequestMode(JSONObject().put("camryAdaptation", "OFF"))
        assertFalse(CamryAdaptation.enabled())
        assertThrows(IllegalArgumentException::class.java) {
            CamryAdaptation.useRequestMode(JSONObject().put("camryAdaptation", "invalid"))
        }
    }
    @Test fun runningSessionKeepsItsPolicyUntilTeardownThenAppliesSavedChoice() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN)
        CamryAdaptation.save(context, CamryAdaptation.Mode.OFF)
        assertEquals(CamryAdaptation.Mode.OFF, CamryAdaptation.mode(context))
        assertTrue(CamryAdaptation.enabled())
        session.close()
        assertFalse(CamryAdaptation.enabled())
    }
}
