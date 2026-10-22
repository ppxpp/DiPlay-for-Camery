package com.shilapi.xcertplay.pioneer

/** Retry cold-start availability failures while the connection owner remains current. */
object PioneerVpnAuthorizationRetry {
    private val transientReasons = listOf(
        "VPN_ROOT_RESULT_UNCONFIRMED", "VPN_TASK_EXPIRED", "VPN_TASK_CANCELLED",
        "VPN_PREVIOUS_TASK_UNCONFIRMED", "VPN_PREVIOUS_CHILD_UNCONFIRMED",
        "VPN_CONNECTIVITY_SERVICE_MISSING", "Pioneer root service unavailable",
        "ROOT_INIT_READY_TIMEOUT", "ROOT_INIT_STOPPED",
    )
    fun delayAfter(failure: Throwable, attempt: Int): Long? {
        require(attempt >= 1)
        val messages = generateSequence(failure) { it.cause }.take(6).mapNotNull { it.message }.toList()
        if (messages.none { message -> transientReasons.any(message::contains) }) return null
        return when (attempt) { 1 -> 750L; 2 -> 1500L; 3 -> 3000L; else -> 5000L }
    }
}
