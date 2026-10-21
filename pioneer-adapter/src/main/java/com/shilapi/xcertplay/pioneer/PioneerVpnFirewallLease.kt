package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** App heartbeat for a root-owned, narrowly scoped VPN firewall lease. */
class PioneerVpnFirewallLease private constructor(
    private val context: Context,
    private val directory: File,
    private val token: String,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private var released = false
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "vpn-firewall-heartbeat").apply { isDaemon = true }
    }
    private fun renew() {
        val temp = File(directory, "heartbeat.tmp")
        temp.writeText(SystemClock.elapsedRealtime().toString())
        Os.chmod(temp.path, 0x180)
        check(temp.renameTo(File(directory, "heartbeat"))) { "VPN_FIREWALL_HEARTBEAT_WRITE" }
    }
    private fun status(): JSONObject? = runCatching {
        val file = File(directory, "result.json")
        check(file.length() in 1..8192)
        JSONObject(file.readText()).also { check(it.getString("token") == token) }
    }.getOrNull()

    @Synchronized
    override fun close() {
        if (released) return
        closed.set(true)
        heartbeat.shutdownNow()
        File(directory, "stop").apply { writeText(token); Os.chmod(path, 0x180) }
        repeat(2) { attempt ->
            if (attempt == 1) {
                log("VPN_FIREWALL_RELEASE_RECOVERY begin; exact token only")
                val command = "CLASSPATH=${PioneerRootDispatcher.quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerVpnFirewallHelper --release ${PioneerRootDispatcher.quote(directory.path)} </dev/null >/dev/null 2>&1 &"
                Thread({ runCatching { PioneerRootDispatcher.dispatch(command) } }, "vpn-release-recovery")
                    .apply { isDaemon = true; start() }
            }
            val end = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < end) {
                val state = status()
                if (state?.optString("state") == "CLOSED") {
                    released = true
                    log("VPN_FIREWALL_RELEASED cleanupMs=${state.optLong("cleanupMs", -1)} recovery=${attempt == 1}")
                    return
                }
                // A pending/uncertain firewall command must never be bypassed by another helper.
                check(state?.optString("state") != "RELEASE_FAILED") { "VPN_FIREWALL_RELEASE_FAILED ${state?.optString("error")}" }
                Thread.sleep(50)
            }
        }
        error("VPN_FIREWALL_RELEASE_UNCONFIRMED; tunnel retained until cleanup")
    }

    companion object {
        fun open(context: Context, address: Inet6Address, current: () -> Boolean,
                 log: (String) -> Unit, onError: (Throwable) -> Unit,
                 acquired: (PioneerVpnFirewallLease) -> Unit): PioneerVpnFirewallLease {
            check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer device required" }
            val app = context.applicationContext
            val owner = NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
                iface.name.matches(Regex("tun[0-9]{1,5}")) && iface.inetAddresses.toList().any { it.address.contentEquals(address.address) }
            }.singleOrNull() ?: error("VPN_FIREWALL_TUN_OWNER_UNCONFIRMED")
            val root = File(app.filesDir, "vpn-firewall").apply { mkdirs(); Os.chmod(path, 0x1c0) }
            val lock = File(root, "owner.lock")
            if (!lock.exists()) lock.createNewFile()
            Os.chmod(lock.path, 0x180)
            val token = UUID.randomUUID().toString().replace("-", "")
            val dir = File(root, token).apply { check(mkdir()); Os.chmod(path, 0x1c0) }
            val started = SystemClock.elapsedRealtime()
            val request = JSONObject().put("token", token).put("package", app.packageName)
                .put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
                .put("boot", File("/proc/sys/kernel/random/boot_id").readText().trim())
                .put("appPid", Process.myPid())
                .put("appStart", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
                .put("interface", owner.name).put("index", owner.index)
                .put("address", Inet6Address.getByAddress(null, address.address, 0).hostAddress)
                .put("requestedAtMs", started).put("deadline", started + 10000)
            File(dir, "request.json").apply { writeText(request.toString()); Os.chmod(path, 0x180) }
            val lease = PioneerVpnFirewallLease(app, dir, token, log)
            acquired(lease)
            lease.renew()
            val dispatchError = AtomicReference<Throwable?>()
            val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                "com.shilapi.xcertplay.pioneer.PioneerVpnFirewallHelper ${PioneerRootDispatcher.quote(dir.path)} </dev/null >/dev/null 2>&1 &"
            Thread({ runCatching {
                if (!PioneerVpnFirewallWarmup.submit(app, dir, current, log)) {
                    log("VPN_FIREWALL_WARM_UNAVAILABLE; launching session helper")
                    PioneerRootDispatcher.dispatch(command)
                }
            }.onFailure(dispatchError::set) }, "vpn-firewall-dispatch")
                .apply { isDaemon = true; start() }
            try {
                while (SystemClock.elapsedRealtime() < started + 10000) {
                    check(current()) { "VPN_FIREWALL_CANCELLED" }
                    dispatchError.get()?.let { throw IllegalStateException("VPN_FIREWALL_DISPATCH_FAILED", it) }
                    val state = lease.status()
                    when (state?.optString("state")) {
                        "READY" -> {
                            log("VPN_FIREWALL_READY interface=${owner.name} index=${owner.index} helperStartupMs=${state.optLong("helperStartupMs", -1)} firewallInstallMs=${state.optLong("firewallInstallMs", -1)}")
                            lease.heartbeat.scheduleWithFixedDelay({
                                if (!lease.closed.get()) try {
                                    check(current()) { "VPN_FIREWALL_CANCELLED" }
                                    val helper = lease.status()
                                    check(helper?.optString("state") == "READY" &&
                                        SystemClock.elapsedRealtime() - helper.optLong("updated", 0) in 0..3000) { "VPN_FIREWALL_HELPER_STOPPED" }
                                    lease.renew()
                                } catch (error: Throwable) {
                                    if (!lease.closed.get()) { lease.heartbeat.shutdown(); onError(error) }
                                }
                            }, 1, 1, TimeUnit.SECONDS)
                            return lease
                        }
                        "CLOSED", "RELEASE_FAILED" -> error("VPN_FIREWALL_START_FAILED ${state.optString("error")}")
                    }
                    lease.renew()
                    Thread.sleep(50)
                }
                error("VPN_FIREWALL_START_TIMEOUT")
            } catch (error: Throwable) {
                runCatching { lease.close() }.onFailure(error::addSuppressed)
                throw error
            }
        }
    }
}
