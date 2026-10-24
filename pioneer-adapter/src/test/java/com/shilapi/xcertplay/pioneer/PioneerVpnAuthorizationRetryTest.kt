package com.shilapi.xcertplay.pioneer

import org.junit.Assert.*
import org.junit.Test

class PioneerVpnAuthorizationRetryTest {
    @Test fun coldStartTimeoutsRemainRetryableBeyondFourAttemptsWithBoundedBackoff() {
        val failure = IllegalStateException("VPN_ROOT_RESULT_UNCONFIRMED")
        assertEquals(listOf(750L, 1500L, 3000L, 5000L, 5000L, 5000L),
            (1..6).map { PioneerVpnAuthorizationRetry.delayAfter(failure, it) })
    }
    @Test fun ownershipConflictsAndDeniedPermissionsAreNotTreatedAsTemporaryStartup() {
        for (reason in listOf("VPN_ACTIVE_OTHER_APP", "VPN_ALWAYS_ON_OTHER_APP", "VPN_SHARED_UID_UNSUPPORTED", "VPN_APPOP_SET_FAILED exit=1")) {
            assertNull(PioneerVpnAuthorizationRetry.delayAfter(IllegalStateException(reason), 1))
        }
    }
    @Test fun wrappedRootServiceAvailabilityIsRetryable() {
        assertEquals(5000L, PioneerVpnAuthorizationRetry.delayAfter(
            IllegalStateException("VPN root dispatch rejected", IllegalStateException("Pioneer root service unavailable")), 9))
    }
}
