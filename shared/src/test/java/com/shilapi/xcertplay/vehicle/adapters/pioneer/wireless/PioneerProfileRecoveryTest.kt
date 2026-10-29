package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Test

class PioneerProfileRecoveryTest {
    private fun action(current: Int, recorded: String?)=PioneerProfileRecovery.decide(3,current,recorded,"new-boot",1)
    @Test fun legacyOrdinaryProfileIsPreserved() { assertEquals(PioneerProfileRecovery.Action.ADOPT_CURRENT,action(2,null)) }
    @Test fun changedBootAcceptsANewOrdinaryBaseline() { assertEquals(PioneerProfileRecovery.Action.ADOPT_CURRENT,action(2,"old-boot")) }
    @Test fun sameBootConflictIsNotOverwritten() { assertEquals(PioneerProfileRecovery.Action.BLOCK,action(2,"new-boot")) }
    @Test fun knownTemporaryProfileCanStillBeRolledBack() { assertEquals(PioneerProfileRecovery.Action.RESTORE_SAVED,action(131,"old-boot")) }
    @Test fun unknownSerialProfileIsNeverAdopted() { assertEquals(PioneerProfileRecovery.Action.BLOCK,action(130,null)) }
    @Test fun multiplePairedDevicesRemainProtected() { assertEquals(PioneerProfileRecovery.Action.BLOCK,PioneerProfileRecovery.decide(3,2,null,"new-boot",2)) }
    @Test fun conflictDoesNotRetry() { assertFalse(WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT.retryable) }
}
