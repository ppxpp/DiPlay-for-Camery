package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.util.concurrent.atomic.AtomicBoolean

/** Retires the previous audio-only workaround's journal; never enables HFP for new connections. */
internal class PioneerAudioReconnect(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit,
) {
    private val journal=context.getSharedPreferences("pioneer-wireless-audio-reconnect",Context.MODE_PRIVATE)
    private val binder=PioneerBinder(log)
    private var recovered=false

    fun recoverIfNeeded(snapshot: PioneerBluetoothSnapshot): Boolean {
        if(recovered)return true
        val records=journal.all
        if(records.isEmpty()) { recovered=true;return true }
        val devices=snapshot.devices()
        if(devices.isEmpty())return false
        if(records.size!=1 || devices.size!=1)conflict("蓝牙回连配置归属不明确，已保留恢复记录")
        val (address,value)=records.entries.single()
        val device=devices.single()
        if(value !is Int || value!=2 || !PioneerBlueforceNative.sameAddress(device.address,address))
            conflict("蓝牙回连配置或目标已变化，已保留恢复记录")
        // Do not initialize the vendor SDK or write profiles before OEM HFP/A2DP.
        if(snapshot.connectedAddress(address)==null)return false
        val current=binder.output(81,device.handle) ?: return false
        if(current!=value && current!=(value or 1))conflict("蓝牙回连配置被修改，未覆盖原厂设置")
        if(binder.query(44)!=listOf(0,0) || binder.query(55)!=listOf(0,0))return false
        if(current!=value) {
            val ports=PioneerCallbackRepair(context,cancelled,log).audit().ports
            if(ports.any { it.connected || it.lowerStatus!=0 || it.cached!=0 })
                conflict("蓝牙串口正在使用，已保留原配置记录")
            val adapter=PioneerAdapter().apply { initialize() }
            val selected=adapter.paired().singleOrNull()?.takeIf {
                PioneerBlueforceNative.sameAddress(it.address,address)
            } ?: conflict("蓝牙配对列表已变化，未修改配置")
            try {
                if(cancelled.get())throw java.io.InterruptedIOException("Pioneer audio profile recovery cancelled")
                PioneerBluetoothLink.requireConnected(adapter,selected)
                check(adapter.connectProfiles(selected,value)) { "OEM profile restore rejected" }
                val deadline=SystemClock.elapsedRealtime()+35_000
                while(binder.output(81,selected.handle)!=value || adapter.query("isConnecting")!=false) {
                    check(SystemClock.elapsedRealtime()<deadline) { "OEM profile restore timed out" }
                    Thread.sleep(250)
                }
            } catch(error: Exception) {
                throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT,
                    "蓝牙原配置恢复未确认，请稍后重试").apply { initCause(error) }
            }
        }
        check(journal.edit().remove(address).commit()) { "Cannot retire OEM reconnect profile" }
        recovered=true
        log("Pioneer legacy audio reconnect profile restored; HFP workaround retired")
        return true
    }

    private fun conflict(message: String): Nothing =
        throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT,message)
}
