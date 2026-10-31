package com.shilapi.xcertplay.pioneer

import android.content.Context
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** VPN authorization is requested with USB authorization, awaited only immediately before TUN. */
object PioneerVpnInitialization {
    private data class Attempt(val owner: WeakReference<Any>, val done: CountDownLatch = CountDownLatch(1), @Volatile var failure: Throwable? = null)
    @Volatile private var attempt: Attempt? = null

    @Synchronized fun begin(context: Context, owner: Any, current: () -> Boolean) {
        if (attempt?.let { it.owner.get() === owner && it.done.count == 1L } == true) return
        val app = context.applicationContext
        val owned = Attempt(WeakReference(owner))
        attempt = owned
        PioneerRootSession.start(app)
        PioneerConnectionTasks.update("vpn-authorization", "RUNNING")
        Thread({
            var failures = 0
            try {
                while (current()) {
                    try {
                        check(PioneerVpnPermissionGrant.grantForConnection(app, current)) { "VPN_ROOT_AUTH_REJECTED" }
                        if (attempt === owned && PioneerConnectionTasks.snapshot().none { it.id == "vpn-authorization" && it.state == "REUSED" })
                            PioneerConnectionTasks.update("vpn-authorization", "COMPLETE")
                        return@Thread
                    } catch (error: Throwable) {
                        check(current()) { "VPN_AUTH_CANCELLED" }
                        val delay = PioneerVpnAuthorizationRetry.delayAfter(error, ++failures) ?: throw error
                        PioneerConnectionTasks.update("vpn-authorization", "RETRYING", "attempt=$failures")
                        Thread.sleep(delay)
                    }
                }
                error("VPN_AUTH_CANCELLED")
            } catch (error: Throwable) {
                owned.failure = error
                if (attempt === owned) PioneerConnectionTasks.update("vpn-authorization", "FAILED", error.message.orEmpty())
            } finally { owned.done.countDown() }
        }, "vpn-init-authorization").apply { isDaemon = true; start() }
    }

    fun await(context: Context, current: () -> Boolean) {
        val owned = checkNotNull(attempt) { "VPN_AUTH_NOT_REQUESTED" }
        PioneerConnectionTasks.update("vpn-permission", "RUNNING")
        while (!owned.done.await(100, TimeUnit.MILLISECONDS)) {
            check(current()) { "VPN_AUTH_CANCELLED" }
            val auth = PioneerConnectionTasks.snapshot().firstOrNull { it.id == "vpn-authorization" }
            if (auth?.state == "RETRYING") PioneerConnectionTasks.update("vpn-permission", "RETRYING", auth.detail)
            else if (PioneerConnectionTasks.snapshot().any { it.id == "root" && it.state == "RUNNING" })
                PioneerConnectionTasks.update("vpn-permission", "RUNNING", "waiting-root")
            else PioneerConnectionTasks.update("vpn-permission", "RUNNING")
        }
        check(current()) { "VPN_AUTH_CANCELLED" }
        owned.failure?.let {
            PioneerConnectionTasks.update("vpn-permission", "FAILED", it.message.orEmpty())
            throw IllegalStateException("VPN_AUTH_FAILED", it)
        }
        val reused = PioneerConnectionTasks.snapshot().any { it.id == "vpn-authorization" && it.state == "REUSED" }
        PioneerConnectionTasks.update("vpn-permission", if (reused) "REUSED" else "COMPLETE")
        // No additional prepare query here: the authorization task already checked its result.
    }
}
