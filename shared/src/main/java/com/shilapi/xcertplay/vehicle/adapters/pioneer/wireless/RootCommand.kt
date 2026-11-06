package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.UUID

internal enum class HotspotProbeAction { PREPARE, PREPARE_LOCAL_ONLY, RESUME_LOCAL_ONLY, START, FIVE_GHZ, FIREWALL, STATUS, STOP, STOP_OWNED, RELEASE }

/** Fixed commands; prewarming cannot authorize any network mutation. */
internal class RootCommand(private val context: Context, private val token: String,
    private val cancelled: () -> Boolean, private val log: (String) -> Unit,
    private val onTiming: (String, Long, String) -> Unit = { _, _, _ -> }) {
    private val adaptationAuthorized=com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()
    fun prewarm(action: HotspotProbeAction) {
        require(action in setOf(HotspotProbeAction.PREPARE,HotspotProbeAction.PREPARE_LOCAL_ONLY))
        if (cancelled()) return
        Thread({
            runCatching {
                com.shilapi.xcertplay.pioneer.PioneerRootSession.request(context,"HOTSPOT_WARM",
                    org.json.JSONObject(),30_000) { !cancelled() }
            }.onFailure { log("Vehicle hotspot prewarm unavailable: ${it.message}") }
        },"pioneer-hotspot-prewarm").apply { isDaemon=true; start() }
    }
    fun cancelPrewarm() = Unit

    fun run(action: HotspotProbeAction, cleanup: Boolean=false): String {
        check(cleanup || !cancelled()) { "Vehicle hotspot cancelled" }
        val started=SystemClock.elapsedRealtime()
        val (gate, runner)=assets(action)
        val payload=org.json.JSONObject().put("action",action.name).put("token",token)
            .put("runner",runner.canonicalPath).put("adaptationAuthorized",adaptationAuthorized)
        gate?.let { payload.put("gate",it.canonicalPath) }
        val result=HotspotRootWorker.execute(context,payload) { cleanup || !cancelled() }.getString("text")
        check(cleanup || !cancelled()) { "Vehicle hotspot cancelled" }
        if (!cleanup) HotspotHelperMetrics.parse(result).forEach { metric ->
            runCatching { onTiming("hotspot_${action.name.lowercase()}_${metric.phase}",metric.millis,metric.result) }
        }
        check(result.endsWith("RESULT: OK\n")) {
            "Vehicle hotspot $action failed: ${result.lineSequence().lastOrNull { it.startsWith("ERROR ") } ?: "helper did not report success"}"
        }
        result.lineSequence().filter { it.startsWith("localOnly=") }.forEach(log)
        log("Vehicle hotspot action=$action completed elapsedMs=${SystemClock.elapsedRealtime()-started}")
        return result
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

}
