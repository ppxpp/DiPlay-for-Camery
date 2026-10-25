package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One root ART process for initialization only; no data-plane or cross-connection keepalive. */
object PioneerRootSession {
    private val lock = Any()
    @Volatile private var descriptor: File? = null
    @Volatile private var launchError: Throwable? = null
    private val finishing = AtomicBoolean()

    fun start(context: Context) = synchronized(lock) {
        if (!PioneerUsbPolicy.matchesDevice()) return@synchronized
        val prior = descriptor?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        if (prior != null && prior.optString("state") !in setOf("CLOSED", "ERROR") &&
            SystemClock.elapsedRealtime() < prior.optLong("expiresAt")) return@synchronized
        val app = context.applicationContext
        launchError = null
        finishing.set(false)
        val root = File(app.filesDir, "root-init").apply { mkdirs(); Os.chmod(path, 0x1c0) }
        val token = UUID.randomUUID().toString()
        val file = File(root, "$token.json")
        val started = SystemClock.elapsedRealtime()
        file.writeText(JSONObject().put("token", token).put("package", app.packageName)
            .put("uid", app.applicationInfo.uid).put("apk", app.applicationInfo.sourceDir)
            .put("boot", File("/proc/sys/kernel/random/boot_id").readText().trim())
            .put("appPid", Process.myPid()).put("appStart", File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19])
            .put("state", "PENDING").put("expiresAt", started + 180_000).put("requestedAtMs", started).toString())
        Os.chmod(file.path, 0x180)
        descriptor = file
        PioneerConnectionTasks.restart("root")
        PioneerConnectionTasks.update("root", "RUNNING")
        Thread({
            try {
                val command = "CLASSPATH=${PioneerRootDispatcher.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerRootSessionHelper ${PioneerRootDispatcher.quote(file.path)} </dev/null >/dev/null 2>&1 &"
                PioneerConnectionTrace.mark("root-init-dispatched")
                PioneerRootDispatcher.dispatch(command)
                Thread({ wakeStoppedSessions(app) }, "root-init-reap").apply { isDaemon = true; start() }
                val until = SystemClock.elapsedRealtime() + 30_000
                while (SystemClock.elapsedRealtime() < until && descriptor == file) {
                    val state = runCatching { JSONObject(file.readText()).optString("state") }.getOrNull()
                    if (finishing.get()) break
                    if (state == "READY") {
                        PioneerUsbLaunchPolicy.rootReady(app)
                        PioneerConnectionTasks.update("root", "COMPLETE")
                        PioneerConnectionTrace.mark("root-init-ready")
                        break
                    }
                    if (state in setOf("CLOSED", "ERROR")) break
                    Thread.sleep(50)
                }
            } catch (error: Throwable) {
                launchError = error
                PioneerConnectionTasks.update("root", "FAILED", error.message.orEmpty())
            }
        }, "root-init-launch").apply { isDaemon = true; start() }
    }

    fun ready(context: Context, current: () -> Boolean): JSONObject {
        start(context)
        val until = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < until) {
            check(current()) { "ROOT_INIT_CANCELLED" }
            launchError?.let { throw IllegalStateException("ROOT_INIT_DISPATCH_FAILED", it) }
            val status = descriptor?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            if (status?.optString("state") == "CLOSED") { start(context); continue }
            val stopping = descriptor?.let { File(it.path + ".stop").exists() } == true
            if (status?.optString("state") == "READY" && !stopping) {
                check(status.optInt("uid") == context.applicationInfo.uid && status.optInt("appPid") == Process.myPid() &&
                    status.optString("apk") == context.applicationInfo.sourceDir) { "ROOT_INIT_OWNER_CHANGED" }
                PioneerConnectionTasks.update("root", "COMPLETE")
                return status
            }
            check(status?.optString("state") != "ERROR") { "ROOT_INIT_STOPPED" }
            Thread.sleep(50)
        }
        error("ROOT_INIT_READY_TIMEOUT")
    }

    /** Successful submission means the root has accepted this exact task, never dispatch a duplicate. */
    fun submit(context: Context, directory: File, current: () -> Boolean) {
        val status = ready(context, current)
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(status.getString("socket"), LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = 3000
            check(socket.peerCredentials.uid == 0 && socket.peerCredentials.pid == status.getInt("pid")) { "ROOT_INIT_PEER_CHANGED" }
            check(current()) { "ROOT_INIT_CANCELLED" }
            PioneerRootTaskProtocol.submitTask(socket.outputStream, directory.canonicalPath)
            PioneerRootTaskProtocol.requireAccepted(socket.inputStream)
        }
    }

    fun status(): JSONObject = descriptor?.let { file -> runCatching {
        JSONObject(file.readText()).put("stopRequested", File(file.path + ".stop").exists())
    }.getOrNull() } ?: JSONObject()

    /** Wake an old API25 accept() after its owner monitor has requested stop. Never submit work. */
    private fun wakeStoppedSessions(app: Context) {
        val root = File(app.filesDir,"root-init")
        root.listFiles().orEmpty().filter { it.name.endsWith(".json") && it != descriptor }
            .sortedByDescending { it.lastModified() }.take(16).forEach { file ->
                runCatching {
                    val old = JSONObject(file.readText())
                    if (old.optString("package") != app.packageName || old.optInt("uid") != app.applicationInfo.uid ||
                        old.optString("state") != "READY" || !File(file.path+".stop").exists()) return@runCatching
                    val name = old.optString("socket")
                    if (!name.matches(Regex("diplay-init-[0-9a-f-]{36}"))) return@runCatching
                    LocalSocket().use { socket ->
                        socket.connect(LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT))
                        check(socket.peerCredentials.uid == 0 && socket.peerCredentials.pid == old.optInt("pid"))
                        socket.outputStream.write('\n'.code); socket.outputStream.flush()
                    }
                }
            }
    }

    fun finish(context: Context) {
        if (!finishing.compareAndSet(false, true)) return
        // A private stop marker also handles a launch that is not READY yet. No second process.
        descriptor?.let { file ->
            if (status().optString("state") == "PENDING") PioneerConnectionTasks.update("root", "SKIPPED")
            File(file.path + ".stop").apply { writeText(file.nameWithoutExtension); Os.chmod(path, 0x180) }
        }
    }
}
