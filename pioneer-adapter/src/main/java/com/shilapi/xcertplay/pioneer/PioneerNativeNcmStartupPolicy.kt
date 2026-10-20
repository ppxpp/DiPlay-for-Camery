package com.shilapi.xcertplay.pioneer

import android.content.Context

/** Fixed startup experiments exposed only by the existing idle-only TCP diagnostics. */
object PioneerNativeNcmStartupPolicy {
    private fun prefs(context: Context) = context.getSharedPreferences("pioneer-debug", Context.MODE_PRIVATE)
    fun warmWorkerEnabled(context: Context) = prefs(context).getBoolean("native_ncm_warm_worker", true)
    fun prewarmEnabled(context: Context) = prefs(context).getBoolean("native_ncm_prewarm", true)
    fun save(context: Context, warmWorker: Boolean?, prewarm: Boolean?) {
        prefs(context).edit().apply {
            warmWorker?.let { putBoolean("native_ncm_warm_worker", it) }
            prewarm?.let { putBoolean("native_ncm_prewarm", it) }
        }.commit()
    }
}
