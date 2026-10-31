package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Single-flight, background-only root authorization. Never opens a consent window. */
object PioneerVpnPermissionGrant {
    private val taskLock = Any()
    private var nextTestDispatchDelayMs = 0L
    private var delayedTestGrantsRemaining = 0
    @Volatile private var verifiedSelfNetworks = emptySet<android.net.Network>()

    fun status(context: Context): JSONObject {
        val last = File(context.filesDir, "pioneer-debug/vpn-permission.json")
        val appOp = runCatching {
            val manager = context.getSystemService(android.app.AppOpsManager::class.java)
            android.app.AppOpsManager::class.java.getMethod("checkOpNoThrow", String::class.java,
                Int::class.javaPrimitiveType, String::class.java)
                .invoke(manager, "android:activate_vpn", context.applicationInfo.uid, context.packageName) as Int
        }.getOrNull()
        return JSONObject().put("preconsented", appOp == android.app.AppOpsManager.MODE_ALLOWED)
            .put("appOpMode", appOp ?: JSONObject.NULL)
            .put("conflict", runCatching { conflict(context) }.getOrDefault("VPN_CONFLICT_QUERY_FAILED") ?: JSONObject.NULL)
            .put("supportedDevice", PioneerUsbPolicy.matchesDevice())
            .put("lastTask", runCatching { if (last.length() in 1..32768) JSONObject(last.readText()) else JSONObject() }.getOrDefault(JSONObject()))
    }

    fun conflict(context: Context): String? {
        val alwaysOn = Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
        if (!alwaysOn.isNullOrEmpty() && alwaysOn != context.packageName) return "VPN_ALWAYS_ON_OTHER_APP"
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "VPN_CONNECTIVITY_SERVICE_MISSING"
        val vpnNetworks = manager.allNetworks.orEmpty().filter {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.toSet()
        if (vpnNetworks.isNotEmpty() && !verifiedSelfNetworks.containsAll(vpnNetworks)) return "VPN_ACTIVE_NETWORK_EXISTS"
        return null
    }

    /** Legacy authorization adapter; production grants include root-side system preparation. */
    internal fun authorize(prepare: () -> Boolean, current: () -> Boolean, grant: () -> Unit): Boolean = synchronized(taskLock) {
        check(current()) { "VPN authorization cancelled" }
        if (prepare()) return@synchronized true
        grant()
        check(current()) { "VPN authorization cancelled" }
        prepare()
    }

    fun grantForConnection(context: Context, stillCurrent: () -> Boolean): Boolean = synchronized(taskLock) {
        check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer device required" }
        var conflict = conflict(context)
        if (conflict == "VPN_ACTIVE_NETWORK_EXISTS") {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val before = manager.allNetworks.orEmpty().filter {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.toSet()
            check(before.size == 1) { "VPN_MULTIPLE_ACTIVE_NETWORKS" }
            // The root helper verifies getVpnConfig ownership for the target Android user.
            val inspection = execute(context.applicationContext, "INSPECT", true, stillCurrent)
            check(inspection.getJSONObject("helper").optBoolean("selfVpnOwner")) { "VPN_OWNER_UNCONFIRMED" }
            val after = manager.allNetworks.orEmpty().filter {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.toSet()
            check(before == after) { "VPN_NETWORK_CHANGED_DURING_INSPECTION" }
            verifiedSelfNetworks = after
            conflict = conflict(context)
        }
        check(conflict == null) { conflict ?: "VPN conflict" }
        if (VpnService.prepare(context) == null) {
            PioneerConnectionTasks.update("vpn-authorization", "REUSED")
            true
        } else {
            val result = execute(context.applicationContext, "GRANT", true, stillCurrent)
            check(stillCurrent()) { "VPN authorization cancelled" }
            result.getJSONObject("helper").let { it.optBoolean("appOpAllowed") && it.optBoolean("targetPrepared") }
        }
    }

    /** Idle-only TCP caller uses the same dispatcher and validation as the connection. */
    fun run(context: Context, action: String): JSONObject {
        require(action in setOf("STATUS", "INSPECT", "GRANT", "REVOKE", "REVOKE_FOR_RETRY_TEST", "REVOKE_FOR_RETRY_LIMIT_TEST"))
        if (action == "STATUS") return status(context)
        if (action == "INSPECT") return synchronized(taskLock) {
            execute(context.applicationContext, action) { true }
        }
        return PioneerDebugUpdateGuard.withCacheMaintenance {
            synchronized(taskLock) {
                val result = execute(context.applicationContext,
                    if (action.startsWith("REVOKE_FOR_RETRY")) "REVOKE" else action) { true }
                if (action.startsWith("REVOKE_FOR_RETRY")) {
                    nextTestDispatchDelayMs = 16000L
                    delayedTestGrantsRemaining = if (action == "REVOKE_FOR_RETRY_LIMIT_TEST") 5 else 1
                    result.put("nextGrantTestDispatchDelayMs", nextTestDispatchDelayMs)
                        .put("testDelayedGrantCount", delayedTestGrantsRemaining)
                }
                result
            }
        }
    }

    private fun execute(app: Context, action: String, reuse: Boolean = false, current: () -> Boolean): JSONObject {
        check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer device required" }
        check(current()) { "VPN authorization cancelled" }
        if (reuse) {
            PioneerConnectionTasks.update("permission-request", "RUNNING", "waiting-root")
            PioneerRootSession.ready(app, current)
            PioneerConnectionTasks.update("permission-request", "RUNNING")
        }
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        val root = File(app.filesDir, "vpn-permission").apply { mkdirs() }
        Os.chmod(root.path, 0x1c0)
        cleanupCompleted(root)
        if (action == "GRANT" || action == "REVOKE") {
            check(conflict(app) == null) { "VPN_CONFLICT" }
            requirePreviousFinished(root, boot, SystemClock.elapsedRealtime())
        }
        val token = UUID.randomUUID().toString()
        val directory = File(root, token).apply { check(mkdir()) }
        Os.chmod(directory.path, 0x1c0)
        val started = SystemClock.elapsedRealtime()
        val taskBudgetMs = 15000L
        val request = JSONObject().put("token", token).put("action", action).put("camryAdaptation", CamryAdaptation.requestMode()).put("package", app.packageName)
            .put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
            .put("boot", boot).put("appPid", Process.myPid())
            .put("appStart", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
            .put("requestedAtMs", started).put("deadline", started + taskBudgetMs)
        File(directory, "request.json").apply { writeText(request.toString()); Os.chmod(path, 0x180) }
        val error = AtomicReference<Throwable?>()
        val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
            "com.shilapi.xcertplay.pioneer.PioneerVpnPermissionHelper ${PioneerRootDispatcher.quote(directory.path)} </dev/null >/dev/null 2>&1 &"
        val report = JSONObject().put("action", action).put("token", token).put("startedAt", System.currentTimeMillis())
        val testDelay = if (action == "GRANT" && delayedTestGrantsRemaining > 0) {
            delayedTestGrantsRemaining--
            nextTestDispatchDelayMs
        } else 0L
        Thread({ runCatching {
            if (testDelay > 0) Thread.sleep(testDelay)
            if (reuse) PioneerRootSession.submit(app, directory, current)
            else PioneerRootDispatcher.dispatch(command)
        }.onFailure(error::set) }, "vpn-root-dispatch")
            .apply { isDaemon = true; start() }
        try {
            var result: JSONObject? = null
            while (SystemClock.elapsedRealtime() < started + taskBudgetMs + 500) {
                check(current()) { "VPN authorization cancelled" }
                error.get()?.let { throw IllegalStateException("VPN root dispatch rejected", it) }
                val file = File(directory, "result.json")
                result = runCatching { if (file.length() in 1..32768) JSONObject(file.readText()) else null }.getOrNull()
                if (result != null) break
                Thread.sleep(50)
            }
            check(result != null) { "VPN_ROOT_RESULT_UNCONFIRMED" }
            requireResult(result, request)
            report.put("helper", result)
            check(result.optString("code") == "COMPLETED") { result.optString("error", "VPN_ROOT_FAILED") }
            check(current()) { "VPN authorization cancelled" }
            if (action == "GRANT" || action == "REVOKE") {
                check(conflict(app) == null) { "VPN_CONFLICT_AFTER_GRANT" }
                if (action == "REVOKE") report.put("preparedAfter", VpnService.prepare(app) == null)
                else report.put("rootPermissionVerified", result.optBoolean("appOpAllowed"))
            }
            report.put("code", "COMPLETED")
            return report
        } catch (failure: Exception) {
            report.put("code", "FAILED").put("error", "${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            File(directory, "cancel").writeText("cancel")
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            val logs = File(app.filesDir, "pioneer-debug").apply { mkdirs() }
            File(logs, "vpn-permission.tmp").apply {
                writeText(report.toString()); check(renameTo(File(logs, "vpn-permission.json")))
            }
            PioneerDebugRuntime.log(app, "VPN_ROOT_AUTH action=$action code=${report.optString("code")} " +
                "prepared=${report.optBoolean("preparedAfter")} elapsedMs=${report.optLong("elapsedMs")} error=${report.optString("error")}")
        }
    }

    /** Only authenticated terminal results without unresolved children are eligible for retention cleanup. */
    internal fun cleanupCompleted(root: File, keep: Int = 16) {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val request = runCatching { JSONObject(File(dir, "request.json").readText()) }.getOrNull()
                ?: return@mapNotNull null
            val result = runCatching { JSONObject(File(dir, "result.json").readText()) }.getOrNull()
                ?: return@mapNotNull null
            if (runCatching { requireResult(result, request) }.isFailure ||
                result.optBoolean("unresolvedChild") ||
                (result.optString("code") != "COMPLETED" &&
                    !(result.optString("code") == "ERROR" && result.optBoolean("childrenFinished"))))
                return@mapNotNull null
            dir
        }.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.deleteRecursively() }
    }

    internal fun requireResult(result: JSONObject, request: JSONObject) {
        check(result.optString("token") == request.getString("token") && result.optInt("helperUid", -1) == 0 &&
            result.optString("package") == request.getString("package") && result.optInt("targetUid", -1) == request.getInt("uid") &&
            result.optString("action") == request.getString("action")) { "VPN helper identity mismatch" }
    }

    internal fun requirePreviousFinished(root: File, boot: String, now: Long) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val request = runCatching { JSONObject(File(dir, "request.json").readText()) }.getOrNull() ?: return@forEach
            if (request.optString("boot") != boot || request.optString("action") !in setOf("GRANT", "REVOKE")) return@forEach
            val result = runCatching { JSONObject(File(dir, "result.json").readText()) }.getOrNull()
            if (result == null) {
                check(now >= request.optLong("deadline", Long.MAX_VALUE) && !File(dir, "mutation").exists()) {
                    "VPN_PREVIOUS_TASK_UNCONFIRMED"
                }
            } else {
                requireResult(result, request)
                check(!result.optBoolean("unresolvedChild")) { "VPN_PREVIOUS_CHILD_UNCONFIRMED" }
            }
        }
    }
}
