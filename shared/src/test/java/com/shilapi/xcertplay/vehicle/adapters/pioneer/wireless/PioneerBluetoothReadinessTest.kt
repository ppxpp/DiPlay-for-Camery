package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Test

class PioneerBluetoothReadinessTest {
    @Test fun onlyTheVendorConnectedStateCanStartTheHandshake() {
        PioneerBluetoothReadiness.requireConnected(3,3)
        for(state in listOf(-1,0,1,2,4)) {
            val failure=assertThrows(WirelessStartupException::class.java) {
                PioneerBluetoothReadiness.requireConnected(state,3)
            }
            assertEquals(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,failure.reason)
            assertFalse(failure.reason.retryable)
        }
    }
    @Test fun eitherOemPhoneProfileIsSufficient() {
        for (profiles in listOf(1, 2, 3, 129, 130, 131)) {
            assertTrue(PioneerBluetoothSnapshot.ready(true, profiles))
        }
    }

    @Test fun aclOrSerialAloneCannotTriggerVendorInitialization() {
        for (profiles in listOf(0, 16, 64, 128)) {
            assertFalse(PioneerBluetoothSnapshot.ready(true, profiles))
        }
        for (profiles in listOf(1, 2, 3, 131)) {
            assertFalse(PioneerBluetoothSnapshot.ready(false, profiles))
        }
    }
}
