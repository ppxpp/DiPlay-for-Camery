package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.File

/** No operation lock or network mutation is allowed before this one-shot gate returns. */
internal object HotspotPrewarmAuthorization {
    // Missing flag is an older, already-supported lease. New leases begin explicitly false.
    fun mayStopOwnedAp(creationStarted: Boolean?, rejected: Boolean) = creationStarted != false && !rejected

    fun await(go: File, token: String, checkCancelled: () -> Unit,
        now: () -> Long, pause: () -> Unit) {
        require(token.matches(Regex("[0-9a-f]{32}")))
        val entered=now()
        while(true) {
            checkCancelled()
            check(now()-entered<60_000) { "Hotspot prewarm authorization expired" }
            if(go.isFile) {
                check(go.readText()==token) { "Hotspot prewarm authorization identity changed" }
                return
            }
            pause()
        }
    }
}
