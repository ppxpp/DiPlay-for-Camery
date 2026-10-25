package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Ipv6NcmWriteResultTest {
    @Test fun completedPacketProceeds() {
        Ipv6NcmBridge.requireCompleteWrite(result(NcmUsbBridge.WriteFailure.NONE))
    }
    @Test fun allUnconfirmedWritesStopTheVpnInsteadOfSilentlyLosingPackets() {
        NcmUsbBridge.WriteFailure.entries.filter { it != NcmUsbBridge.WriteFailure.NONE }.forEach {
            try {
                Ipv6NcmBridge.requireCompleteWrite(result(it))
                fail("Expected explicit failure for $it")
            } catch (error: IOException) {
                assertTrue(error.message!!.contains("VPN_NCM_WRITE_FAILED failure=$it"))
            }
        }
    }
    private fun result(failure: NcmUsbBridge.WriteFailure) = NcmUsbBridge.WriteResult(
        if (failure == NcmUsbBridge.WriteFailure.NONE) 128 else -1, 128, failure, 0, 0)
}
