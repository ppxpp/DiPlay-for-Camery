package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.*
import org.junit.Test

class PioneerSerialOwnershipTest {
    private val phone="AA:BB:CC:DD:EE:FF"
    private val uuid="00000000-DECA-FADE-DECA-DEAFDECACAFE"
    private fun allowed(record: String?=phone, recordedBoot: String?="boot", boot: String="boot",
        actual: String=phone, id: String=uuid, index: Int=0, connected: Boolean=true, status: Int=0) =
        PioneerSerialOwnership.mayRecover(record,recordedBoot,boot,phone,actual,id,index,connected,status)
    @Test fun recoversOnlyRecordedCarPlayPort() { assertTrue(allowed());assertTrue(allowed(record=phone.lowercase())) }
    @Test fun refusesUnknownOrDifferentOwner() { assertFalse(allowed(record=null));assertFalse(allowed(record="other"));assertFalse(allowed(actual="other")) }
    @Test fun refusesOldBootOrMissingIdentity() { assertFalse(allowed(recordedBoot=null));assertFalse(allowed(recordedBoot="old"));assertFalse(allowed(boot="",recordedBoot="")) }
    @Test fun refusesOtherServiceOrPort() { assertFalse(allowed(id="unknown"));assertFalse(allowed(index=1)) }
    @Test fun refusesPendingOrDisconnectedPort() { assertFalse(allowed(status=1));assertFalse(allowed(connected=false)) }
    @Test fun cleanupNeverDisconnectsAnotherServiceOnTheSamePhone() {
        assertTrue(PioneerSerialOwnership.matchesPort(phone, phone.lowercase(), uuid, 0))
        assertFalse(PioneerSerialOwnership.matchesPort(phone, phone, "00001101-0000-1000-8000-00805F9B34FB", 0))
        assertFalse(PioneerSerialOwnership.matchesPort(phone, phone, uuid, 1))
        assertFalse(PioneerSerialOwnership.matchesPort(phone, "other", uuid, 0))
    }
}
