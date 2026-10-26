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
import java.security.MessageDigest
import java.util.UUID

/** Rules persist independently of the connection. No heartbeat thread or release subprocess. */
class PioneerVpnFirewallLease private constructor() : Closeable {
    override fun close() = Unit

    companion object {
        private val verified = mutableSetOf<String>()
        @Synchronized fun invalidate() { verified.clear() }
        @Synchronized fun open(context: Context, address: Inet6Address, current: () -> Boolean,
                 log: (String) -> Unit, onError: (Throwable) -> Unit,
                 acquired: (PioneerVpnFirewallLease) -> Unit): PioneerVpnFirewallLease {
            check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer device required" }
            val app = context.applicationContext
            val owner = NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
                iface.name.matches(Regex("tun[0-9]{1,5}")) && iface.inetAddresses.toList().any { it.address.contentEquals(address.address) }
            }.singleOrNull() ?: error("VPN_FIREWALL_TUN_OWNER_UNCONFIRMED")
            val key = "${owner.name}:${address.hostAddress}"
            val lease = PioneerVpnFirewallLease()
            acquired(lease)
            PioneerConnectionTasks.update("rules-check", "RUNNING")
            if (key in verified) {
                check(current()) { "VPN_FIREWALL_CANCELLED" }
                PioneerConnectionTasks.update("rules-check", "REUSED")
                PioneerConnectionTasks.update("rules", "REUSED")
                PioneerConnectionTasks.update("network-ready", "COMPLETE")
                PioneerRootSession.finish(app)
                log("VPN_FIREWALL_REUSED interface=${owner.name}; rules retained")
                return lease
            }
            PioneerConnectionTasks.update("rules-check", "RUNNING", "waiting-root")
            PioneerRootSession.ready(app, current)
            PioneerConnectionTasks.update("rules-check", "RUNNING")
            val root = File(app.filesDir, "vpn-firewall").apply { mkdirs(); Os.chmod(path, 0x1c0) }
            File(root, "owner.lock").apply { if (!exists()) createNewFile(); Os.chmod(path, 0x180) }
            val token = UUID.randomUUID().toString().replace("-", "")
            val stable = MessageDigest.getInstance("MD5").digest("${app.packageName}:${app.applicationInfo.uid}:vpn-rules-v1".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val dir = File(root, token).apply { check(mkdir()); Os.chmod(path, 0x1c0) }
            val started = SystemClock.elapsedRealtime()
            val request = JSONObject().put("token", token).put("stableToken", stable).put("persistent", true)
                .put("camryAdaptation", CamryAdaptation.requestMode()).put("package", app.packageName).put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
                .put("boot", File("/proc/sys/kernel/random/boot_id").readText().trim()).put("appPid", Process.myPid())
                .put("appStart", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
                .put("interface", owner.name).put("index", owner.index)
                .put("address", Inet6Address.getByAddress(null, address.address, 0).hostAddress)
                .put("requestedAtMs", started).put("deadline", started + 15000)
            File(dir,"request.json").apply { writeText(request.toString()); Os.chmod(path,0x180) }
            try {
                PioneerRootSession.submit(app, dir, current)
                while (SystemClock.elapsedRealtime() < started + 15500) {
                    check(current()) { "VPN_FIREWALL_CANCELLED" }
                    val result = File(dir,"result.json").let { runCatching {
                        if (it.length() in 1..8192) JSONObject(it.readText()) else null
                    }.getOrNull() }
                    if (result != null) {
                        check(result.optString("token") == token) { "VPN_FIREWALL_RESULT_IDENTITY" }
                        when (result.optString("state")) {
                            "CHECKING_RULES" -> { Thread.sleep(50); continue }
                            "CONFIGURING_RULES" -> {
                                PioneerConnectionTasks.update("rules-check", "COMPLETE")
                                PioneerConnectionTasks.update("rules", "RUNNING")
                                Thread.sleep(50); continue
                            }
                            "VERIFYING_RULES" -> {
                                PioneerConnectionTasks.update("network-ready", "RUNNING")
                                Thread.sleep(50); continue
                            }
                        }
                        check(result.optString("state") == "READY") { "VPN_FIREWALL_START_FAILED ${result.optString("error")}" }
                        verified.clear(); verified += key
                        PioneerConnectionTasks.update("rules-check", "COMPLETE")
                        PioneerConnectionTasks.update("rules", if (result.optInt("rulesChanged") == 0) "REUSED" else "COMPLETE")
                        PioneerConnectionTasks.update("network-ready", "COMPLETE")
                        PioneerConnectionTrace.mark("vpn-firewall-ready", "interface=${owner.name} persistent=true")
                        log("VPN_FIREWALL_READY interface=${owner.name}; persistent rules, initialization root may exit")
                        return lease
                    }
                    Thread.sleep(50)
                }
                error("VPN_FIREWALL_START_TIMEOUT")
            } catch (error: Throwable) {
                File(dir,"stop").writeText(token)
                PioneerConnectionTasks.update("rules", "FAILED", error.message.orEmpty())
                throw error
            } finally { PioneerRootSession.finish(app) }
        }
    }
}
