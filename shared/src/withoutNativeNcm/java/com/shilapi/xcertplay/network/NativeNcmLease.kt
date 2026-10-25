package com.shilapi.xcertplay.network

import android.content.Context
import android.hardware.usb.UsbDevice
import java.io.Closeable
import java.io.IOException

/** Disabled build boundary only: no module loading, worker, network or route configuration. */
internal class NativeNcmLease private constructor() : Closeable {
    val binding: InterfaceSocketBinding get() = unavailable()
    val hostMac: ByteArray get() = unavailable()
    override fun close() = Unit

    companion object {
        private fun unavailable(): Nothing = throw IOException("Native NCM is not included in this build; select another wired connection method")
        fun open(context: Context, device: UsbDevice, address: String, isCurrent: () -> Boolean,
                 log: (String) -> Unit, onError: (Throwable) -> Unit): NativeNcmLease = unavailable()

        fun requireReleased(context: Context) = Unit
        fun awaitReleased(context: Context, isCurrent: () -> Boolean, log: (String) -> Unit) {
            check(isCurrent()) { "Connection cancelled" }
        }
        fun requirePrewarmFinished(context: Context) = Unit
    }
}
