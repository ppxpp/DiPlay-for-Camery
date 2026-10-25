package com.shilapi.xcertplay.network

import android.content.Context

/** Disabled capability boundary for builds without the optional kernel-network backend. */
object NativeNcmPreparation {
    fun prepareInBackground(context: Context, wireless: () -> Boolean, hasSession: () -> Boolean, log: (String) -> Unit) = Unit
    fun connectionRequested() = Unit
    internal fun awaitReady(context: Context, isCurrent: () -> Boolean) {
        check(isCurrent()) { "Connection cancelled" }
        NativeNcmLease.requirePrewarmFinished(context)
    }
}
