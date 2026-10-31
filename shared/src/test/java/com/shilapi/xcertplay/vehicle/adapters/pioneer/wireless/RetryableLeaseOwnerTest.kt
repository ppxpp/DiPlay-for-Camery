package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.*
import org.junit.Test

class RetryableLeaseOwnerTest {
    @Test fun activeLeaseCannotBeDisplacedByAnotherController() {
        val owners=RetryableLeaseOwner<Any>();owners.claim(Any())
        var closed=false
        try { owners.prepare { closed=true };fail("must reject active owner") }
        catch(expected: IllegalStateException) { assertFalse(closed) }
    }
    @Test fun failedCleanupRetainsIdentityAndCanBeRetried() {
        val owners=RetryableLeaseOwner<Any>();val first=Any();owners.claim(first)
        try { owners.release(first) { error("vendor unavailable") };fail("must retain failure") }
        catch(expected: IllegalStateException) { }
        var retried: Any?=null
        owners.prepare { old -> retried=old;owners.release(old) { } }
        assertSame(first,retried)
        owners.claim(Any())
    }
    @Test fun staleCleanupCannotClearNewOwner() {
        val owners=RetryableLeaseOwner<Any>();val first=Any();owners.claim(first)
        owners.release(first) { };owners.claim(Any())
        owners.release(first) { }
        try { owners.prepare { fail("healthy new owner must remain active") };fail("must reject") }
        catch(expected: IllegalStateException) { }
    }
}
