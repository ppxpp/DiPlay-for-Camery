package com.shilapi.xcertplay.pioneer

/** Production USB policy for the verified Pioneer VPN backend. */
object PioneerVpnIoPolicy {
    const val RECEIVE_REQUESTS = 4
    const val SEND_REQUESTS = 2
    const val BATCH_PACKETS = 8
}
