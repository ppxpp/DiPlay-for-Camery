package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.wifi.WifiConfiguration
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Root-only helper, launched as app_process by pioneer.pfilesdoperator.
 * It drives the vendor SoftAP, injects the vendor `frequency` band selector,
 * installs the CARNET rules for bcm1, and writes a private result file for the connection owner.
 */
object HotspotProbeHelper {
    private const val MAX_LINES = 6000
    private const val MAX_OUTPUT = 30_000
    private var ruleTag = ""
    private var leaseToken = ""
    @Volatile private var cancellation: File? = null
    private lateinit var commandRunner: File
    private lateinit var commandGuard: File
    private var workerOwnerAlive: (() -> Boolean)? = null
    private fun checkCancelled() { check(workerOwnerAlive?.invoke()!=false && cancellation?.exists()!=true) { "Vehicle hotspot cancelled" } }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if(args.getOrNull(0)=="WORKER")worker(args) else execute(args)
        } finally { System.exit(0) }
    }

    private fun worker(args: Array<String>) {
        check(Process.myUid()==0 && !Process.is64Bit() && args.getOrNull(6)=="camry-enabled")
        val output=File(args[2])
        val owner=Os.stat(output.path).st_uid
        check(android.system.OsConstants.S_ISREG(Os.lstat(output.path).st_mode) && Os.lstat(output.path).st_nlink==1L)
        val files=output.canonicalFile.parentFile!!
        val inbox=File(output.path+".inbox")
        check(inbox.canonicalFile.parentFile==files && Os.stat(inbox.path).st_uid==owner)
        val ownerIdentity=output.readText().trim()
        val parts=ownerIdentity.split(':')
        check(parts.size==2 && parts[0].toInt()>1)
        val appPid=parts[0].toInt()
        fun ownerAlive()=runCatching {
            Os.stat("/proc/$appPid").st_uid==owner && processStart(appPid)==parts[1]
        }.getOrDefault(false)
        fun ownedWrite(file: File, text: String) {
            val temp=File(file.path+".tmp")
            temp.writeText(text);Os.chmod(temp.path,384);Os.chown(temp.path,owner,-1)
            check(temp.renameTo(file))
        }
        ownedWrite(File(output.path+".running"),"${Process.myPid()}:${processStart(Process.myPid())}")
        workerOwnerAlive=::ownerAlive
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val watcher=Thread({
            while(running.get()) {
                if(!ownerAlive()) { cancellation?.let { runCatching { it.writeText("owner exited") } };break }
                Thread.sleep(200)
            }
        },"hotspot-owner-watch").apply { isDaemon=true;start() }
        try {
            // Read-only initialization; no lease or Wi-Fi mutation until a queued action.
            wifi();connectivity();PioneerLocalOnlyPolicy.rules()
            ownedWrite(File(output.path+".ready"),Process.myPid().toString())
            var lastOperation=android.os.SystemClock.elapsedRealtime()
            var operated=false
            while(ownerAlive()) {
                val request=inbox.listFiles().orEmpty().filter { it.name.endsWith(".request") }.sortedBy { it.name }.firstOrNull()
                if(request==null) {
                    val idle=android.os.SystemClock.elapsedRealtime()-lastOperation
                    if(!File(files,"pioneer-wireless-hotspot.json").exists() && idle>(if(operated)5_000 else 45_000))break
                    Thread.sleep(50);continue
                }
                try {
                    val info=Os.lstat(request.path)
                    check(info.st_uid==owner && info.st_nlink==1L && android.system.OsConstants.S_ISREG(info.st_mode))
                    val json=org.json.JSONArray(request.readText())
                    check(json.length()==8)
                    val command=Array(8) { json.getString(it) }
                    val action=HotspotProbeAction.valueOf(command[0])
                    val result=File(command[2])
                    val token=command[3]
                    check(token.matches(Regex("[0-9a-f]{32}")) && command[1]==args[1] && command[5]=="immediate" && command[6]==args[6] && command[7]==args[7])
                    check(result.canonicalFile.parentFile==files && result.name.startsWith("pioneer-hotspot-$token-") && result.name.endsWith(".txt"))
                    val resultInfo=Os.lstat(result.path)
                    check(resultInfo.st_uid==owner && resultInfo.st_nlink==1L && android.system.OsConstants.S_ISREG(resultInfo.st_mode))
                    if(command[4]!="-")check(action in setOf(HotspotProbeAction.PREPARE,HotspotProbeAction.PREPARE_LOCAL_ONLY) &&
                        File(command[4]).canonicalFile==File(files.parentFile,"code_cache/pioneer-ap-frequency-gate").canonicalFile)
                    execute(command)
                    operated=true
                    lastOperation=android.os.SystemClock.elapsedRealtime()
                } finally { request.delete() }
            }
        } finally {
            running.set(false);watcher.interrupt()
            ownedWrite(File(output.path+".exited"),"exited")
        }
    }

    private fun execute(args: Array<String>) {
        val helperEntered = android.os.SystemClock.elapsedRealtime()
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { message ->
            if (lines.size < MAX_LINES) lines += message
        }
        val output = args.getOrNull(2)?.let(::File)
        var operationChannel: java.nio.channels.FileChannel? = null
        val deferred=args.getOrNull(5)=="deferred"
        var prewarmCompleted=false
        try {
            val action = args.getOrNull(0)?.let(HotspotProbeAction::valueOf)
                ?: error("missing action")
            val packageName = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: error("missing package name")
            check(Process.myUid() == 0) { "helper is not root: uid=${Process.myUid()}" }
            check(output != null) { "missing output file" }
            check(args.getOrNull(6)=="camry-enabled" && !Process.is64Bit()) { "Camry ARM32 request authorization required" }
            val token = args.getOrNull(3).orEmpty()
            check(token.matches(Regex("[0-9a-f]{32}"))) { "Invalid hotspot lease" }
            commandRunner=File(checkNotNull(args.getOrNull(7)) { "Missing hotspot command runner" })
            val runnerInfo=Os.lstat(commandRunner.path)
            check(commandRunner.canonicalFile.parentFile==File(output.parentFile!!.parentFile,"code_cache").canonicalFile &&
                commandRunner.name=="pioneer-command-runner" &&
                runnerInfo.st_uid==Os.stat(output.parentFile!!.path).st_uid &&
                android.system.OsConstants.S_ISREG(runnerInfo.st_mode) && runnerInfo.st_nlink==1L) {
                "Invalid hotspot command runner identity"
            }
            commandGuard=File(output.parentFile,"pioneer-hotspot-command.lock")
            log("localOnly=worker pid=${Process.myPid()} action=${action.name}")
            val requestHeader=output.readText()
            val requestedAt=requestHeader.lineSequence().firstOrNull { it.startsWith("REQUESTED_AT=") }
                ?.substringAfter('=')?.toLongOrNull()
            val dispatchedAt=requestHeader.lineSequence().firstOrNull { it.startsWith("DISPATCHED_AT=") }
                ?.substringAfter('=')?.toLongOrNull()
            if(requestedAt!=null && dispatchedAt!=null) {
                log("localOnly=timing phase=request_files elapsedMs=${dispatchedAt-requestedAt}")
                metric("request_files",dispatchedAt-requestedAt,"complete",log)
                log("localOnly=timing phase=dispatch_to_entry elapsedMs=${helperEntered-dispatchedAt}")
                metric("dispatch_to_entry",helperEntered-dispatchedAt,"complete",log)
            }
            leaseToken=token
            ruleTag = "diplay-ap-${Os.stat(output.parentFile!!.path).st_uid}-$token"
            cancellation = File(output.path+".cancel")
            // Publish identity before checking cancellation. Recovery first cancels, then reads
            // this marker: a late dispatcher cannot mutate after recovery has moved on.
            val running=File(output.path+".running")
            val runningTemp=File(output.path+".running.tmp")
            try {
                runningTemp.writeText("${Process.myPid()}:${processStart(Process.myPid())}")
                Os.chmod(runningTemp.path,384)
                Os.chown(runningTemp.path,Os.stat(output.parentFile!!.path).st_uid,-1)
                check(runningTemp.renameTo(running)) { "Cannot publish hotspot helper identity" }
            } finally { runningTemp.delete() }
            if(deferred) {
                check(action==HotspotProbeAction.PREPARE || action==HotspotProbeAction.PREPARE_LOCAL_ONLY)
                checkCancelled()
                // Read-only initialization. Do not take the operation lock while waiting:
                // previous-lease recovery must be able to use another helper concurrently.
                wifi();connectivity();PioneerLocalOnlyPolicy.rules()
                check(sh("true").endsWith("[exit=0]")) { "Prewarm shell initialization failed" }
                val ready=File(output.path+".ready")
                ready.writeText(android.os.SystemClock.elapsedRealtime().toString())
                Os.chmod(ready.path,384);Os.chown(ready.path,Os.stat(output.parentFile!!.path).st_uid,-1)
                log("localOnly=timing phase=prewarm_init elapsedMs=${android.os.SystemClock.elapsedRealtime()-helperEntered}")
                metric("prewarm_init",android.os.SystemClock.elapsedRealtime()-helperEntered,"complete",log)
                timed("prewarm_authorization_wait",log) {
                    HotspotPrewarmAuthorization.await(File(output.path+".go"),token,::checkCancelled,
                        { android.os.SystemClock.elapsedRealtime() },{ Thread.sleep(25) })
                }
            }
            prewarmCompleted=true
            // Every privileged request, including recovery STOP/RELEASE, shares this
            // process lock. Even an unobservable or stuck old helper cannot overlap a
            // new mutation. Lock ownership is released by process death as well.
            operationChannel=FileOutputStream(File(output.parentFile,"pioneer-hotspot-helper.lock"),true).channel
            timed("operation_lock_wait",log) { checkNotNull(operationChannel).lock() }
            // A previous Java helper may have died while its native supervisor still owns
            // descendants. Do not mutate anything until their shared command lock is free.
            FileOutputStream(commandGuard,true).channel.use { channel -> channel.lock().release() }
            checkCancelled()
            if(action==HotspotProbeAction.PREPARE || action==HotspotProbeAction.PREPARE_LOCAL_ONLY) {
                verifyCreation(output,token,requestStart=false)
            }
            log("uid=${Process.myUid()} pid=${Process.myPid()} action=${action.name} package=$packageName")
            when (action) {
                HotspotProbeAction.PREPARE_LOCAL_ONLY -> {
                    timed("policy_prepare",log) { localOnly(log,refresh=false) }
                    timed("local_firewall_prepare",log) { prepareLocalTraffic(log) }
                    timed("oem_ap_start",log) { prepare(packageName,output,args.getOrNull(4)?.takeUnless { it=="-" },log) }
                    timed("frequency",log) { fiveGhz(log,verifyRoute=false) }
                    timed("local_route_firewall",log) { firewall(log) }
                    timed("policy_ready",log) { localOnly(log,refresh=true) }
                }
                HotspotProbeAction.PREPARE -> {
                    prepareLocalTraffic(log)
                    prepare(packageName,output,args.getOrNull(4)?.takeUnless { it=="-" },log)
                    fiveGhz(log,verifyRoute=false)
                    firewall(log)
                }
                HotspotProbeAction.RESUME_LOCAL_ONLY -> {
                    firewall(log)
                    localOnly(log,refresh=true)
                }
                HotspotProbeAction.START -> start(packageName, log)
                HotspotProbeAction.FIVE_GHZ -> fiveGhz(log)
                HotspotProbeAction.FIREWALL -> firewall(log)
                HotspotProbeAction.STATUS -> status(log)
                HotspotProbeAction.STOP -> stop(log)
                HotspotProbeAction.STOP_OWNED -> stopOwned(output,token,log)
                HotspotProbeAction.RELEASE -> removeFirewall(log)
            }
            log("localOnly=timing phase=helper_total elapsedMs=${android.os.SystemClock.elapsedRealtime()-helperEntered}")
            write(output, lines, "OK")
        } catch (failure: Throwable) {
            if(deferred && !prewarmCompleted) log("localOnly=prewarm_failed_without_execution")
            val interrupted=runCatching {
                val preparing=args.getOrNull(0) in listOf("PREPARE","PREPARE_LOCAL_ONLY")
                val saved=org.json.JSONObject(File(checkNotNull(output).parentFile,"pioneer-wireless-hotspot.json").readText())
                val manager=wifi()
                preparing && saved.getString("token")==args.getOrNull(3) &&
                    saved.getString("boot")==File("/proc/sys/kernel/random/boot_id").readText().trim() &&
                    saved.optBoolean("created") && saved.optBoolean("creationStarted") &&
                    !saved.optBoolean("creationRejected") && apState(manager)==11 &&
                    (manager.javaClass.getMethod("getWifiEnabledState").invoke(manager) as Int) in 2..3
            }.getOrDefault(false)
            log("localOnly=failure cause=${failure.javaClass.simpleName}:${failure.message} at=${failure.stackTrace.take(4).joinToString(" <- ")}")
            if(interrupted)log("ERROR AP_START_INTERRUPTED: owned hotspot lost while station Wi-Fi resumed; original=${failure.javaClass.simpleName}:${failure.message}")
            else log("ERROR ${failure.javaClass.simpleName}: ${failure.message}")
            if (output != null) runCatching { write(output, lines, "ERROR") }
        } finally {
            operationChannel?.close()
        }
    }

    private inline fun timed(phase: String, log: (String) -> Unit, action: () -> Unit) {
        val start=android.os.SystemClock.elapsedRealtime()
        var complete = false
        try { action(); complete = true } finally {
            val elapsed = android.os.SystemClock.elapsedRealtime()-start
            log("localOnly=timing phase=$phase elapsedMs=$elapsed")
            metric(phase,elapsed,if(complete) "complete" else "failed",log)
        }
    }

    private inline fun metric(phase: String, millis: Long, result: String, log: (String) -> Unit) {
        log("DIPLAY_METRIC phase=$phase durationMs=${millis.coerceAtLeast(0)} result=$result")
    }

    private fun awaitExit(worker: java.lang.Process, timeoutMs: Long): Boolean {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs
        do {
            if(runCatching { worker.exitValue();true }.getOrDefault(false)) return true
            Thread.sleep(10)
        } while(android.os.SystemClock.elapsedRealtime()<deadline)
        return false
    }

    private fun prepare(packageName: String, output: File, gatePath: String?, log: (String) -> Unit) {
        check(apState(wifi())==11) { "Vehicle AP state changed before preparation" }
        val readySocket=Supplicant.open(log) ?: error("Wi-Fi control service is not ready")
        try { check(readySocket.command("PING")=="PONG") { "Wi-Fi control service did not confirm readiness" } }
        finally { readySocket.close() }
        log("localOnly=wifi_ready supplicant=responsive")
        if(gatePath==null) { verifyCreation(output,leaseToken,requestStart=true);start(packageName,log);return }
        val binary=File(gatePath)
        val owner=Os.stat(output.parentFile!!.path).st_uid
        val info=Os.lstat(binary.path)
        check(info.st_uid==owner && android.system.OsConstants.S_ISREG(info.st_mode) && info.st_nlink==1L)
        val result=File(output.path+".gate")
        val cancel=File(output.path+".gate.cancel")
        val worker=ProcessBuilder(binary.path,result.path,cancel.path,owner.toString())
            .redirectErrorStream(true).start()
        try {
            val end=android.os.SystemClock.elapsedRealtime()+2500
            while(!result.exists() || result.readText().startsWith("state=ARMED").not()) {
                checkCancelled()
                if(runCatching { worker.exitValue();true }.getOrDefault(false)) break
                if(android.os.SystemClock.elapsedRealtime()>=end) break
                Thread.sleep(10)
            }
            if(result.isFile && result.readText().startsWith("state=ARMED")) {
                verifyCreation(output,leaseToken,requestStart=true)
                start(packageName,log)
            } else {
                cancel.writeText("cancel")
                check(awaitExit(worker,3000)) { "AP gate detach not confirmed" }
                check(!result.isFile || !result.readText().contains("DETACH_UNCONFIRMED")) { "AP gate detach not confirmed" }
                verifyCreation(output,leaseToken,requestStart=true)
                start(packageName,log)
            }
        } finally {
            cancel.writeText("cancel")
            if(!awaitExit(worker,3000)) {
                worker.destroy()
                check(awaitExit(worker,3000)) { "AP gate did not stop" }
            }
            val state=if(result.isFile) result.readText() else "no result"
            val receipt=File(output.parentFile,"pioneer-ap-first-select-last.txt")
            receipt.writeText(state)
            Os.chmod(receipt.path,384)
            Os.chown(receipt.path,owner,owner)
            log("localOnly=firstSelectGate "+state.replace("\n"," "))
            check(!state.contains("DETACH_UNCONFIRMED")) { "AP gate detach not confirmed" }
            result.delete();cancel.delete()
        }
    }

    private fun verifyCreation(output: File, token: String, requestStart: Boolean) {
        checkCancelled()
        val journal=File(output.parentFile,"pioneer-wireless-hotspot.json")
        val saved=org.json.JSONObject(journal.readText())
        check(saved.getString("token")==token && saved.getBoolean("created") &&
            saved.getString("boot")==File("/proc/sys/kernel/random/boot_id").readText().trim()) {
            "Hotspot creation lease is no longer current"
        }
        if(apState(wifi())!=11) {
            saved.put("creationRejected",true)
            saveCreationState(journal,saved)
            error("Vehicle hotspot changed before creation; ownership not acquired")
        }
        if(requestStart) {
            saved.put("creationStarted",true)
            saveCreationState(journal,saved)
        }
    }

    private fun saveCreationState(journal: File, state: org.json.JSONObject) {
        val temp=File(journal.path+".tmp")
        FileOutputStream(temp).use { it.write(state.toString().toByteArray());it.fd.sync() }
        Os.chmod(temp.path,384);Os.chown(temp.path,Os.stat(journal.parentFile!!.path).st_uid,-1)
        check(temp.renameTo(journal)) { "Cannot persist hotspot creation state" }
    }

    private fun processStart(pid: Int): String = File("/proc/$pid/stat").readText()
        .substringAfterLast(") ").split(' ')[19]

    private fun start(packageName: String, log: (String) -> Unit) {
        val wifi = wifi()
        log("apState before=${apState(wifi)}")
        log("apConfig ssid=${apSsid(wifi)}")
        val connectivity = connectivity()
        val method = connectivity.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: connectivity.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: error("IConnectivityManager.startTethering missing")
        log("call ${method.name} params=${method.parameterTypes.size} package=$packageName")
        if (method.parameterTypes.size == 4) {
            method.invoke(connectivity, 0, ResultReceiver(null), false, packageName)
        } else {
            method.invoke(connectivity, 0, ResultReceiver(null), false)
        }
        awaitApState(wifi, 13, 60_000, log)

    }

    private fun stopOwned(output: File, token: String, log: (String) -> Unit) {
        // Read ownership and AP state only AFTER acquiring the cross-process operation lock.
        // A timed-out PREPARE may have enabled the AP after the app last observed state 11.
        val saved=org.json.JSONObject(File(output.parentFile,"pioneer-wireless-hotspot.json").readText())
        check(saved.getString("token")==token && saved.getBoolean("created") &&
            saved.getString("boot")==File("/proc/sys/kernel/random/boot_id").readText().trim()) { "AP lease identity changed" }
        if(!HotspotPrewarmAuthorization.mayStopOwnedAp(
                if(saved.has("creationStarted")) saved.getBoolean("creationStarted") else null,
                saved.optBoolean("creationRejected"))) {
            removeFirewall(log)
            log("localOnly=AP_NOT_ACQUIRED foreign_ap_untouched=true")
            return
        }
        val wifi=wifi()
        val deadline=android.os.SystemClock.elapsedRealtime()+15000
        var state=apState(wifi)
        while(state==10 || state==12) {
            check(android.os.SystemClock.elapsedRealtime()<deadline) { "Owned AP is still changing state" }
            Thread.sleep(100);state=apState(wifi)
        }
        check(state==11 || state==13) { "Owned AP state unconfirmed" }
        if(state==13) {
            val iface=java.net.NetworkInterface.getByName("bcm1")
            val mac=iface?.hardwareAddress?.joinToString(":") { "%02X".format(it.toInt() and 255) }
            check(apSsid(wifi)==saved.getString("ssid") && iface!=null &&
                (saved.optInt("index")==0 || iface.index==saved.getInt("index")) &&
                (saved.optString("mac").isEmpty() || mac==saved.getString("mac"))) { "Owned AP identity changed; not stopping it" }
            stop(log)
        } else removeFirewall(log)
        check(apState(wifi)==11) { "Owned AP stop unconfirmed" }
    }

    private fun stop(log: (String) -> Unit) {
        val connectivity = connectivity()
        val method = connectivity.javaClass.methods.firstOrNull {
            it.name == "stopTethering" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Integer.TYPE
        }
        if (method != null) {
            log("call stopTethering type=0")
            runCatching { method.invoke(connectivity, 0) }
                .onFailure { log("stopTethering failed: ${it.message}") }
        } else {
            log("stopTethering(int) missing; fallback setWifiApEnabled(false)")
        }
        val wifi = wifi()
        if (apState(wifi) != 11) {
            runCatching {
                Class.forName("android.net.wifi.IWifiManager")
                    .getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(wifi, null, false)
                log("fallback setWifiApEnabled(false) invoked")
            }.onFailure { log("fallback setWifiApEnabled failed: ${it.message}") }
        }
        awaitApState(wifi, 11, 8_000, log)
        log("apState after=${apState(wifi)}")
        removeFirewall(log)
    }

    private fun fiveGhz(log: (String) -> Unit, verifyRoute: Boolean = true) {
        checkCancelled()
        val iface = requireApInterface(log)
        check(apState(wifi()) == 13) { "厂商热点未启用" }
        var supplicant = Supplicant.open(log) ?: error("supplicant socket unavailable")
        try {
            var before = supplicant.command("IFNAME=$iface STATUS")
            val initialDeadline=android.os.SystemClock.elapsedRealtime()+30_000
            while(!before.contains("mode=AP") || !before.contains("wpa_state=COMPLETED")) {
                checkCancelled()
                check(android.os.SystemClock.elapsedRealtime()<initialDeadline) { "Vehicle AP supplicant did not become ready" }
                Thread.sleep(100)
                before=supplicant.command("IFNAME=$iface STATUS")
            }
            log("AP before=$before")
            check(before.lineSequence().any { it == "key_mgmt=WPA2-PSK" || it == "key_mgmt=WPA-PSK" }) { "Vehicle AP is not PSK secured" }
            if (isFiveGhz(before)) { if(verifyRoute) ensureApRoute(iface,log); log("AP already reports 5GHz; kernel frequency requires independent audit"); return }
            checkCancelled()
            check(supplicant.command("IFNAME=$iface SET_NETWORK 0 frequency 5") == "OK")
            // SELECT on an already running AP does not restart the radio. A disable/enable cycle
            // is required. This firmware may take longer than a control-socket timeout to stop AP.
            try { log("AP disable=${supplicant.command("IFNAME=$iface DISABLE_NETWORK 0")}") }
            catch (failure: java.io.IOException) { log("AP disable reply timeout; reopen socket and verify state (${failure.javaClass.simpleName})") }
            supplicant.close()
            supplicant = Supplicant.open(log) ?: error("cannot reopen supplicant socket")
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var idle = false
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                checkCancelled()
                val state = supplicant.command("IFNAME=$iface STATUS")
                if (state.lineSequence().any { it == "wpa_state=DISCONNECTED" || it == "wpa_state=INACTIVE" }) { idle = true; break }
                Thread.sleep(500)
            }
            check(idle) { "AP未停止；不继续修改频率" }
            checkCancelled()
            check(supplicant.command("IFNAME=$iface SET_NETWORK 0 frequency 5") == "OK")
            check(supplicant.command("IFNAME=$iface ENABLE_NETWORK 0") == "OK")
            check(supplicant.command("IFNAME=$iface SELECT_NETWORK 0") == "OK")
            var current = supplicant.command("IFNAME=$iface STATUS")
            val readyDeadline=android.os.SystemClock.elapsedRealtime()+30_000
            while(!current.contains("mode=AP") || !current.contains("wpa_state=COMPLETED") || !isFiveGhz(current)) {
                checkCancelled()
                check(android.os.SystemClock.elapsedRealtime()<readyDeadline) { "Vehicle 5GHz AP did not become ready" }
                Thread.sleep(100)
                current=supplicant.command("IFNAME=$iface STATUS")
            }
            log("AP after=$current")
            check(current.contains("mode=AP") && current.contains("wpa_state=COMPLETED") && isFiveGhz(current)) {
                "AP未报告5GHz；以实际状态为准"
            }
            if(verifyRoute) ensureApRoute(iface,log)
            log("5GHz AP supplicant verified; exact hardware channel requires independent nl80211 audit")
        } finally { supplicant.close() }
    }

    private fun ensureApRoute(iface: String, log: (String) -> Unit) {
        check(iface == "bcm1") { "unsupported AP interface" }
        val address = Regex("inet ([0-9.]+)/([0-9]+)").find(sh("ip -4 addr show dev $iface"))
            ?: error("AP缺少IPv4地址")
        val bytes = java.net.InetAddress.getByName(address.groupValues[1]).address
        val bits = address.groupValues[2].toInt()
        check(bytes.size == 4 && bits in 8..30) { "unsupported AP subnet" }
        val host = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        val network = host and ((0xffffffffL shl (32-bits)) and 0xffffffffL)
        val prefix = (3 downTo 0).joinToString(".") { ((network ushr (it*8)) and 255).toString() } + "/$bits"
        val expected = "$prefix dev $iface"
        if (!sh("ip -4 route show table local_network").contains(expected)) {
            log("恢复频段切换时丢失的AP本地路由 prefix=$prefix iface=$iface")
            log(sh("/system/bin/ndc network route add local $iface $prefix"))
            check(sh("ip -4 route show table local_network").contains(expected)) { "AP本地路由恢复失败" }
        }
        log("AP本地网络路由已核实 $expected")
    }

    private fun firewall(log: (String) -> Unit) {
        checkCancelled()
        val iface = requireApInterface(log)
        log("AP interface=$iface")
        ensureApRoute(iface,log)
        // OEM startup may reset its chains: recheck before publishing network readiness.
        allowLocalTraffic(iface,log)
    }

    private fun prepareLocalTraffic(log: (String) -> Unit) {
        checkCancelled()
        try {
            allowLocalTraffic("bcm1",log)
        } catch(failure: Exception) {
            // A cold system can create the vendor chains only when tethering starts.
            // Keep the original post-start install/verification as the mandatory barrier.
            // Any partial early insertion still belongs to this journaled lease.
            checkCancelled()
            log("AP early CARNET preparation deferred; mandatory post-start verification retained: ${failure.javaClass.simpleName}")
        }
    }

    private fun allowLocalTraffic(iface: String, log: (String) -> Unit) {
        checkCancelled()
        check(iface == "bcm1") { "unsupported AP interface" }
        // Interface-name matches do not need an existing interface or IP address. Install
        // before SoftAP starts so saved-network clients can immediately exchange DHCP.
        val checks=listOf("/system/bin/iptables", "/system/bin/ip6tables").flatMap { binary ->
            listOf("CARNET_IN", "CARNET_OUT").map { chain ->
                val direction=if(chain=="CARNET_IN") "-i" else "-o"
                "$binary -w -t filter -C $chain $direction $iface " +
                    "-m comment --comment $ruleTag-$chain -j ACCEPT"
            }
        }
        // Batch process launches; verify every rule even when an insertion appeared successful.
        localBatch(checks.map { check -> "($check || ${check.replace(" -C "," -I ")})" }+checks)
        log("AP CARNET firewall verified families=IPv4,IPv6 interface=$iface")
    }

    private fun localRuleSnapshot(): Map<Pair<String,String>,String> {
        val keys=PioneerLocalOnlyPolicy.rules().map { it.binary to it.chain }.distinct()
        val command=keys.mapIndexed { index,(binary,chain) ->
            "printf '\\n__DIPLAY_AP_CHAIN_${index}__\\n'; $binary -w -t filter -S $chain || exit 1"
        }.joinToString("\n")
        return PioneerLocalOnlyPolicy.splitChainListings(sh(command),keys)
    }

    private fun tagged(rule: PioneerLocalOnlyPolicy.Rule, snapshot: Map<Pair<String,String>,String>) =
        PioneerLocalOnlyPolicy.taggedRulePresent(snapshot.getValue(rule.binary to rule.chain),rule,ruleTag)

    private fun localBatch(commands: List<String>) {
        if(commands.isEmpty()) return
        // Fixed, token-validated commands. One shell launch avoids a JVM thread/process
        // round trip for every rule; && stops at the first failed kernel operation.
        check(sh(commands.joinToString(" && ")).endsWith("[exit=0]")) { "AP local-only rule batch failed; recovery record retained" }
    }

    private fun localOnly(log: (String) -> Unit, refresh: Boolean) {
        checkCancelled()
        val before=localRuleSnapshot()
        val reuse=refresh && PioneerLocalOnlyPolicy.initialRulesAtFront(before,ruleTag)
        val rules=PioneerLocalOnlyPolicy.rules().map { if(refresh && !reuse) it.copy(id=it.id+"-ready") else it }
        if(reuse) log("localOnly=initial_rules_reused precedence=verified")
        val add=rules.filter { !tagged(it,before) }.map { it.command("-I",ruleTag) }
        localBatch(add+rules.map { it.command("-C",ruleTag) })
        checkCancelled()
        // Both sets enforce the same restrictions. Keep the initial set until STOP_OWNED /
        // RELEASE, which already remove both tags. No extra root process, stale cleanup
        // worker or rule-deletion round trips on the connection's critical path.
        if(refresh) log("localOnly=initial_rules_retained cleanup=lease_release")
        log("localOnly=ENFORCED scope=bcm1 forwarding=REJECT dnsProxy=REJECT ipv6RA=DROP dhcp=OEM phoneCellular=UNVERIFIED")
    }

    private fun removeLocalRules(rules: List<PioneerLocalOnlyPolicy.Rule>, log: (String) -> Unit) {
        val before=localRuleSnapshot()
        val present=rules.asReversed().filter { tagged(it,before) }
        if(present.isEmpty()) return
        localBatch(present.map { it.command("-C",ruleTag) }+present.map { it.command("-D",ruleTag) })
        val after=localRuleSnapshot()
        check(present.none { tagged(it,after) }) { "AP local-only cleanup unconfirmed" }
        log("localOnly=REMOVED rules=${present.size}")
    }

    private fun removeLocalOnly(log: (String) -> Unit) {
        val rules=PioneerLocalOnlyPolicy.rules()
        removeLocalRules(rules+rules.map { it.copy(id=it.id+"-ready") },log)
    }

    private fun removeFirewall(log: (String) -> Unit) {
        removeLocalOnly(log)
        // Match this lease even after the AP interface has disappeared.
        val iface = "bcm1"
        for (binary in listOf("/system/bin/iptables", "/system/bin/ip6tables")) {
            for (chain in listOf("CARNET_IN", "CARNET_OUT")) {
                val direction = if (chain == "CARNET_IN") "-i" else "-o"
                val comment = "$ruleTag-$chain"
                val rule = "$binary -w -t filter -D $chain $direction $iface " +
                    "-m comment --comment $comment -j ACCEPT"
                var confirmed=false
                repeat(3) { attempt ->
                    if(!confirmed) {
                        val snapshot=sh("$binary -w -t filter -S")
                        check(snapshot.endsWith("[exit=0]")) { "Vehicle AP firewall cleanup inspection failed: $snapshot" }
                        if(!snapshot.contains(comment))confirmed=true else {
                            val removed=sh(rule)
                            if(!removed.endsWith("[exit=0]"))
                                log("localOnly=firewall_cleanup_retry chain=$chain attempt=$attempt detail=${removed.replace("\n"," ")}")
                            val after=sh("$binary -w -t filter -S")
                            confirmed=after.endsWith("[exit=0]") && !after.contains(comment)
                            if(!confirmed)Thread.sleep(100)
                        }
                    }
                }
                check(confirmed) { "Vehicle AP firewall cleanup failed; tagged rule still present: $comment" }
            }
        }
    }

    private fun status(log: (String) -> Unit) {
        val wifi=wifi()
        log("apState=${apState(wifi)}")
        logShell(log,"AP address","ip -4 addr show bcm1")
        logShell(log,"AP local routes","ip -4 route show table local_network")
        for(binary in listOf("/system/bin/iptables","/system/bin/ip6tables")) {
            for(chain in listOf("INPUT","OUTPUT","FORWARD")) {
                log("localOnly=counters binary=$binary chain=$chain")
                sh("$binary -w -n -v -x -L $chain").lineSequence().filter { it.contains("$ruleTag-local-") }.forEach { log("localOnly=counter $it") }
            }
        }
    }

    private fun awaitApState(wifi: Any, target: Int, timeoutMillis: Long, log: (String) -> Unit) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        var last = -1
        var enablingSeen=false
        var interruptedSince=0L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if(target==13) checkCancelled()
            val state = runCatching { apState(wifi) }.getOrDefault(-1)
            if (state != last) {
                log("apState=$state")
                last = state
            }
            if (state == target) return
            if(target==13) {
                if(state==12)enablingSeen=true
                val stationState=runCatching {
                    wifi.javaClass.getMethod("getWifiEnabledState").invoke(wifi) as Int
                }.getOrDefault(-1)
                // Only fail early after observing our AP startup, followed by AP-off
                // and normal Wi-Fi returning. Slow AP startup keeps its full timeout.
                if(enablingSeen && state==11 && stationState in 2..3) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(interruptedSince==0L)interruptedSince=now
                    if(now-interruptedSince>=1_000)
                        error("AP_START_INTERRUPTED: hotspot disabled while station Wi-Fi resumed")
                } else interruptedSince=0L
            }
            Thread.sleep(200)
        }
        error("Vehicle AP state did not reach $target; last=$last")
    }

    private fun wifi(): Any = Class.forName("android.net.wifi.IWifiManager\$Stub")
        .getMethod("asInterface", IBinder::class.java)
        .invoke(null, service("wifi") ?: error("wifi service missing"))
        ?: error("wifi interface null")

    private fun connectivity(): Any = Class.forName("android.net.IConnectivityManager\$Stub")
        .getMethod("asInterface", IBinder::class.java)
        .invoke(null, service("connectivity") ?: error("connectivity service missing"))
        ?: error("connectivity interface null")

    private fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java)
        .invoke(null, name) as? IBinder

    private fun apState(wifi: Any): Int = Class.forName("android.net.wifi.IWifiManager")
        .getMethod("getWifiApEnabledState")
        .invoke(wifi) as Int

    private fun apSsid(wifi: Any): String = runCatching {
        val config = Class.forName("android.net.wifi.IWifiManager")
            .getMethod("getWifiApConfiguration")
            .invoke(wifi) ?: return "null"
        config.javaClass.getField("SSID").get(config) as? String ?: "?"
    }.getOrDefault("error")

    private fun requireApInterface(log: (String) -> Unit): String {
        // This entry point is firmware-gated and must never fall back to a station interface.
        check(File("/sys/class/net/bcm1").isDirectory) { "bcm1 AP interface not found; start the vendor hotspot first" }
        log("AP interface present=bcm1")
        return "bcm1"
    }

    private fun isFiveGhz(text: String): Boolean {
        if (text.contains("5GHz") || text.contains("5 GHz")) return true
        val freq = Regex("freq=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (freq != null) return freq >= 5_000
        val channel = Regex("channel\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return channel != null && channel > 14
    }

    private fun logShell(log: (String) -> Unit, label: String, command: String) {
        log("### $label")
        log("$ $command")
        log(sh(command))
    }

    private fun sh(command: String, timeoutSeconds: Long = 12): String {
        // The supervisor kills and reaps the entire process group on timeout/cancellation.
        // Keep the operation lock until it exits, including when a killed kernel task takes
        // time to unwind. The app's outer request deadline remains bounded and keeps its journal.
        val process = ProcessBuilder(commandRunner.path, (timeoutSeconds * 1000).toString(),
            cancellation?.path ?: "-", "/system/bin/sh", command, commandGuard.path, Process.myPid().toString())
            .redirectErrorStream(true).start()
        val text = StringBuilder()
        var truncated = false
        process.inputStream.bufferedReader().use { reader ->
            val chunk = CharArray(2048)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                val keep = minOf(count, MAX_OUTPUT - text.length)
                if (keep > 0) text.append(chunk, 0, keep)
                if (keep < count) truncated = true
            }
        }
        val exit = process.waitFor()
        return text.toString() + (if (truncated) "\n[OUTPUT_TRUNCATED]" else "") + "\n[exit=$exit]"
    }

    private fun write(output: File, lines: List<String>, result: String) {
        output.parentFile?.mkdirs()
        val text = lines.joinToString("\n") + "\nRESULT: $result\n"
        val temp = File(output.path + ".tmp")
        FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        runCatching { Os.chmod(temp.path, 0x180) } // 0600
        if (!temp.renameTo(output)) {
            FileOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        runCatching { Os.chmod(output.path, 0x180); Os.chown(output.path, output.parentFile!!.let { Os.stat(it.path).st_uid }, -1) }
    }

    private class Supplicant private constructor(private val socket: LocalSocket, private val log: (String) -> Unit) {
        private val input = socket.inputStream
        private val output = socket.outputStream

        fun command(command: String): String {
            checkCancelled()
            output.write(command.toByteArray(Charsets.UTF_8))
            output.flush()
            val buffer = ByteArray(8192)
            val count = input.read(buffer)
            check(count > 0) { "supplicant returned no response" }
            return String(buffer, 0, count, Charsets.UTF_8).trim()
        }

        fun close() = runCatching { socket.close() }

        companion object {
            fun open(log: (String) -> Unit): Supplicant? {
                val candidates = listOf(
                    Triple("/data/misc/wifi/sockets/wpa_wlan0_cmd", LocalSocketAddress.Namespace.FILESYSTEM, "fs:wpa_wlan0_cmd"),
                    Triple("/data/misc/wifi/sockets/wpa_wlan0", LocalSocketAddress.Namespace.FILESYSTEM, "fs:wpa_wlan0"),
                    Triple("wpa_wlan0_cmd", LocalSocketAddress.Namespace.ABSTRACT, "abstract:wpa_wlan0_cmd"),
                    Triple("wpa_wlan0", LocalSocketAddress.Namespace.ABSTRACT, "abstract:wpa_wlan0"),
                )
                for ((name, namespace, label) in candidates) {
                    try {
                        val socket = LocalSocket(LocalSocket.SOCKET_DGRAM)
                        socket.bind(LocalSocketAddress("btprobe-ap-${android.os.Process.myPid()}-${System.nanoTime()}", LocalSocketAddress.Namespace.ABSTRACT))
                        socket.connect(LocalSocketAddress(name, namespace))
                        socket.soTimeout = 10_000
                        log("supplicant socket=$label")
                        return Supplicant(socket, log)
                    } catch (failure: Throwable) {
                        log("supplicant socket $label failed ${failure.javaClass.simpleName}: ${failure.message}")
                    }
                }
                return null
            }
        }
    }
}
