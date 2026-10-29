package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** Undo our abandoned mutation only AFTER ordinary Bluetooth is connected; caller excludes a live lease. */
internal class PioneerBootProfileRecovery(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit,
) {
    private val journal=context.getSharedPreferences("pioneer-wireless-profile-rollback",Context.MODE_PRIVATE)
    private val boot=PioneerCallbackRepair.boot()
    private var completed=false

    fun recoverIfNeeded(snapshot: PioneerBluetoothSnapshot): Boolean {
        if(completed)return true
        if(cancelled.get())throw InterruptedIOException("Pioneer boot recovery cancelled")
        val records=journal.all.filter { (address,value) ->
            address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && value is Int
        }
        if(records.isEmpty()) { completed=true;return true }
        val devices=snapshot.devices()
        // The OEM may not yet have published its paired list during boot. Keep waiting,
        // rather than retiring the journal or initializing its SDK without a target.
        if(devices.isEmpty())return false
        if(records.size!=1 || devices.size!=1)conflict("重启后蓝牙设备归属不明确，已保留恢复记录")
        val (address,value)=records.entries.single()
        val device=devices.single()
        if(!PioneerBlueforceNative.sameAddress(device.address,address))conflict("重启后蓝牙配对设备已变化，已保留恢复记录")
        val saved=value as Int
        val binder=PioneerBinder(log)
        // Do not initialize the vendor SDK or write profiles before OEM HFP/A2DP.
        if(snapshot.connectedAddress(address)==null)return false
        val current=binder.output(81,device.handle) ?: return false
        val action=PioneerProfileRecovery.decide(saved,current,journal.getString("boot:$address",null),boot,devices.size)
        if(action==PioneerProfileRecovery.Action.BLOCK)conflict("重启后蓝牙配置不符合上次临时变更，已保留恢复记录")
        if(action==PioneerProfileRecovery.Action.RESTORE_SAVED && current!=saved) {
            // A previous boot cannot own a live port in this boot. Never disconnect a
            // current OEM/other application's port to force recovery.
            val repair=PioneerCallbackRepair(context,cancelled,log)
            val ports=repair.audit().ports
            val active=ports.filter { it.connected || it.lowerStatus!=0 }
            if(active.isNotEmpty()) {
                val port=active.singleOrNull()
                if(port==null || port.index!=0 || !port.connected || port.lowerStatus!=0 ||
                    !PioneerBlueforceNative.sameAddress(port.address,address) ||
                    !PioneerCallbackRepair.ownsPreviousPort(context,address,port.uuid))
                    conflict("蓝牙串口存在其他占用，未强制恢复配置")
            } else if(ports.any { it.cached!=0 }) {
                // Same-boot process death can leave the cache pending while the real port
                // is idle. The helper rechecks native state before clearing that cache.
                val cleared=repair.clearIdle()
                if(cleared.ports.any { it.connected || it.lowerStatus!=0 || it.cached!=0 })
                    conflict("蓝牙串口残留未清理，已保留恢复记录")
            }
            log("Pioneer interrupted boot profile recovery after OEM connection: current=$current saved=$saved")
            // SDK initialization is allowed only for this narrowly owned rollback.
            val adapter=PioneerAdapter().apply { initialize() }
            val paired=adapter.paired()
            val selected=paired.singleOrNull { PioneerBlueforceNative.sameAddress(it.address,address) }
            if(paired.size!=1 || selected==null || binder.output(81,selected.handle)!=current)
                conflict("恢复前蓝牙设备或配置再次变化，已保留恢复记录")
            if(active.isNotEmpty())repair.prepareBaseline(selected,binder)
            if(cancelled.get())throw InterruptedIOException("Pioneer boot recovery cancelled")
            PioneerBluetoothLink.requireConnected(adapter,selected)
            check(adapter.connectProfiles(selected,saved)) { "Pioneer boot profile restore rejected" }
            // Once the write is issued, finish confirming/retiring it even if the
            // connection request is cancelled. A timeout always retains the journal.
            val deadline=SystemClock.elapsedRealtime()+10_000
            while(binder.output(81,selected.handle)!=saved || adapter.query("isConnecting")!=false) {
                check(SystemClock.elapsedRealtime()<deadline) { "Pioneer boot profile restore timed out" }
                Thread.sleep(250)
            }
        }
        check(journal.edit().remove(address).remove("boot:$address").commit()) { "Cannot retire boot profile rollback" }
        log("Pioneer interrupted boot profile recovery confirmed action=$action; rechecking OEM Bluetooth")
        completed=true
        return true
    }

    private fun conflict(message: String): Nothing =
        throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT,message)
}
