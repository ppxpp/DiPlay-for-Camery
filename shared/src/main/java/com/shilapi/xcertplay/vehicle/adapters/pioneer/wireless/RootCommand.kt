package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import com.shilapi.xcertplay.pioneer.PioneerWirelessRootLauncher

internal enum class HotspotProbeAction { PREPARE, PREPARE_LOCAL_ONLY, RESUME_LOCAL_ONLY, START, FIVE_GHZ, FIREWALL, STATUS, STOP, STOP_OWNED, RELEASE }

/** Fixed commands; prewarming cannot authorize any network mutation. */
internal class RootCommand(private val context: Context, private val token: String,
    private val cancelled: () -> Boolean, private val log: (String) -> Unit) {
    private class Request(val action: HotspotProbeAction, val output: File, val started: Long, val deferred: Boolean) {
        val failure=AtomicReference<Throwable?>()
        val cancel=File(output.path+".cancel")
        val ready=File(output.path+".ready")
        val go=File(output.path+".go")
        fun cancel() { cancel.writeText("cancel") }
        fun finished()=output.readText().contains("\nRESULT:")
        fun retire() {
            // Never remove cancellation of a request that a late dispatcher can still launch.
            if(finished()) {
                output.delete();cancel.delete();ready.delete();go.delete()
                File(output.path+".running").delete()
            } else cancel()
        }
    }
    private val adaptationAuthorized=com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()
    private val warmLock=Any()
    private var warm: Request?=null
    private var warmClosed=false
    private var consumed=false

    fun prewarm(action: HotspotProbeAction) = synchronized(warmLock) {
        require(action in setOf(HotspotProbeAction.PREPARE,HotspotProbeAction.PREPARE_LOCAL_ONLY))
        if(warmClosed || consumed || warm!=null || cancelled()) return@synchronized
        warm=create(action,deferred=true)
        log("Vehicle hotspot prewarm dispatched; execution remains gated")
    }

    fun cancelPrewarm() = synchronized(warmLock) {
        warmClosed=true
        warm?.let { it.cancel();it.retire() }
        warm=null
    }

    fun run(action: HotspotProbeAction, cleanup: Boolean=false): String {
        check(cleanup || !cancelled()) { "Vehicle hotspot cancelled" }
        val prepared=synchronized(warmLock) { consumed=true;warm.also { warm=null } }
        val request=if(prepared!=null && !cleanup && prepared.action==action &&
            prepared.failure.get()==null && !prepared.finished() &&
            SystemClock.elapsedRealtime()-prepared.started<55_000) prepared else {
            prepared?.let { it.cancel();it.retire() }
            create(action,deferred=false)
        }
        val executing=SystemClock.elapsedRealtime()
        val deadline=executing+180_000
        var cancelSent=false
        try {
            if(request.deferred) {
                check(!cancelled()) { "Vehicle hotspot cancelled before authorization" }
                // The caller has persisted its owned AP lease and finished old-lease recovery.
                val authorization=File(request.go.path+".tmp")
                try {
                    authorization.writeText(token);Os.chmod(authorization.path,384)
                    check(authorization.renameTo(request.go)) { "Cannot publish hotspot authorization" }
                } finally { authorization.delete() }
                log("Vehicle hotspot prewarm authorized leadMs=${executing-request.started} ready=${request.ready.exists()}")
            }
            while(SystemClock.elapsedRealtime()<deadline) {
                request.failure.get()?.let { request.cancel();throw IllegalStateException("Vehicle hotspot root dispatch failed",it) }
                if(!cleanup && cancelled() && !cancelSent) { request.cancel();cancelSent=true }
                val result=request.output.readText()
                if(result.contains("\nRESULT:")) {
                    check(cleanup || !cancelSent) { "Vehicle hotspot cancelled" }
                    if(request.deferred && result.contains("localOnly=prewarm_failed_without_execution") && !cancelled()) {
                        request.cancel()
                        log("Vehicle hotspot prewarm failed before execution; retrying normal startup")
                        return run(action,cleanup)
                    }
                    check(result.endsWith("RESULT: OK\n")) { "Vehicle hotspot $action failed: ${result.lineSequence().lastOrNull { it.startsWith("ERROR ") } ?: "helper did not report success"}" }
                    result.lineSequence().filter { it.startsWith("localOnly=") }.forEach(log)
                    log("Vehicle hotspot action=$action completed elapsedMs=${SystemClock.elapsedRealtime()-executing} requestTotalMs=${SystemClock.elapsedRealtime()-request.started}")
                    return result
                }
                Thread.sleep(100)
            }
            request.cancel()
            error("Vehicle hotspot helper timed out; recovery record retained")
        } finally { request.retire() }
    }

    private fun create(action: HotspotProbeAction, deferred: Boolean): Request {
        val started=SystemClock.elapsedRealtime()
        val classpath=rootClasspath()
        val gate=if(action in setOf(HotspotProbeAction.PREPARE,HotspotProbeAction.PREPARE_LOCAL_ONLY)) {
            val file=File(context.codeCacheDir,"pioneer-ap-frequency-gate")
            val temp=File(context.codeCacheDir,"ap-gate-${UUID.randomUUID()}")
            try {
                context.assets.open("pioneer-ap-frequency-gate").use { source -> temp.outputStream().use { source.copyTo(it) } }
                Os.chmod(temp.path,448)
                check(temp.renameTo(file)) { "Cannot publish AP frequency gate" }
            } finally { temp.delete() }
            file
        } else null
        val commandRunner=File(context.codeCacheDir,"pioneer-command-runner")
        synchronized(RootCommand::class.java) {
            val temp=File(context.codeCacheDir,"command-runner-${UUID.randomUUID()}")
            try {
                context.assets.open("pioneer-command-runner").use { source -> temp.outputStream().use { source.copyTo(it) } }
                Os.chmod(temp.path,448)
                check(temp.renameTo(commandRunner)) { "Cannot publish hotspot command runner" }
            } finally { temp.delete() }
        }
        val output=File(context.filesDir,"pioneer-hotspot-$token-${UUID.randomUUID()}.txt").apply {
            writeText("REQUESTED_AT=$started\n");Os.chmod(path,384)
        }
        val request=Request(action,output,started,deferred)
        Thread({ runCatching {
            output.writeText("REQUESTED_AT=$started\nDISPATCHED_AT=${SystemClock.elapsedRealtime()}\n")
            PioneerWirelessRootLauncher.launchHotspot(context,classpath,output,action.name,token,gate,commandRunner,deferred,adaptationAuthorized)
        }.onFailure { request.failure.set(it) } },"pioneer-hotspot-dispatch").apply { isDaemon=true;start() }
        return request
    }

    private fun rootClasspath(): String {
        val apk=File(context.applicationInfo.sourceDir)
        val dir=File(context.filesDir,"pioneer-wireless-dex-${apk.lastModified()}-${apk.length()}").apply { mkdirs();Os.chmod(path,448) }
        val file=File(dir,"classes.dex")
        if(!file.isFile) {
            val tmp=File(dir,"classes.${UUID.randomUUID()}.tmp")
            context.assets.open("pioneer-hotspot-helper.dex").use { src -> tmp.outputStream().use { src.copyTo(it) } }
            Os.chmod(tmp.path,384)
            check(tmp.renameTo(file)) { "Cannot publish vehicle helper DEX" }
        }
        return file.path
    }
}
