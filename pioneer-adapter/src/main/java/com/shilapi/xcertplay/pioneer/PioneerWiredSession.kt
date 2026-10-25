package com.shilapi.xcertplay.pioneer

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Vehicle adaptation lifetime. Transport owners open it before advertising AirPlay. */
object PioneerWiredSession {
    enum class Backend { LWIP, NATIVE_NCM, LWIP_OPTIMIZED, VPN }

    class Session internal constructor(
        val id: Long,
        val backend: Backend,
        val verifiedDevice: Boolean,
    ) : Closeable {
        @Volatile var firstFrame = false
            internal set
        @Volatile var controlAccepted = false
            internal set
        override fun close() = end(this)
    }

    private val ids = AtomicLong()
    @Volatile var current: Session? = null
        private set
    val active: Boolean get() = current != null
    val adaptationActive: Boolean get() = current?.verifiedDevice == true
    val sessionId: Long get() = current?.id ?: 0L
    fun isCurrent(id: Long): Boolean = current?.let { it.id == id && it.verifiedDevice } == true

    @Synchronized fun begin(backend: Backend, verifiedDevice: Boolean = PioneerUsbPolicy.matchesDevice()): Session {
        check(current == null) { "Previous Pioneer wired session is still active" }
        PioneerDiagnostics.clear()
        return Session(ids.incrementAndGet(), backend, verifiedDevice).also { current = it }
    }

    @Synchronized private fun end(owner: Session) {
        if (current === owner) current = null
    }

    fun firstFrame(id: Long) {
        val configure = synchronized(this) {
            val owner = current?.takeIf { it.id == id && !it.firstFrame } ?: return
            owner.firstFrame = true
            if (owner.backend == Backend.VPN) PioneerDebugRuntime.vpnEnableBatching else null
        }
        // Native URB sender stays in JNI: it cannot observe the Kotlin flag by itself.
        // Promote its startup single-frame limit exactly once after the first output.
        if (configure != null) runCatching {
            check(configure()) { "VPN batching owner already closed" }
            PioneerConnectionTrace.mark("vpn-batch-enabled", "frames=${PioneerVpnIoPolicy.BATCH_PACKETS}")
        }.onFailure { android.util.Log.e("DiPlayVpn", "Could not enable post-startup batching",it) }
    }

    @Synchronized fun controlAccepted(id: Long) {
        current?.takeIf { it.id == id }?.controlAccepted = true
    }
}
