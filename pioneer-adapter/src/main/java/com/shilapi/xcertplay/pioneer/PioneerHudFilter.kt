package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only the navigation worker renews this lease. A separate timer would conceal a stalled worker. */
internal class PioneerHudFilter(context: Context) {
    private val app = context.applicationContext
    private val uid = Process.myUid()
    private val pid = Process.myPid()
    private val boot by lazy { File("/proc/sys/kernel/random/boot_id").readText().trim() }
    private val start by lazy { PioneerHudFilterHelper.start(pid) }
    private val area = File(PioneerHudFilterHelper.ROOT, uid.toString())
    @Volatile private var descriptor: File? = null
    @Volatile private var launchError: String? = null
    private var token = ""
    private var nextLaunchMs = 0L
    private var lastWriteMs = -HEARTBEAT_MS
    private var lastActive = false
    private var writtenDeadline = 0L
    private var acknowledged = false
    private var feedback = JSONObject()
    private val main = Handler(Looper.getMainLooper())
    private var probe = 0L
    private var probePending = false
    @Volatile private var mainAck = 0L
    @Volatile private var mainAckMs = 0L

    fun maintain(active: Boolean) {
        val now = SystemClock.elapsedRealtime()
        try {
            if (active && !hostAlive() && !launchPending(now) && now >= nextLaunchMs) launch(now)
            if (descriptor == null || !hostAlive()) { acknowledged = false; return }
            if (active != lastActive || active && now - lastWriteMs >= HEARTBEAT_MS) {
                if (active && !mainResponsive(now)) { readAcknowledgement(now); return }
                val controlDir = File(area, "control")
                check(Os.lstat(controlDir.path).let { it.st_uid == uid && it.st_mode and 0x12 == 0 })
                val lease = JSONObject().put("version", 1).put("token", token).put("boot", boot)
                    .put("pid", pid).put("start", start).put("active", active)
                    .put("issuedMs", now).put("expiresMs", if (active) now + TIMEOUT_MS else 0)
                val temp = File(controlDir, "lease.json.tmp")
                temp.writeText(lease.toString()); Os.chmod(temp.path, 0x1a4)
                Os.rename(temp.path, File(controlDir, "lease.json").path)
                lastWriteMs = now; lastActive = active
                writtenDeadline = if (active) now + TIMEOUT_MS else 0
                probePending = false
            }
            readAcknowledgement(now)
        } catch (error: Exception) {
            acknowledged = false
            launchError = error.toString()
        }
    }

    fun ready(): Boolean = acknowledged && lastActive && SystemClock.elapsedRealtime() < writtenDeadline &&
        feedback.optLong("expiresMs") > SystemClock.elapsedRealtime() &&
        SystemClock.elapsedRealtime() - feedback.optLong("healthMs") in 0..6000 && hostAlive() && tpsAlive(feedback)

    fun release() { maintain(false); acknowledged = false }

    private fun mainResponsive(now: Long): Boolean {
        if (!probePending || mainAck == probe && now - mainAckMs > 1000) {
            val next = ++probe
            probePending = true
            main.post { mainAckMs = SystemClock.elapsedRealtime(); mainAck = next }
        }
        return mainAck == probe && now - mainAckMs in 0..1000
    }

    private fun launch(now: Long) {
        nextLaunchMs = now + 15000
        acknowledged = false; lastActive = false; writtenDeadline = 0; lastWriteMs = -HEARTBEAT_MS
        launchError = null
        val root = File(app.filesDir, "hud-filter").apply { mkdirs(); Os.chmod(path, 0x1c0) }
        token = UUID.randomUUID().toString()
        val request = JSONObject().put("version", 1).put("token", token).put("boot", boot)
            .put("uid", uid).put("appPid", pid).put("appStart", start).put("package", app.packageName)
            .put("apk", app.applicationInfo.sourceDir).put("camryAdaptation", CamryAdaptation.requestMode())
            .put("state", "STARTING").put("requestedMs", now)
        val file = File(root, "$token.json")
        file.writeText(request.toString()); Os.chmod(file.path, 0x180)
        descriptor = file
        Thread({
            try {
                val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerHudFilterHelper ${PioneerRootDispatcher.quote(file.path)} </dev/null >/dev/null 2>&1 &"
                PioneerRootDispatcher.dispatch(command)
            } catch (error: Exception) { launchError = error.toString() }
        }, "diplay-hud-filter-launch").apply { isDaemon = true; start() }
    }

    private fun hostAlive(): Boolean = runCatching {
        val file = descriptor ?: return false
        val host = JSONObject(PioneerHudFilterHelper.read(file, 8192))
        if (host.optString("state") == "CLOSED" && host.has("error")) launchError = host.optString("error")
        if (host.has("runtimeError")) launchError = host.optString("runtimeError")
        host.optString("token") == token && host.optString("boot") == boot && host.optString("state") == "RUNNING" &&
            Os.stat("/proc/${host.getInt("helperPid")}").st_uid == 0 &&
            PioneerHudFilterHelper.start(host.getInt("helperPid")) == host.getString("helperStart")
    }.getOrDefault(false)

    private fun launchPending(now: Long): Boolean = runCatching {
        val file = descriptor ?: return false
        val request = JSONObject(PioneerHudFilterHelper.read(file, 8192))
        request.optString("token") == token && request.optString("state") == "STARTING" &&
            now - request.getLong("requestedMs") in 0..60000
    }.getOrDefault(false)

    private fun tpsAlive(state: JSONObject): Boolean = runCatching {
        val target = state.getInt("tpsPid")
        Os.stat("/proc/$target").st_uid == 1000 && PioneerHudFilterHelper.start(target) == state.getString("tpsStart") &&
            File("/proc/$target/cmdline").readText().substringBefore('\u0000') == PioneerHudFilterHelper.TPS
    }.getOrDefault(false)

    private fun readAcknowledgement(now: Long) {
        val status = File(area, "feedback/status.json")
        val stat = Os.lstat(status.path)
        check(stat.st_uid == 1000 && stat.st_mode and 0x12 == 0)
        feedback = JSONObject(PioneerHudFilterHelper.read(status, 4096))
        acknowledged = feedback.optString("token") == token && feedback.optString("boot") == boot &&
            feedback.optBoolean("installed") && feedback.optBoolean("active") &&
            feedback.optLong("expiresMs") > now && feedback.optLong("expiresMs") <= writtenDeadline && tpsAlive(feedback)
        if (acknowledged) launchError = null
    }

    fun snapshot(): JSONObject = JSONObject().put("ready", ready()).put("requested", lastActive)
        .put("heartbeatMs", HEARTBEAT_MS).put("timeoutMs", TIMEOUT_MS)
        .put("expiresAtElapsedMs", writtenDeadline).put("error", launchError ?: JSONObject.NULL)
        .put("blockedFrames", feedback.optLong("blockedFrames")).put("blockedExits", feedback.optLong("blockedExits"))
        .put("tpsPid", feedback.optInt("tpsPid"))

    companion object {
        private const val HEARTBEAT_MS = 5000L
        private const val TIMEOUT_MS = 10000L
    }
}
