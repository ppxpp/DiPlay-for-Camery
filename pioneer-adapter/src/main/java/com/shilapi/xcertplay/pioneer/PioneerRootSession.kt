package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.FileObserver
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID

/** The only app-side root entry point. One authenticated service per app owner, across CarPlay sessions. */
object PioneerRootSession {
    private val lock = Object()
    @Volatile private var descriptor: File? = null
    @Volatile private var cachedReady: JSONObject? = null
    @Volatile private var launchError: Throwable? = null
    @Volatile private var announcedRootPid = 0
    private val cancellations = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "root-ipc-cancel").apply { isDaemon = true } })

    fun start(context: Context) = synchronized(lock) {
        if (!PioneerUsbPolicy.matchesDevice()) return@synchronized
        if (liveCached() != null) return@synchronized
        val prior = descriptor?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        if (prior != null && bootstrapPending(prior, SystemClock.elapsedRealtime()) && launchError == null) return@synchronized
        if (prior != null && validReady(prior)) { markReady(context.applicationContext, prior); return@synchronized }
        val app = context.applicationContext
        findReady(app)?.let { markReady(app, it); return@synchronized }
        val root = File(app.filesDir, "root-init").apply { mkdirs(); Os.chmod(path, 0x1c0) }
        val token = UUID.randomUUID().toString()
        val file = File(root, "$token.json")
        val now = SystemClock.elapsedRealtime()
        file.writeText(JSONObject().put("token", token).put("camryAdaptation", CamryAdaptation.requestMode())
            .put("package", app.packageName).put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
            .put("boot", File("/proc/sys/kernel/random/boot_id").readText().trim())
            .put("appPid", Process.myPid()).put("appStart", processStart(Process.myPid()))
            .put("state", "PENDING").put("requestedAtMs", now).put("expiresAt", now + 60_000).toString())
        Os.chmod(file.path, 0x180)
        descriptor = file; launchError = null
        PioneerConnectionTasks.restart("root"); PioneerConnectionTasks.update("root", "RUNNING")
        val observer = object : FileObserver(root.path, FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == file.name) synchronized(lock) { lock.notifyAll() }
            }
        }.also { it.startWatching() }
        Thread({
            try {
                // The firmware Binder is used only to bootstrap this single Java root host.
                val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process -Xcompiler-option --compiler-filter=interpret-only /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerRootSessionHelper ${PioneerRootDispatcher.quote(file.path)} </dev/null >/dev/null 2>&1 &"
                PioneerRootDispatcher.dispatch(command)
                val deadline = now + 60_000
                while (descriptor == file && SystemClock.elapsedRealtime() < deadline) {
                    val state = runCatching { JSONObject(file.readText()) }.getOrNull()
                    if (state != null && validReady(state)) { markReady(app, state); break }
                    if (state?.optString("state") in setOf("ERROR", "CLOSED"))
                        error("ROOT_SERVICE_START_FAILED ${state?.optString("error")}")
                    synchronized(lock) { if (cachedReady == null) lock.wait(1000) }
                }
                if (descriptor == file && cachedReady == null) error("ROOT_SERVICE_READY_TIMEOUT")
            } catch (error: Throwable) {
                synchronized(lock) { if (descriptor == file && cachedReady == null) launchError = error }
            }
            finally { observer.stopWatching(); synchronized(lock) { lock.notifyAll() } }
        }, "root-service-launch").apply { isDaemon = true; start() }
    }

    internal fun bootstrapPending(value: JSONObject, now: Long): Boolean =
        value.optString("state") == "PENDING" && value.optLong("requestedAtMs", -1) in 0..now &&
            value.optLong("expiresAt") > now &&
            value.optLong("expiresAt") - value.optLong("requestedAtMs") in 1..60_000

    private class PeerChanged : IllegalStateException("ROOT_IPC_PEER_CHANGED")

    /** An old failed cancellation must never evict a newer discovered endpoint. */
    private fun invalidate(endpoint: JSONObject) = synchronized(lock) {
        val cached = cachedReady
        if (cached != null && cached.optString("token") == endpoint.optString("token") &&
            cached.optString("socket") == endpoint.optString("socket") && cached.optInt("pid") == endpoint.optInt("pid")) {
            cachedReady = null
        }
    }

    private fun liveCached(): JSONObject? {
        val cached = cachedReady ?: return null
        if (validReady(cached)) return cached
        invalidate(cached)
        return null
    }

    private fun markReady(app: Context, state: JSONObject) {
        cachedReady = state
        val pid = state.optInt("pid")
        if (pid != announcedRootPid) {
            announcedRootPid = pid
            PioneerUsbLaunchPolicy.rootReady(app)
            PioneerConnectionTasks.update("root", "COMPLETE")
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun findReady(app: Context): JSONObject? {
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        return File(app.filesDir, "root-init").listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }.take(16)
            .firstNotNullOfOrNull { file -> runCatching {
                JSONObject(file.readText()).takeIf {
                    it.optString("package") == app.packageName && it.optString("apk") == app.applicationInfo.sourceDir &&
                        it.optString("boot") == boot && validReady(it)
                }
            }.getOrNull() }
    }

    private fun processStart(pid: Int) = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
    private fun validReady(value: JSONObject): Boolean = runCatching {
        value.optString("state") == "READY" && value.optInt("appPid") == Process.myPid() &&
            value.optInt("uid") == Process.myUid() && value.optInt("ipcVersion") == PioneerRootIpc.VERSION &&
            value.optString("appStart") == processStart(Process.myPid()) && Os.stat("/proc/${value.getInt("pid")}").st_uid == 0 &&
            processStart(value.getInt("pid")) == value.getString("rootStart")
    }.getOrDefault(false)

    fun ready(context: Context, current: () -> Boolean): JSONObject {
        liveCached()?.let { check(current()) { "ROOT_IPC_CANCELLED" }; return it }
        start(context)
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            liveCached()?.let { check(current()) { "ROOT_IPC_CANCELLED" }; return it }
            check(current()) { "ROOT_IPC_CANCELLED" }
            // Recover discovery only. No business request has been sent at this point.
            val pending = descriptor?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            if (pending?.optString("state") == "PENDING" &&
                pending.optLong("expiresAt") <= SystemClock.elapsedRealtime()) start(context)
            launchError?.let { throw IllegalStateException("ROOT_SERVICE_DISPATCH_FAILED", it) }
            val state = descriptor?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            if (state != null && validReady(state)) {
                markReady(context.applicationContext, state)
                return state
            }
            if (state?.optString("state") in setOf("ERROR", "CLOSED")) {
                findReady(context.applicationContext)?.let { markReady(context.applicationContext, it); return it }
                error("ROOT_SERVICE_START_FAILED ${state?.optString("error")}")
            }
            synchronized(lock) { if (cachedReady == null && launchError == null) lock.wait(250) } // Bootstrap/cancellation only.
        }
        error("ROOT_SERVICE_READY_TIMEOUT")
    }

    @JvmStatic @JvmOverloads
    fun request(context: Context, operation: String, payload: JSONObject, timeoutMs: Long = 15_000,
                current: () -> Boolean = { true }): JSONObject {
        require(timeoutMs in 1..180_000)
        check(current()) { "ROOT_IPC_CANCELLED" }
        val status = ready(context.applicationContext, current)
        return exchange(status, operation, payload, timeoutMs, current)
    }

    private fun exchange(status: JSONObject, operation: String, payload: JSONObject, timeoutMs: Long,
                         current: () -> Boolean): JSONObject {
        val id = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val wire = JSONObject().put("version", PioneerRootIpc.VERSION).put("id", id)
            .put("token", status.getString("token")).put("operation", operation)
            .put("deadlineMs", deadline).put("payload", payload)
        var sent = false
        var completed = false
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(status.getString("socket"), LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 250
                val peer = socket.peerCredentials
                if (peer.uid != 0 || peer.pid != status.getInt("pid")) throw PeerChanged()
                check(current()) { "ROOT_IPC_CANCELLED" }
                // Even a lost acceptance frame cannot prove that a fully sent mutation was not executed.
                sent = true
                PioneerRootIpc.write(socket.outputStream, wire, PioneerRootIpc.MAX_REQUEST_BYTES)
                val reader = PioneerRootIpc.Reader(PioneerRootIpc.MAX_RESPONSE_BYTES)
                while (SystemClock.elapsedRealtime() < deadline) {
                    check(current()) { "ROOT_IPC_CANCELLED" }
                    val message = try { reader.read(socket.inputStream) } catch (_: SocketTimeoutException) { continue }
                    check(message.optInt("version") == PioneerRootIpc.VERSION && message.optString("id") == id) { "ROOT_IPC_RESPONSE_IDENTITY" }
                    if (message.optString("type") == "accepted") continue
                    check(message.optString("type") == "result") { "ROOT_IPC_RESPONSE_TYPE" }
                    if (!message.optBoolean("ok")) {
                        completed = true
                        val detail = message.optString("error", "ROOT_IPC_TASK_FAILED")
                        if (message.optString("completion") == "UNKNOWN") throw PioneerRootIpc.CompletionUnknown(detail)
                        error("${message.optString("completion", "FAILED")}: $detail")
                    }
                    val result = message.getJSONObject("result")
                    completed = true
                    check(current()) { "ROOT_IPC_CANCELLED" }
                    return result
                }
                error("ROOT_IPC_DEADLINE_EXCEEDED")
            }
        } catch (error: Exception) {
            // Never replay a command automatically: the operation may have already changed the vehicle.
            if ((error is java.io.IOException || error is PeerChanged) && !completed) invalidate(status)
            if (sent && !completed) {
                if (operation != "CANCEL") cancel(status, id)
                throw PioneerRootIpc.CompletionUnknown("ROOT_IPC_COMPLETION_UNCONFIRMED operation=$operation id=$id", error)
            }
            throw error
        }
    }

    private fun cancel(status: JSONObject, id: String) {
        // Pin the original authenticated endpoint; cancellation never bootstraps or targets a replacement owner.
        val endpoint = JSONObject(status.toString())
        runCatching { cancellations.execute { runCatching {
            exchange(endpoint, "CANCEL", JSONObject().put("requestId", id), 2000) { true }
        } } }
    }

    fun status(): JSONObject = cachedReady?.let { JSONObject(it.toString()) } ?: JSONObject().put("state", "NOT_READY")

}
