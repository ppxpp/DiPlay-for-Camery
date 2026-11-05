package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticComponentPolicyTest {
    @Test
    fun excludesBluetoothAndBlueforceLibraries() {
        assertFalse(DiagnosticComponentPolicy.isSelectedLibrary("libblueforce.so"))
        assertFalse(DiagnosticComponentPolicy.isSelectedLibrary("libbluetooth_jni.so"))
        assertFalse(DiagnosticComponentPolicy.isSelectedLibrary("libpioneer_bluetooth.so"))
        assertFalse(DiagnosticComponentPolicy.isSelectedLibrary("BlueforceService.jar"))
    }

    @Test
    fun keepsPioneerCeamWifiAndTetherLibraries() {
        assertTrue(DiagnosticComponentPolicy.isSelectedLibrary("libpioneer.so"))
        assertTrue(DiagnosticComponentPolicy.isSelectedLibrary("libceam.so"))
        assertTrue(DiagnosticComponentPolicy.isSelectedLibrary("libwifi.so"))
        assertTrue(DiagnosticComponentPolicy.isSelectedLibrary("libtether.jar"))
    }

    @Test
    fun excludesBluetoothPackagesEvenWhenAnotherKeywordMatches() {
        assertFalse(DiagnosticComponentPolicy.isSelectedPackage("com.android.bluetooth"))
        assertFalse(DiagnosticComponentPolicy.isSelectedPackage("com.pioneer.bluetooth"))
        assertFalse(DiagnosticComponentPolicy.isSelectedPackage("com.blueforce.service"))
    }

    @Test
    fun keepsNonBluetoothPioneerCeamWifiAndTetherPackages() {
        assertTrue(DiagnosticComponentPolicy.isSelectedPackage("com.pioneer.headunit"))
        assertTrue(DiagnosticComponentPolicy.isSelectedPackage("com.ceam.service"))
        assertTrue(DiagnosticComponentPolicy.isSelectedPackage("com.byd.wifi"))
        assertTrue(DiagnosticComponentPolicy.isSelectedPackage("com.byd.tether"))
    }

    @Test
    fun explicitComponentsContainNoBluetoothOrBlueforcePaths() {
        assertTrue(DiagnosticComponentPolicy.explicitFiles.isNotEmpty())
        assertTrue(DiagnosticComponentPolicy.explicitFiles.none {
            it.contains("blueforce", ignoreCase = true) || it.contains("bluetooth", ignoreCase = true)
        })
        assertTrue(DiagnosticComponentPolicy.explicitFiles.contains("/system/bin/hostapd"))
        assertTrue(DiagnosticComponentPolicy.explicitFiles.contains("/system/bin/wpa_supplicant"))
    }
}
