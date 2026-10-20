package com.shilapi.xcertplay.network

import android.content.Context
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import java.io.IOException

/** Homepage-only module prewarm, serialized with the first wired connection as in b819. */
object NativeNcmPreparation {
    private val gate = NativeNcmPreparationGate()

    fun prepareInBackground(context: Context, wireless: () -> Boolean, hasSession: () -> Boolean,
                            log: (String) -> Unit) {
        val app = context.applicationContext
        val stillSelected = { PioneerUsbPolicy.enabled(app) && !wireless() && PioneerWiredBackend.nativeEnabled(app) &&
            com.shilapi.xcertplay.pioneer.PioneerNativeNcmStartupPolicy.prewarmEnabled(app) }
        val eligible = { stillSelected() && !hasSession() && !PioneerWiredSession.active }
        gate.schedule(eligible, { task -> Thread(task, "native-ncm-prewarm").apply { isDaemon = true; start() } }) {
            try {
                com.shilapi.xcertplay.pioneer.PioneerNativeNcmPrewarm.requireSupportedFirmware()
                NativeNcmLease.prewarm(app, stillSelected, log)
            } catch (error: Throwable) {
                log("NATIVE_NCM_PREWARM deferred: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun connectionRequested() = gate.connectionRequested()

    internal fun awaitReady(context: Context, isCurrent: () -> Boolean) {
        gate.awaitReady(isCurrent)
        NativeNcmLease.requirePrewarmFinished(context)
    }
}

/** Small, process-local fence; the root module loader also owns a cross-process file lock. */
internal class NativeNcmPreparationGate {
    private val monitor = Object()
    private var scheduled = false
    private var requested = false
    private var preparing = false

    fun schedule(eligible: () -> Boolean, execute: (Runnable) -> Unit, prepare: () -> Unit) {
        synchronized(monitor) {
            if (scheduled || requested || !eligible()) return
            scheduled = true
            preparing = true
        }
        try {
            execute(Runnable {
                try {
                    val run = synchronized(monitor) { !requested && eligible() }
                    if (run) prepare()
                } finally {
                    synchronized(monitor) { preparing = false; monitor.notifyAll() }
                }
            })
        } catch (error: Throwable) {
            synchronized(monitor) { preparing = false; monitor.notifyAll() }
            throw error
        }
    }

    fun connectionRequested() = synchronized(monitor) { requested = true }

    fun awaitReady(isCurrent: () -> Boolean, timeoutMillis: Long = 22_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        synchronized(monitor) {
            while (preparing) {
                if (!isCurrent()) throw IOException("NCM preparation cancelled")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("NCM prewarm has not finished; retry later")
                monitor.wait(minOf(100L, (remaining / 1_000_000).coerceAtLeast(1)))
            }
        }
        if (!isCurrent()) throw IOException("NCM preparation cancelled")
    }
}
