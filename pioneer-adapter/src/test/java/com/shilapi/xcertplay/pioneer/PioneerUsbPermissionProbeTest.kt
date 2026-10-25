package com.shilapi.xcertplay.pioneer

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerUsbPermissionProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun clean() { context.getSharedPreferences("pioneer-debug", 0).edit().clear().commit() }

    @Test fun holdDefaultsOffAndIsRestrictedToPioneer() {
        ShadowBuild.setModel("SABRESD_MX6DQ"); ShadowBuild.setDevice("tamago")
        assertFalse(PioneerUsbPermissionProbe.holdsRequests(context))
        context.getSharedPreferences("pioneer-debug", 0).edit().putBoolean("usb_permission_probe_hold", true).commit()
        assertTrue(PioneerUsbPermissionProbe.holdsRequests(context))
        ShadowBuild.setModel("other")
        assertFalse(PioneerUsbPermissionProbe.holdsRequests(context))
    }

    @Test fun binderSuccessAloneAndExistingPermissionAreNotProof() {
        val completed = JSONObject().put("helperUid", 0).put("code", "COMPLETED").put("grantCallSucceeded", true)
        fun verdict(before: Boolean, same: Boolean, after: Boolean, open: Boolean) =
            PioneerUsbPermissionProbe.verdict("GRANT", before, same, after, open, completed)
        assertEquals("GRANT_NOT_VERIFIED", verdict(false, true, false, false))
        assertEquals("GRANT_NOT_VERIFIED", verdict(false, true, true, false))
        assertEquals("ALREADY_GRANTED_NOT_A_PROOF", verdict(true, true, true, true))
        assertEquals("DEVICE_CHANGED_NO_PROOF", verdict(false, false, true, true))
        assertEquals("GRANT_VERIFIED", verdict(false, true, true, true))
        completed.put("helperUid", 2000)
        assertEquals("HELPER_FAILED", verdict(false, true, true, true))
    }

    @Test fun clearDefaultRequiresReadbackAndNeverClaimsRevocation() {
        val helper = JSONObject().put("helperUid", 0).put("code", "COMPLETED")
        assertEquals("DEFAULT_CLEAR_NOT_CONFIRMED", PioneerUsbPermissionProbe.verdict("CLEAR_DEFAULT", true, true, true, false, helper))
        helper.put("defaultClearVerified", true)
        assertEquals("DEFAULT_CLEARED_REPLUG_REQUIRED", PioneerUsbPermissionProbe.verdict("CLEAR_DEFAULT", true, true, true, false, helper))
        PioneerUsbPermissionHelper.requireOwnDefault("NONE", "com.example.diplay")
        PioneerUsbPermissionHelper.requireOwnDefault("com.example.diplay", "com.example.diplay")
        for (other in listOf("com.other.app", "UNKNOWN", "MULTIPLE"))
            assertThrows(java.io.IOException::class.java) { PioneerUsbPermissionHelper.requireOwnDefault(other, "com.example.diplay") }
    }

    @Test fun settingDefaultIsNotProofThatTheSystemPopupIsGone() {
        val helper = JSONObject().put("helperUid", 0).put("code", "COMPLETED")
            .put("defaultSetCallSucceeded", true)
        assertEquals("DEFAULT_SET_NOT_CONFIRMED", PioneerUsbPermissionProbe.verdict("SET_DEFAULT", true, true, true, false, helper))
        helper.put("defaultSetVerified", true)
        assertEquals("DEFAULT_SET_REPLUG_REQUIRED", PioneerUsbPermissionProbe.verdict("SET_DEFAULT", true, true, true, false, helper))
        assertEquals("DEVICE_CHANGED_NO_PROOF", PioneerUsbPermissionProbe.verdict("SET_DEFAULT", true, false, true, false, helper))
        helper.put("helperUid", 2000)
        assertEquals("HELPER_FAILED", PioneerUsbPermissionProbe.verdict("SET_DEFAULT", true, true, true, false, helper))
    }

    @Test fun deviceSelectionAndIdentityRejectAmbiguityOrReplacement() {
        val a = device("/dev/bus/usb/001/003", "serial-a")
        val b = device("/dev/bus/usb/001/004", "serial-b")
        assertSame(a, PioneerUsbPermissionProbe.selectDevice(listOf(a), null))
        assertThrows(IllegalArgumentException::class.java) { PioneerUsbPermissionProbe.selectDevice(listOf(a, b), null) }
        assertSame(b, PioneerUsbPermissionProbe.selectDevice(listOf(a, b), b.deviceName))
        assertNotEquals(PioneerUsbPermissionHelper.identity(a), PioneerUsbPermissionHelper.identity(b))
        val usb = context.getSystemService(UsbManager::class.java)
        shadowOf(usb).addOrUpdateUsbDevice(a, false)
        val request = JSONObject().put("node", a.deviceName).put("deviceId", a.deviceId).put("vid", a.vendorId)
            .put("pid", a.productId).put("identityHash", PioneerUsbPermissionHelper.identity(a))
        assertSame(a, PioneerUsbPermissionHelper.current(usb, request))
        shadowOf(usb).removeUsbDevice(a)
        shadowOf(usb).addOrUpdateUsbDevice(device(a.deviceName, "replacement"), false)
        assertThrows(java.io.IOException::class.java) { PioneerUsbPermissionHelper.current(usb, request) }
    }

    @Test fun defaultsParserDistinguishesOwnOtherAndConflictingAssociations() {
        val device = device("/dev/bus/usb/001/003", "serial-a")
        fun entry(pkg: String, pid: Int = device.productId) =
            "<preference package=\"$pkg\"><usb-device vendor-id=\"1452\" product-id=\"$pid\" /></preference>"
        assertEquals("NONE", PioneerUsbPermissionHelper.defaultFrom("<settings>${entry("other", 12)}</settings>", device))
        assertEquals("com.example.diplay", PioneerUsbPermissionHelper.defaultFrom("<settings>${entry("com.example.diplay")}</settings>", device))
        assertEquals("MULTIPLE", PioneerUsbPermissionHelper.defaultFrom("<settings>${entry("com.example.diplay")}${entry("other")}</settings>", device))
    }

    @Test fun rootHelperDoesNotAcceptArbitraryOperations() {
        for (action in listOf("INSPECT", "GRANT", "CLEAR_DEFAULT", "SET_DEFAULT")) PioneerUsbPermissionHelper.requireAction(action)
        for (action in listOf("SHELL", "REVOKE_ALL", "", "GRANT;reboot"))
            assertThrows(java.io.IOException::class.java) { PioneerUsbPermissionHelper.requireAction(action) }
    }

    private fun device(node: String, serial: String): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", node)
        ReflectionHelpers.setField(this, "mVendorId", 0x05ac)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        ReflectionHelpers.setField(this, "mManufacturerName", "Apple Inc.")
        ReflectionHelpers.setField(this, "mProductName", "iPhone")
        ReflectionHelpers.setField(this, "mSerialNumber", serial)
    }
}
