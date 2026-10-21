package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Starts only an idle root ART worker; the VPN service retains all tunnel/session ownership. */
object PioneerVpnFirewallWarmup {
    private val launching = AtomicBoolean()
    @Volatile private var descriptor: File? = null

    fun start(context: Context, log: (String) -> Unit) {
        if (!PioneerUsbPolicy.matchesDevice() || !launching.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                // A live or unconfirmed previous worker is retained. Only a missing PID or a
                // positively identified PID reuse permits another idle worker to be dispatched.
                if (previousWorkerMayBeAlive()) return@Thread
                val root = File(app.filesDir, "vpn-firewall").apply { mkdirs(); Os.chmod(path, 0x1c0) }
                val token = UUID.randomUUID().toString().replace("-", "")
                val file = File(root, "warm-$token.json")
                val request = JSONObject().put("token", token).put("package", app.packageName)
                    .put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
                    .put("boot", boot()).put("appPid", Process.myPid()).put("appStart", processStart())
                    .put("deadline", SystemClock.elapsedRealtime() + 10_000).put("state", "PENDING")
                file.writeText(request.toString())
                Os.chmod(file.path, 0x180)
                descriptor = file
                val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerVpnFirewallHelper --warm ${PioneerRootDispatcher.quote(file.path)} </dev/null >/dev/null 2>&1 &"
                PioneerRootDispatcher.dispatch(command)
                log("VPN_FIREWALL_WARM_DISPATCHED; idle root worker overlaps wired startup")
            } catch (error: Throwable) {
                log("VPN_FIREWALL_WARM_UNAVAILABLE ${error.message ?: error.javaClass.simpleName}")
            } finally {
                launching.set(false)
            }
        }, "vpn-firewall-prewarm").apply { isDaemon = true; start() }
    }

    /** A failed connect is definitely unsubmitted. Any later error must fail this lease. */
    internal fun submit(context: Context, directory: File, current: () -> Boolean, log: (String) -> Unit): Boolean {
        val file = descriptor ?: return false
        val status = runCatching {
            check(file.length() in 1..8192)
            JSONObject(file.readText())
        }.getOrNull() ?: return false
        val app = context.applicationContext
        if (status.optString("state") != "READY" || status.optString("boot") != boot() ||
            status.optString("apk") != app.applicationInfo.sourceDir || status.optString("package") != app.packageName ||
            status.optInt("uid") != app.applicationInfo.uid || status.optInt("appPid") != Process.myPid() ||
            status.optString("appStart") != processStart() || file.name != "warm-${status.optString("token")}.json") return false
        val name = status.optString("socket")
        if (!name.matches(Regex("diplay-vpn-[0-9a-f-]{36}"))) return false
        LocalSocket().use { socket ->
            try {
                socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            } catch (_: IOException) { return false }
            socket.soTimeout = 2000
            val peer = socket.peerCredentials
            check(peer.uid == 0 && peer.pid == status.optInt("pid")) { "VPN_FIREWALL_WARM_OWNER_CHANGED" }
            check(current()) { "VPN_FIREWALL_CANCELLED" }
            // From the first write onward delivery is uncertain: never launch another helper.
            NativeNcmWarmProtocol.submitTask(socket.outputStream, directory.canonicalPath)
            NativeNcmWarmProtocol.requireAccepted(socket.inputStream)
            log("VPN_FIREWALL_WARM_ACCEPTED rootPid=${peer.pid}; reused idle ART process")
            return true
        }
    }

    private fun previousWorkerMayBeAlive(): Boolean {
        val file = descriptor ?: return false
        val status = runCatching {
            check(file.length() in 1..8192)
            JSONObject(file.readText())
        }.getOrNull() ?: return true
        // PENDING has no root PID yet; dispatch completion is uncertain, so do not duplicate it.
        val pid = status.optInt("pid", -1)
        if (pid <= 0) return true
        val process = File("/proc/$pid")
        return try {
            val owner = Os.stat(process.path)
            val stat = File(process, "stat").readText()
            owner.st_uid == 0 && stat.substringAfterLast(") ").split(' ')[19] == status.getString("rootStart")
        } catch (error: android.system.ErrnoException) {
            error.errno != android.system.OsConstants.ENOENT
        } catch (_: Throwable) {
            // Android may deny reads of another UID's process identity. This is not proof of exit.
            true
        }
    }

    private fun boot() = File("/proc/sys/kernel/random/boot_id").readText().trim()
    private fun processStart() = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]
}
