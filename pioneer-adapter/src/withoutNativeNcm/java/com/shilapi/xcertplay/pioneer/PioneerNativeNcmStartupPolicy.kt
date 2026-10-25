package com.shilapi.xcertplay.pioneer

import android.content.Context

/** Disabled capability boundary; the optional prewarm/worker implementation is not compiled. */
object PioneerNativeNcmStartupPolicy {
    fun warmWorkerEnabled(context: Context) = false
    fun prewarmEnabled(context: Context) = false
    fun save(context: Context, warmWorker: Boolean?, prewarm: Boolean?) {
        error("Native NCM is not included in this build")
    }
}
