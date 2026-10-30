package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import com.shilapi.xcertplay.pioneer.PioneerWirelessRootLauncher
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** One root runtime per app process. Each request retains its own cancellation and result. */
internal object HotspotRootWorker {
    private data class Worker(val output: File, val inbox: File, val started: Long,
        val failure: AtomicReference<Throwable?> = AtomicReference())
    private var current: Worker? = null

    @Synchronized fun ensure(context: Context, classpath: String, token: String, runner: File,
        authorized: Boolean, log: (String)->Unit): File {
        current?.let { worker ->
            val running=File(worker.output.path+".running")
            val alive=runCatching { HotspotRequestIdentity.running(running) }.getOrDefault(false)
            if(worker.failure.get()==null && !File(worker.output.path+".exited").exists() &&
                (alive || (!running.exists() && SystemClock.elapsedRealtime()-worker.started<20_000)))return worker.inbox
        }
        val output=File(context.filesDir,"pioneer-hotspot-$token-worker-${UUID.randomUUID()}.worker")
        output.writeText("${Process.myPid()}:${File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]}")
        Os.chmod(output.path,384)
        val inbox=File(output.path+".inbox").apply { check(mkdir());Os.chmod(path,448) }
        val worker=Worker(output,inbox,SystemClock.elapsedRealtime())
        current=worker
        Thread({ runCatching {
            PioneerWirelessRootLauncher.launchHotspot(context,classpath,output,"WORKER",token,null,runner,false,authorized)
        }.onFailure { worker.failure.set(it) } },"pioneer-hotspot-worker-launch").apply { isDaemon=true;start() }
        log("Vehicle hotspot root worker dispatched; ownerPid=${Process.myPid()}")
        return inbox
    }

    fun enqueue(context: Context, classpath: String, output: File, action: HotspotProbeAction,
        token: String, gate: File?, runner: File, authorized: Boolean, log: (String)->Unit) {
        check(authorized && (action in setOf(HotspotProbeAction.STOP_OWNED,HotspotProbeAction.RELEASE) ||
            com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled())) { "Camry adaptation required" }
        val args=JSONArray(listOf(action.name,context.packageName,output.canonicalPath,token,
            gate?.canonicalPath ?: "-","immediate","camry-enabled",runner.canonicalPath))
        val deadline=SystemClock.elapsedRealtime()+180_000
        repeat(2) { attempt ->
            val (worker,request)=synchronized(this) {
                val inbox=ensure(context,classpath,token,runner,authorized,log)
                val request=File(inbox,output.name+".request")
                val temp=File(request.path+".tmp")
                try {
                    temp.writeText(args.toString());Os.chmod(temp.path,384)
                    check(temp.renameTo(request)) { "Cannot publish hotspot worker request" }
                } finally { temp.delete() }
                checkNotNull(current) to request
            }
            log("Vehicle hotspot worker request queued action=$action")
            while(SystemClock.elapsedRealtime()<deadline) {
                if(output.readText().contains("\nRESULT:"))return
                worker.failure.get()?.let { throw IllegalStateException("Hotspot worker launch failed",it) }
                val identity=File(worker.output.path+".running")
                val alive=runCatching { HotspotRequestIdentity.running(identity) }.getOrDefault(false)
                val stopped=File(worker.output.path+".exited").exists() ||
                    (!alive && (identity.exists() || SystemClock.elapsedRealtime()-worker.started>=20_000))
                if(stopped) {
                    if(output.readText().contains("\nRESULT:"))return
                    // Never replay a mutation that may have begun. Its journal is
                    // recovered under the operation lock by the next cleanup request.
                    check(!File(output.path+".running").exists()) { "Hotspot root worker exited during operation" }
                    request.delete()
                    check(attempt==0) { "Hotspot root worker did not start" }
                    break
                }
                Thread.sleep(100)
            }
        }
        error("Hotspot worker request timed out")
    }
}
