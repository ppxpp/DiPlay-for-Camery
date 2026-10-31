package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.File

/** PID reuse must not make a stale request look like a live helper. Unknown identity fails closed. */
internal object HotspotRequestIdentity {
    fun running(marker: File, stat: (Int) -> String? = { pid ->
        val file=File("/proc/$pid/stat")
        try {
            android.system.Os.stat(file.path)
            file.readText()
        } catch(failure: android.system.ErrnoException) {
            if(failure.errno==android.system.OsConstants.ENOENT) null else throw failure
        }
    }): Boolean {
        if(!marker.exists()) return false
        val parts=marker.readText().split(':',limit=2)
        check(parts.size==2 && parts[0].toIntOrNull()?.let { it>1 }==true &&
            parts[1].matches(Regex("[0-9]+"))) { "Unconfirmed hotspot helper identity" }
        val current=stat(parts[0].toInt()) ?: return false
        return current.substringAfterLast(") ").split(' ').getOrNull(19)
            ?.let { it==parts[1] } ?: error("Unconfirmed hotspot helper process")
    }
}
