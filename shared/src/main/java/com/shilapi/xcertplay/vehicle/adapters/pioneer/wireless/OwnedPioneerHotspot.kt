package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
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
    private val token=UUID.randomUUID().toString().replace("-","")
    private val journal=File(context.filesDir,"pioneer-wireless-hotspot.json")
    private val lockFile=File(context.filesDir,"pioneer-wireless-hotspot.lock")
    private var lockStream: FileOutputStream?=null
    private var leaseLock: FileLock?=null
    private var startupDeadline=Long.MAX_VALUE
    private val commands=RootCommand(context,token,{ closed || isCancelled() || SystemClock.elapsedRealtime()>=startupDeadline },{ log(it) })
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
            recoverPrevious()
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
                .put("creationStarted",false).put("ssid",config.SSID).put("wifiWasEnabled",wifi.isWifiEnabled).put("index",0).put("mac","")
            save()
            if(initial==11) {
                commands.run(if(localOnly) HotspotProbeAction.PREPARE_LOCAL_ONLY else HotspotProbeAction.PREPARE)
                state!!.put("creationStarted",true)
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
        val process=ProcessBuilder(radioBinary.path).redirectErrorStream(true).start()
        val text=BoundedProcessOutput.read(process,8_000,{ closed || isCancelled() || SystemClock.elapsedRealtime()>=startupDeadline })
        fun number(key: String)=Regex("(?m)^$key=(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            ?: error("Vehicle radio audit did not confirm $key")
        return Radio(number("bcm1_ifindex"),number("iftype"),number("frequency_mhz"))
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
                if(result.contains("localOnly=AP_NOT_ACQUIRED")) return
            } else runner.run(HotspotProbeAction.RELEASE,true)
            // STOP_OWNED confirms AP-off and removes all lease rules under the root lock.
            if(saved.getBoolean("created") && saved.optBoolean("wifiWasEnabled") && !wifi.isWifiEnabled) {
                @Suppress("DEPRECATION")
                check(wifi.setWifiEnabled(true)) { "Vehicle Wi-Fi restore rejected" }
            }
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
