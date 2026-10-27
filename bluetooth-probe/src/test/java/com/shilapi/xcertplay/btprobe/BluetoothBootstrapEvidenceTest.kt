package com.shilapi.xcertplay.btprobe

import org.junit.Assert.*
import org.junit.Test

class BluetoothBootstrapEvidenceTest {
    private fun complete() = BluetoothBootstrapEvidence(true,true,2,1,true,1,true,true)
    @Test fun aCompleteTranscriptAndAuthenticatedWifiHandoffAreRequired() {
        assertTrue(complete().bluetoothWorkVerified)
        val partial = listOf(complete().copy(linkReady=false),complete().copy(authenticatedAndSubscribed=false),
            complete().copy(wifiConfigurationsSent=0),complete().copy(startSessionsSent=0),
            complete().copy(transportNotificationSeen=false),complete().copy(postTransportConfigurationsSent=0),
            complete().copy(wirelessAvailableSeen=false))
        partial.forEach { assertFalse(it.toString(),it.bluetoothWorkVerified) }
    }
    @Test fun deliveredBluetoothParametersDoNotProveWifiTakeover() {
        val delivered=complete().copy(wifiRuntimeAuthenticated=false)
        assertTrue(delivered.bluetoothControlComplete)
        assertFalse(delivered.bluetoothWorkVerified)
    }
    @Test fun anUnrelatedWifiSessionDoesNotReplaceBluetoothEvidence() {
        assertFalse(complete().copy(authenticatedAndSubscribed=false).bluetoothWorkVerified)
    }
}
