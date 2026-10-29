package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/** The temporary profile belongs to Bluetooth bootstrap, not the Wi-Fi CarPlay session. */
internal class PioneerSerialConnection(
    private val context: Context,
    private val adapter: PioneerAdapter,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit,
    private val device: PairedDevice,
) : Closeable {
    private val adaptationAuthorized=com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()
    private val binder=PioneerBinder(log)
    private val repair=PioneerCallbackRepair(context,cancelled,log)
    private val journal=context.getSharedPreferences("pioneer-wireless-profile-rollback",Context.MODE_PRIVATE)
    private val profileBootKey="boot:"+device.address
    private val serialJournal=context.getSharedPreferences("pioneer-wireless-serial-lease",Context.MODE_PRIVATE)
    private var owned=false
    private var changedProfile=false
    private var original: Int?=null
    private var temporary: Int?=null
    private var bootstrapReleased=false
    private var released=false
    private var closed=false
    private var stream: BlockingDuplexByteStream?=null
    private fun idle()=(0..8).all { binder.query(134,it)==listOf(0,0) }
    private fun selected(device: PairedDevice)=PioneerBlueforceNative.sameAddress(PioneerBlueforceNative.address(),device.address)
    fun connect(): BlockingDuplexByteStream {
        PioneerBlueforceNative.configure(context,log)
        PioneerBlueforceNative.verifyRuntime()
        repair.prepareBaseline(device,binder)
        check(serialJournal.edit().putString("phone",device.address).putString("boot",PioneerCallbackRepair.boot()).commit()) { "Cannot persist serial ownership" }
        check(PioneerBlueforceNative.open()=="OK")
        try {
            check(idle()) { "存在占用串口；仅允许从全部端口空闲开始" }
            val paired = adapter.paired()
            check(paired.any { it.handle == device.handle && it.address == device.address }) { "目标配对已变化" }
            original = binder.output(81, device.handle) ?: error("无法读取目标配置")
            if(journal.contains(device.address)) {
                val saved=journal.getInt(device.address,-1)
                when(PioneerProfileRecovery.decide(saved,checkNotNull(original),
                    journal.getString(profileBootKey,null),PioneerCallbackRepair.boot(),paired.size)) {
                    PioneerProfileRecovery.Action.RESTORE_SAVED -> {
                        if(original!=saved) {
                            check(adapter.connectProfiles(device,saved)) { "Previous Pioneer profile restore rejected" }
                            waitFor("Previous Pioneer profile recovery",10000) { binder.output(81,device.handle)==saved }
                            original=saved
                        }
                    }
                    PioneerProfileRecovery.Action.ADOPT_CURRENT ->
                        log("Retired stale profile rollback: saved=$saved current=$original; idle ports verified, current OEM profile preserved")
                    PioneerProfileRecovery.Action.BLOCK -> throw com.shilapi.xcertplay.network.WirelessStartupException(
                        com.shilapi.xcertplay.network.WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT,
                        "车机蓝牙配置发生变化，无法安全恢复；请检查原厂蓝牙设置后重试")
                }
                check(journal.edit().remove(device.address).remove(profileBootKey).commit())
            }
            if (original!! and 128 == 0) {
                // Firmware connectProfile updates other devices' overlapping settings.
                // Restrict this experiment to one paired device instead of guessing rollback.
                check(paired.size == 1) { "临时配置实验要求仅有一台已配对设备；不会自动删除其他配对" }
                check(original!! and 0xff.inv() == 0) { "配置含未知位，不能无损恢复" }
                check((binder.output(79, device.handle) ?: 0) and 128 != 0) { "目标不支持 0x80 配置位" }
                temporary = original!! or 128
                if (temporary != original) {
                    // Record before the mutation: even a rejected IPC may have changed state.
                    check(journal.edit().putInt(device.address,checkNotNull(original)).putString(profileBootKey,PioneerCallbackRepair.boot()).commit()) { "无法保存原配置，未执行变更" }
                    changedProfile = true
                    log("临时 connectProfile mask=$temporary；原配置=$original。此操作会触发连接状态机")
                    owned = true
                    check(adapter.connectProfiles(device, checkNotNull(temporary))) { "connectProfile 未受理；将回读并尝试恢复" }
                    Thread.sleep(2000)
                    waitFor("临时配置完成", 30000) {
                        binder.output(81, device.handle) == temporary && adapter.query("isConnecting") == false
                    }
                    // Preparation may have used a nonzero UUID index. Never feed iAP2
                    // into that connection; explicitly disconnect and request index0 next.
                    if (!idle()) {
                        check(selected(device)) { "临时连接不是所选目标；停止，不接管其他连接" }
                        log("清理配置状态机产生的 Apple 端口；后续明确用 index0/iAP2 UUID")
                        check(binder.stopCandidate() == listOf(0)) { "临时连接断开未受理" }
                        waitFor("临时端口释放", 10000) { idle() }
                    }
                    owned = false
                }
            }
            val mask = binder.output(81, device.handle) ?: error("配置回读失败")
            check(mask and 0xf0 != 0) { "目标没有串口配置位；使用单设备临时配置测试" }
            check(paired.filter { it.handle != device.handle }.all {
                (binder.output(81, it.handle) ?: error("其他设备配置未知")) and 0xf0 == 0
            }) { "存在其他串口候选，无法保证 index0 选中目标" }
            check(adapter.query("getSppEnableApple") == true) { "系统 Apple 串口开关关闭；未修改全局开关" }
            check(idle()) { "连接前串口已被占用" }
            check(!cancelled.get()) { "测试已停止" }
            log("连接前回调统计 ${PioneerBlueforceNative.stats()}；清空旧流，避免配置阶段残留字节")
            check(PioneerBlueforceNative.close() == 0) { "连接前注销失败" }
            check(PioneerBlueforceNative.open() == "OK") { "连接前重新注册失败" }
            owned = true // Even a timed-out/rejected IPC may have changed the server.
            check(binder.startCandidate() == listOf(0)) { "index0 连接未受理；-109 表示选择阶段没有目标" }
            val cacheReady = runCatching {
                waitFor("index0 连接", 20000) { binder.query(134, 0) == listOf(0, 1) }
            }
            if (cancelled.get()) error("测试已停止")
            val audited = repair.audit()
            val port = audited.ports.single { it.index == 0 }
            check(audited.registered && port.connected && port.lowerStatus == 0 &&
                PioneerBlueforceNative.sameAddress(port.address,device.address)) {
                "index0 底层连接未确认：cache=${port.cached} native=${port.connected}/${port.lowerStatus} target=${port.address}；${cacheReady.exceptionOrNull()?.message}"
            }
            check(port.uuid.replace("-", "").equals("00000000DECAFADEDECADEAFDECACAFE",true)) {
                "底层串口 UUID 不符：${port.uuid}；禁止发送"
            }
            if (cacheReady.isFailure) log("上层状态未更新；已核对底层目标、UUID、连接标志。继续一次真实 iAP2 验证，不把该状态当作成功")
            check(selected(device)) { "串口地址与所选 iPhone 不符，禁止发送" }
            log("index0 已连接，地址=${PioneerBlueforceNative.address()}；开始 DiPlay iAP2 链路验证")

            return PioneerBlueforceNative.stream(device.address,cancelled,log).also { stream=it }
        } catch (failure: Throwable) {
            runCatching { close() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }
    @Synchronized fun closeBootstrap() {
        if(bootstrapReleased) { restoreProfile();return }
        stream?.close()
        val cleanupRepair=PioneerCallbackRepair(context,AtomicBoolean(false),log,cleanupAuthorized=adaptationAuthorized)
        released=!owned
        try {
            if(owned) {
                var port=cleanupRepair.audit().ports.single { it.index==0 }
                if(port.connected || port.lowerStatus!=0) {
                    check(PioneerSerialOwnership.matchesPort(device.address,port.address,port.uuid,port.index)) {
                        "Pioneer port identity changed; not disconnecting"
                    }
                    check(binder.stopCandidate()==listOf(0)) { "Pioneer disconnect rejected" }
                    waitFor("Pioneer native disconnect",8000,true) {
                        port=cleanupRepair.audit().ports.single { it.index==0 }
                        !port.connected && port.lowerStatus==0
                    }
                }
                if(!idle()) cleanupRepair.clearIdle()
                waitFor("Pioneer disconnect",8000,true) { idle() }
            }
            released=idle() && adapter.query("isConnecting")==false
            check(released) { "Pioneer serial release unconfirmed" }
            check(serialJournal.edit().remove("phone").commit()) { "Cannot retire serial ownership" }
            bootstrapReleased=true
        } finally { PioneerBlueforceNative.close() }
        // Wi-Fi no longer needs the serial profile. Do not leave it enabled throughout
        // CarPlay: an ignition-off would bypass full-session close and retain the setting.
        restoreProfile()
    }
    private fun restoreProfile() {
        if(!changedProfile)return
        check(released) { "Pioneer release unconfirmed; profile rollback retained" }
        val now=binder.output(81,device.handle)
        if(now!=temporary && now!=original) throw com.shilapi.xcertplay.network.WirelessStartupException(
            com.shilapi.xcertplay.network.WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT,
            "车机蓝牙配置发生变化，已保留恢复记录；请检查原厂蓝牙设置后重试")
        if(now!=original) {
            check(adapter.connectProfiles(device,checkNotNull(original)))
            waitFor("Pioneer profile restore",10000,true) { binder.output(81,device.handle)==original && adapter.query("isConnecting")==false }
        }
        check(journal.edit().remove(device.address).remove(profileBootKey).commit())
        changedProfile=false
        log("Pioneer Bluetooth bootstrap profile restored; Wi-Fi session independent")
    }
    @Synchronized override fun close() {
        if(closed)return
        try {
            closeBootstrap()
            closed=true
        } finally { PioneerBlueforceNative.close() }
    }
    private fun waitFor(description: String, timeout: Long, cleanup: Boolean=false, ready: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(true) {
            check(cleanup || !cancelled.get()) { "Pioneer connection cancelled" }
            if(ready())return
            check(SystemClock.elapsedRealtime()<end) { "$description timed out" }
            Thread.sleep(250)
        }
    }
}
