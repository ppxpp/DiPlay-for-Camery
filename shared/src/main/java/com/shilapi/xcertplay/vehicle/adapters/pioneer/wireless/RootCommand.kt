package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal enum class HotspotProbeAction { PREPARE, PREPARE_LOCAL_ONLY, RESUME_LOCAL_ONLY, START, FIVE_GHZ, FIREWALL, STATUS, STOP, STOP_OWNED, RELEASE }

/** Fixed commands; prewarming cannot authorize any network mutation. */
internal class RootCommand(private val context: Context, private val token: String,
    private val cancelled: () -> Boolean, private val log: (String) -> Unit,
    private val onTiming: (String, Long, String) -> Unit = { _, _, _ -> }) {
    private class Request(val output: File, val started: Long) {
        val failure=AtomicReference<Throwable?>()
        val cancel=File(output.path+".cancel")
        fun cancel() { cancel.writeText("cancel") }
        fun finished()=output.readText().contains("\nRESULT:")
        fun retire() {
            // Never remove cancellation of a request that a late dispatcher can still launch.
            if(finished()) {
                output.delete();cancel.delete()
                File(output.path+".running").delete()
            } else cancel()
        }
    }
    private val adaptationAuthorized=com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()
    fun prewarm(action: HotspotProbeAction) {
        require(action in setOf(HotspotProbeAction.PREPARE,HotspotProbeAction.PREPARE_LOCAL_ONLY))
        if(cancelled())return
        val assets=assets(action)
        HotspotRootWorker.ensure(context,rootClasspath(),token,assets.second,adaptationAuthorized,log)
        log("Vehicle hotspot worker prewarm dispatched; execution remains gated")
    }

    // Prewarm starts no operation and owns no network state. The shared worker is
    // retired after the final lease is released, rather than on every handle close.
    fun cancelPrewarm() = Unit

    fun run(action: HotspotProbeAction, cleanup: Boolean=false): String {
        check(cleanup || !cancelled()) { "Vehicle hotspot cancelled" }
        val request=create(action)
        val executing=SystemClock.elapsedRealtime()
        val deadline=executing+180_000
        var cancelSent=false
        try {
            while(SystemClock.elapsedRealtime()<deadline) {
                request.failure.get()?.let { request.cancel();throw IllegalStateException("Vehicle hotspot root dispatch failed",it) }
                if(!cleanup && cancelled() && !cancelSent) { request.cancel();cancelSent=true }
                val result=request.output.readText()
                if(result.contains("\nRESULT:")) {
                    if (!cleanup) HotspotHelperMetrics.parse(result).forEach { metric ->
                        runCatching { onTiming("hotspot_${action.name.lowercase()}_${metric.phase}", metric.millis,
                            if (cancelSent) "cancelled" else metric.result) }
                    }
                    check(cleanup || !cancelSent) { "Vehicle hotspot cancelled" }
                    if(!result.endsWith("RESULT: OK\n"))
                        result.lineSequence().filter { it.startsWith("localOnly=") || it.startsWith("apState=") }.forEach(log)
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

    private fun create(action: HotspotProbeAction): Request {
        val started=SystemClock.elapsedRealtime()
        val classpath=rootClasspath()
        val (gate,commandRunner)=assets(action)
        val output=File(context.filesDir,"pioneer-hotspot-$token-${UUID.randomUUID()}.txt").apply {
            writeText("REQUESTED_AT=$started\n");Os.chmod(path,384)
        }
        val request=Request(output,started)
        Thread({ runCatching {
            output.writeText("REQUESTED_AT=$started\nDISPATCHED_AT=${SystemClock.elapsedRealtime()}\n")
            HotspotRootWorker.enqueue(context,classpath,output,action,token,gate,commandRunner,adaptationAuthorized,log)
        }.onFailure { request.failure.set(it) } },"pioneer-hotspot-dispatch").apply { isDaemon=true;start() }
        return request
    }

    private fun assets(action: HotspotProbeAction): Pair<File?,File> {
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
        return gate to commandRunner
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
