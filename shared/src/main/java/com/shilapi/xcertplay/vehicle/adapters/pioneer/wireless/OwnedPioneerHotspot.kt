package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.SupplicantState
import android.os.SystemClock
import android.system.Os
import com.shilapi.xcertplay.network.WirelessHotspotManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.channels.FileLock
import java.util.Collections
import java.util.UUID
import org.json.JSONObject

/** A vehicle AP lease; it never stops an AP borrowed from another owner. */
internal class OwnedPioneerHotspot(private val context: Context, @Volatile private var isCancelled: () -> Boolean,
    private var log: (String) -> Unit) : WirelessHotspotManager {
    @Volatile private var onTiming: (String, Long, String) -> Unit = { _, _, _ -> }
    fun bindTiming(observer: (String, Long, String) -> Unit) { onTiming = observer }
    private var onPreparation: (String) -> Unit = {}
    fun bindPreparation(observer: (String) -> Unit) { onPreparation=observer }
    private val token=UUID.randomUUID().toString().replace("-","")
    private val journal=File(context.filesDir,"pioneer-wireless-hotspot.json")
    private val lockFile=File(context.filesDir,"pioneer-wireless-hotspot.lock")
    private var lockStream: FileOutputStream?=null
    private var leaseLock: FileLock?=null
    private var startupDeadline=Long.MAX_VALUE
    private val commands=RootCommand(context,token,{ closed || isCancelled() || SystemClock.elapsedRealtime()>=startupDeadline },{ log(it) },{ id, millis, result -> onTiming(id, millis, result) })
    private val operation=Any()
    @Volatile private var closed=false
    private var state: JSONObject?=null
    private var info: WirelessHotspotInfo?=null
    private var configuration: WifiConfiguration?=null
    private val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    fun bindLog(logger: (String) -> Unit) { log=logger }
    fun retainable() = !closed && info!=null && state?.optBoolean("created")==true
    fun resume(timeoutMillis: Long): WirelessHotspotInfo = synchronized(operation) {
        startupDeadline=SystemClock.elapsedRealtime()+timeoutMillis.coerceIn(1,180000)
        try {
            validateReady()
            commands.run(if(state?.optBoolean("localOnly")==true) HotspotProbeAction.RESUME_LOCAL_ONLY else HotspotProbeAction.FIREWALL)
            validateReady()
            checkNotNull(info)
        } finally { startupDeadline=Long.MAX_VALUE }
    }
    fun bindCancellation(cancelled: () -> Boolean) { isCancelled=cancelled }
    fun prewarm() {
        if(!closed && !isCancelled()) commands.prewarm(if(localOnlyRequested())
            HotspotProbeAction.PREPARE_LOCAL_ONLY else HotspotProbeAction.PREPARE)
    }
    private fun localOnlyRequested()=PioneerLocalOnlyPolicy.enabled(true,
        File(context.filesDir,PioneerLocalOnlyPolicy.DISABLE_FILE).exists())
    private fun apState()=wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int
    private fun apConfiguration()=wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        ?: error("Vehicle hotspot configuration unavailable")
    private fun boot()=File("/proc/sys/kernel/random/boot_id").readText().trim()
    private fun checkCurrent() { check(!closed && !isCancelled() && SystemClock.elapsedRealtime()<startupDeadline) { "Vehicle hotspot cancelled" } }
    override fun start(timeoutMillis: Long): WirelessHotspotInfo = synchronized(operation) {
        check(PioneerVehicleWireless.supported()) { "Vehicle hotspot unsupported on this firmware/ABI" }
        startupDeadline=SystemClock.elapsedRealtime()+timeoutMillis.coerceIn(1,180000)
        checkCurrent()
        lockStream=FileOutputStream(lockFile,true)
        try {
            while(leaseLock==null) {
                checkCurrent()
                leaseLock=try { lockStream!!.channel.tryLock() }
                    catch(_: java.nio.channels.OverlappingFileLockException) { null }
                if(leaseLock==null) Thread.sleep(100)
            }
        } catch(failure: Throwable) { releaseLock();throw failure }

        try {
            val recoveryStart = SystemClock.elapsedRealtime()
            var recoveryComplete = false
            try { recoverPrevious(); recoveryComplete = true } finally {
                runCatching { onTiming("hotspot_previous_cleanup", SystemClock.elapsedRealtime()-recoveryStart,
                    if (recoveryComplete) "complete" else "failed") }
            }
            checkCurrent()
            val initial=apState()
            check(initial==11 || initial==13) { "Vehicle hotspot is changing state; retry after it settles" }
            val config=PioneerHotspotConfiguration.ensure(wifi)
            // SoftApManager on this verified firmware ignores allowedKeyManagement and always
            // configures WPA-PSK + proto WPA2 + CCMP. Verify the running supplicant instead.
            log("Vehicle AP saved keyManagement=${config.allowedKeyManagement}; validating live AP security")
            configuration=config
            // An existing 2.4GHz AP can have other clients. Do not restart someone else's network.
            if(initial==13) check(radio().frequency in 5000..5924) { "Existing vehicle AP is not 5GHz; stop it before using this backend" }
            val localOnly=PioneerLocalOnlyPolicy.enabled(initial==11,
                File(context.filesDir,PioneerLocalOnlyPolicy.DISABLE_FILE).exists())
            log("Vehicle AP localOnly=$localOnly owned=${initial==11} phoneCellular=UNVERIFIED")
            state=JSONObject().put("localOnly",localOnly).put("token",token).put("boot",boot()).put("created",initial==11)
                .put("creationStarted",false).put("ssid",config.SSID)
                .put("wifiWasEnabled",wifi.wifiState in listOf(WifiManager.WIFI_STATE_ENABLED,WifiManager.WIFI_STATE_ENABLING)).put("index",0).put("mac","")
            save()
            if(initial==11) {
                var recoveries=0
                while(true) {
                    awaitWifiReady(if(recoveries==0)1_500 else 3_000)
                    onPreparation("")
                    try {
                        commands.run(if(localOnly) HotspotProbeAction.PREPARE_LOCAL_ONLY else HotspotProbeAction.PREPARE)
                        state!!.put("creationStarted",true)
                        break
                    } catch(failure: Throwable) {
                        if(failure.message?.contains("AP_START_INTERRUPTED")!=true || recoveries>=2)throw failure
                        checkCurrent()
                        recoveries++
                        log("Vehicle AP startup interrupted; recovering within network node recovery=$recoveries")
                        onPreparation("vehicle_wifi_wait")
                        // Helper has exited and detached the frequency gate. Release only
                        // this lease's rules/AP before reusing the same Bluetooth session.
                        commands.run(HotspotProbeAction.STOP_OWNED)
                        checkCurrent()
                        state!!.put("creationStarted",false).remove("creationRejected")
                        save()
                    }
                }
            }
            commands.cancelPrewarm() // A borrowed AP never authorizes the prepared request.
            checkCurrent()
            val radio=radio()
            check(radio.frequency in 5000..5924 && radio.type==3) { "Vehicle 5GHz AP radio is not ready" }
            val iface=checkNotNull(NetworkInterface.getByName("bcm1"))
            val mac=checkNotNull(iface.hardwareAddress).joinToString(":") { "%02X".format(it.toInt() and 255) }
            state!!.put("index",iface.index).put("mac",mac);save()
            if(initial==13) commands.run(HotspotProbeAction.FIREWALL)
            checkCurrent()
            val current=apConfiguration()
            check(current.SSID==config.SSID && current.preSharedKey==config.preSharedKey) { "Vehicle hotspot configuration changed" }
            val address=Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().singleOrNull {
                !it.isLoopbackAddress && !it.isLinkLocalAddress
            } ?: error("Vehicle AP IPv4 address unavailable")
            WirelessHotspotInfo(config.SSID,config.preSharedKey,Iap2WirelessSecurity.WPA_WPA2,
                (radio.frequency-5000)/5,radio.frequency,mac,"bcm1",address,"5 GHz",
                WirelessHotspotBackend.PIONEER_VEHICLE, listOf(address),iface.hardwareAddress).also {
                startupDeadline=Long.MAX_VALUE
                info=it;log("Vehicle AP ready frequencyMHz=${radio.frequency} channel=${it.channel} interface=bcm1")
            }
        } catch(failure: Throwable) {
            commands.cancelPrewarm()
            try { cleanup() } catch(cleanup: Throwable) { failure.addSuppressed(cleanup) }
            runCatching { releaseLock() }.onFailure { failure.addSuppressed(it) };throw failure
        }
    }
    private fun awaitWifiReady(quietMillis: Long=1_500) {
        val started=SystemClock.elapsedRealtime()
        val deadline=minOf(startupDeadline,started+45_000)
        var stableSince=0L
        var previous=""
        var enableRequested=false
        var completed=false
        try {
            while(SystemClock.elapsedRealtime()<deadline) {
                checkCurrent()
                val stateNow=wifi.wifiState
                if(stateNow==WifiManager.WIFI_STATE_DISABLED && !enableRequested) {
                    // Persist before changing the switch, including for startup cancellation.
                    state!!.put("wifiReadinessEnabled",true);save()
                    onPreparation("vehicle_wifi_enabling")
                    check(wifi.setWifiEnabled(true)) { "车机 Wi-Fi 开启未受理" }
                    enableRequested=true
                    log("Vehicle Wi-Fi readiness requested enable; originalEnabled=${state!!.getBoolean("wifiWasEnabled")}")
                }
                onPreparation(if(stateNow==WifiManager.WIFI_STATE_ENABLING ||
                    (stateNow==WifiManager.WIFI_STATE_DISABLED && enableRequested)) "vehicle_wifi_enabling" else "vehicle_wifi_wait")
                @Suppress("DEPRECATION")
                val supplicant=wifi.connectionInfo?.supplicantState
                val phase=when(supplicant) {
                    SupplicantState.COMPLETED -> "connected"
                    SupplicantState.DISCONNECTED,SupplicantState.INACTIVE,SupplicantState.SCANNING,
                    SupplicantState.DORMANT -> "idle"
                    else -> "changing"
                }
                val sample="$stateNow/$phase"
                if(sample!=previous) { previous=sample;stableSince=0;log("Vehicle Wi-Fi readiness state=$stateNow phase=$phase supplicant=$supplicant") }
                if(stateNow==WifiManager.WIFI_STATE_ENABLED && phase!="changing") {
                    val now=SystemClock.elapsedRealtime()
                    if(stableSince==0L)stableSince=now
                    if(now-stableSince>=quietMillis) {
                        completed=true
                        log("Vehicle Wi-Fi ready elapsedMs=${now-started} phase=$phase quietMs=${now-stableSince}")
                        return
                    }
                } else stableSince=0
                Thread.sleep(200)
            }
            throw com.shilapi.xcertplay.network.WirelessStartupException(
                com.shilapi.xcertplay.network.WirelessStartupFailure.HOTSPOT_NOT_READY,"Wi-Fi 未就绪，请重试",45_000)
        } finally {
            runCatching { onTiming("hotspot_wifi_readiness",SystemClock.elapsedRealtime()-started,if(completed) "complete" else "failed") }
        }
    }
    private data class Radio(val index: Int,val type: Int,val frequency: Int)
    private val radioBinary: File by lazy {
        val binary=File(context.codeCacheDir,"pioneer-wifi-audit")
        val temp=File(context.codeCacheDir,"wifi-audit-${UUID.randomUUID()}")
        try {
            context.assets.open("pioneer-wifi-audit").use { input -> temp.outputStream().use { input.copyTo(it) } }
            Os.chmod(temp.path,448)
            check(temp.renameTo(binary)) { "Cannot publish vehicle radio audit" }
            binary
        } finally { temp.delete() }
    }
    private fun radio(): Radio {
        // nl80211 omits WIPHY_FREQ while Broadcom is settling its channel. Missing
        // data is not a changed radio: re-query briefly without tearing down AP/SPP.
        // A real frequency/identity mismatch still fails in validateReady below.
        val deadline=minOf(startupDeadline,SystemClock.elapsedRealtime()+3_000)
        var samples=0
        while(true) {
            checkCurrent()
            val remaining=deadline-SystemClock.elapsedRealtime()
            check(remaining>0) { "Vehicle radio audit did not confirm frequency_mhz" }
            val process=ProcessBuilder(radioBinary.path).redirectErrorStream(true).start()
            val text=BoundedProcessOutput.read(process,remaining,{ closed || isCancelled() || SystemClock.elapsedRealtime()>=startupDeadline })
            fun number(key: String)=Regex("(?m)^$key=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val index=checkNotNull(number("bcm1_ifindex")) { "Vehicle radio audit did not confirm bcm1_ifindex" }
            val type=checkNotNull(number("iftype")) { "Vehicle radio audit did not confirm iftype" }
            val frequency=number("frequency_mhz")
            if(frequency!=null) {
                if(samples>0)log("Vehicle AP channel audit settled rechecks=$samples frequencyMHz=$frequency")
                return Radio(index,type,frequency)
            }
            check(index>0 && type==3) { "Vehicle AP radio is not ready" }
            if(samples++==0)log("Vehicle AP channel audit temporarily unavailable; rechecking same AP without reconnecting")
            Thread.sleep(minOf(150,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)))
        }
    }
    override fun validateReady() {
        checkCurrent()
        check(apState()==13) { "Vehicle hotspot is not enabled" }
        val expected=checkNotNull(info) { "Vehicle AP not prepared" }
        val iface=NetworkInterface.getByName("bcm1") ?: error("Vehicle AP interface disappeared")
        check(iface.isUp && iface.index==state!!.getInt("index") &&
            iface.hardwareAddress?.joinToString(":") { "%02X".format(it.toInt() and 255) }==expected.bssid &&
            Collections.list(iface.inetAddresses).contains(expected.hostAddress)) { "Vehicle AP interface identity changed" }
        val current=apConfiguration()
        check(current.SSID==configuration!!.SSID && current.preSharedKey==configuration!!.preSharedKey) { "Vehicle AP credentials changed" }
        val radio=radio()
        check(radio.index==iface.index && radio.type==3 && radio.frequency==expected.frequencyMHz) { "Vehicle AP radio changed" }
    }
    override fun connectionDiagnosticSnapshot()="vehicle_ap=${if(info!=null) "prepared" else "pending"} localOnly=${state?.optBoolean("localOnly")==true} phoneCellular=UNVERIFIED"
    override fun close() {
        closed=true
        commands.cancelPrewarm()
        synchronized(operation) { try { cleanup() } finally { releaseLock() } }
    }
    private fun cleanup() {
        val saved=state ?: return
        try { release(saved,commands) } catch(failure: Throwable) {
            runCatching { File(context.filesDir,"pioneer-wireless-cleanup-error.txt").writeText(
                "${System.currentTimeMillis()} ${failure.javaClass.simpleName}: ${failure.message}") }
            throw failure
        }
        check(journal.delete() || !journal.exists()) { "Vehicle hotspot recovery record retained" }
        state=null;info=null
    }
    private fun release(saved: JSONObject, runner: RootCommand) {
        if(saved.getString("boot")==boot()) {
            if(saved.getBoolean("created")) {
                // Re-read creation proof under the root operation lock. A late PREPARE can
                // still be finishing; app-side state alone cannot decide whether AP is ours.
                val result=runner.run(HotspotProbeAction.STOP_OWNED,true)
                if(result.contains("localOnly=AP_NOT_ACQUIRED")) {
                    if(apState()==11)restoreWifiSwitch(saved)
                    return
                }
            } else runner.run(HotspotProbeAction.RELEASE,true)
            // STOP_OWNED confirms AP-off and removes all lease rules under the root lock.
            if(saved.getBoolean("created"))restoreWifiSwitch(saved)
        }
    }
    private fun restoreWifiSwitch(saved: JSONObject) {
        val original=saved.optBoolean("wifiWasEnabled")
        val currentlyEnabled=wifi.wifiState in listOf(WifiManager.WIFI_STATE_ENABLED,WifiManager.WIFI_STATE_ENABLING)
        if((original || saved.optBoolean("wifiReadinessEnabled")) && currentlyEnabled!=original) {
            @Suppress("DEPRECATION")
            check(wifi.setWifiEnabled(original)) { "Vehicle Wi-Fi switch restore rejected" }
            log("Vehicle Wi-Fi switch restore requested enabled=$original")
        }
    }
    private fun recoverPrevious() {
        if(!journal.isFile)return
        val old=JSONObject(journal.readText())
        check(old.getString("token").matches(Regex("[0-9a-f]{32}")))
        val prefix="pioneer-hotspot-${old.getString("token")}-"
        context.filesDir.listFiles().orEmpty().filter { old.getString("boot")==boot() &&
            it.name.startsWith(prefix) && it.extension=="txt" }.forEach { output ->
            if(!output.readText().contains("\nRESULT:")) {
                File(output.path+".cancel").writeText("cancel")
                val running=File(output.path+".running")
                fun alive()=HotspotRequestIdentity.running(running)
                val end=SystemClock.elapsedRealtime()+15000
                while(alive() && !output.readText().contains("\nRESULT:") && SystemClock.elapsedRealtime()<end) Thread.sleep(100)
                check(!alive() || output.readText().contains("\nRESULT:")) { "Previous AP helper is still recovering" }
            }
        }
        release(old,RootCommand(context,old.getString("token"),{ false },log))
        check(journal.delete()) { "Could not retire previous vehicle hotspot lease" }
    }
    private fun save() {
        val tmp=File(journal.path+".tmp")
        FileOutputStream(tmp).use { it.write(checkNotNull(state).toString().toByteArray());it.fd.sync() }
        Os.chmod(tmp.path,384);check(tmp.renameTo(journal)) { "Cannot persist vehicle hotspot lease" }
    }
    private fun releaseLock() { leaseLock?.release();leaseLock=null;lockStream?.close();lockStream=null }
}
