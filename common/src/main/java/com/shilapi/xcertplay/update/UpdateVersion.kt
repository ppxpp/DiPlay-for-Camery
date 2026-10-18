package com.shilapi.xcertplay.update

internal object UpdateVersion {
    internal fun isNewer(remoteVersionCode: Long, installedVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode
}
