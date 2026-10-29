package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** Access under the backend lock. A healthy owner is never displaced by a new controller. */
internal class RetryableLeaseOwner<T : Any> {
    private var owner: T? = null
    private var cleanupPending = false

    fun prepare(retry: (T) -> Unit) {
        val previous = owner ?: return
        check(cleanupPending) { "Previous Pioneer wireless connection is still active" }
        retry(previous)
        check(owner == null) { "Previous Pioneer wireless connection has not been released" }
    }

    fun claim(lease: T) {
        check(owner == null) { "Pioneer wireless connection is already owned" }
        owner = lease
        cleanupPending = false
    }

    fun release(lease: T, cleanup: () -> Unit) {
        if(owner === lease) cleanupPending = true
        cleanup() // A failed cleanup retains both identity and permission to retry it.
        if(owner === lease) {
            owner = null
            cleanupPending = false
        }
    }
}
