package com.shilapi.xcertplay.btprobe

import android.os.SystemClock
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived diagnostic session; never claims MFi/CarPlay success from linkReady. */
internal class PioneerSerialProbe(
    private val context: Context,
    private val adapter: PioneerAdapter,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit
) {
    private val binder = PioneerBinder(log)
    private val repair = PioneerCallbackRepair(context,cancelled,log)
    private val journal = context.getSharedPreferences("probe-profile-rollback",Context.MODE_PRIVATE)
    private fun idle(): Boolean = (0..8).all { binder.query(134, it) == listOf(0, 0) }
    private fun selected(device: PairedDevice) =
        PioneerBlueforceNative.sameAddress(PioneerBlueforceNative.address(), device.address)

    fun run(device: PairedDevice, active: Boolean, prepare: Boolean = false,
            onConnected: ((com.shilapi.xcertplay.transport.BlockingDuplexByteStream) -> Unit)? = null) {
        PioneerBlueforceNative.loadVerified()
        if (active) repair.prepareBaseline(device,binder)
        log("原生桥接 open=${PioneerBlueforceNative.open().also { check(it == "OK") { it } }}")
        var owned = false
        var changedProfile = false
        var original: Int? = null
        var temporary: Int? = null
        try {
            if (!active) {
                log("只观察 event500/index0，20秒内不请求连接、不写数据、不改配置")
                val end = SystemClock.elapsedRealtime() + 20000
                while (!cancelled.get() && SystemClock.elapsedRealtime() < end) {
                    PioneerBlueforceNative.read(4096, 250)?.takeIf { it.isNotEmpty() }?.let {
                        log("原始 RX ${it.size}B ${it.take(48).joinToString(" ") { b -> "%02X".format(b.toInt() and 255) }}")
                    }
                }
                return
            }
            check(idle()) { "存在占用串口；仅允许从全部端口空闲开始" }
            val paired = adapter.paired()
            check(paired.any { it.handle == device.handle && it.address == device.address }) { "目标配对已变化" }
            original = binder.output(81, device.handle) ?: error("无法读取目标配置")
            check(!journal.contains(device.address) || journal.getInt(device.address,-1)==original) {
                "存在未恢复的原 profile=${journal.getInt(device.address,-1)}；请先使用恢复原 profile 按钮"
            }
            if (prepare) {
                // Firmware connectProfile updates other devices' overlapping settings.
                // Restrict this experiment to one paired device instead of guessing rollback.
                check(paired.size == 1) { "临时配置实验要求仅有一台已配对设备；不会自动删除其他配对" }
                check(original and 0xff.inv() == 0) { "配置含未知位，不能无损恢复" }
                check((binder.output(79, device.handle) ?: 0) and 128 != 0) { "目标不支持 0x80 配置位" }
                temporary = original or 128
                if (temporary != original) {
                    // Record before the mutation: even a rejected IPC may have changed state.
                    check(journal.edit().putInt(device.address,original).commit()) { "无法保存原配置，未执行变更" }
                    changedProfile = true
                    log("临时 connectProfile mask=$temporary；原配置=$original。此操作会触发连接状态机")
                    owned = true
                    check(adapter.connectProfiles(device, temporary)) { "connectProfile 未受理；将回读并尝试恢复" }
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
            val stream = PioneerBlueforceNative.stream(device.address, cancelled, log)
            if (onConnected != null) onConnected(stream) else {
                val verified = Iap2LinkProbe.verify(stream, log)
                log("基础链路测试 iap2Verified=$verified；完整蓝牙阶段请使用 CarPlay 验证按钮")
            }
        } finally {
            val cleanupRepair=PioneerCallbackRepair(context,AtomicBoolean(false),log)
            runCatching { log("原生回调统计 ${PioneerBlueforceNative.stats()}") }.onFailure { log("宿主统计不可用：${it.message}") }
            var released = !owned
            if (owned) {
                runCatching {
                    if (idle()) {
                        check(adapter.query("isConnecting") == false) { "配置连接状态机仍在运行；未确认清理，请人工检查" }
                        released = true; return@runCatching
                    }
                    val audited=cleanupRepair.audit()
                    val port=audited.ports.single { it.index==0 }
                    if (!port.connected && port.lowerStatus==0) {
                        cleanupRepair.clearIdle();released=idle();return@runCatching
                    }
                    check(PioneerBlueforceNative.sameAddress(port.address,device.address)) {
                        "底层端口地址不符/未知，未断开；请从原车蓝牙界面检查"
                    }
                    log("清理本次串口=${binder.stopCandidate()}")
                    val end = SystemClock.elapsedRealtime() + 8000
                    while (!idle() && SystemClock.elapsedRealtime() < end) Thread.sleep(250)
                    released = idle()
                }.onFailure { log("串口清理失败：${it.message}") }
            }
            if (changedProfile) runCatching {
                check(released) { "端口未释放，保留配置并报告，避免再次触发连接状态机" }
                val now = binder.output(81, device.handle)
                check(now == temporary || now == original) { "配置被外部修改，未覆盖：current=$now original=$original" }
                if (now != original) {
                    check(adapter.connectProfiles(device, original!!)) { "恢复请求被拒绝" }
                    waitFor("原配置恢复",10000,true) { binder.output(81,device.handle)==original }
                }
                journal.edit().remove(device.address).commit()
                log("已恢复原 profile=$original；原 HFP/A2DP 可能重新连接")
            }.onFailure { log("需要人工恢复 profile=$original：${it.message}") }
            runCatching { log("原生监听注销=${PioneerBlueforceNative.close()}") }.onFailure { log("宿主关闭异常：${it.message}") }
        }
    }
    fun restoreProfile(device: PairedDevice, fallbackMask: Int) {
        val original=if(journal.contains(device.address))journal.getInt(device.address,-1) else fallbackMask
        check(original in 0..255) { "请填写此前记录的原 profile 掩码" }
        check(adapter.paired().size==1) { "恢复配置要求仅一台配对设备，避免更改其他设备配置" }
        repair.prepareBaseline(device,binder)
        val supported=binder.output(79,device.handle) ?: error("无法读取支持掩码")
        check(original and supported.inv()==0) { "原配置含设备不支持的位" }
        log("恢复原 profile=$original（优先使用本 APP 保存的回滚记录）")
        check(adapter.connectProfiles(device,original)) { "原配置恢复未受理" }
        waitFor("原配置恢复",10000) { binder.output(81,device.handle)==original }
        journal.edit().remove(device.address).commit()
        log("原 profile 已恢复；原车通话/音频状态仍需检查")
    }
    private fun waitFor(description: String, timeout: Long, cleanup: Boolean = false, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (true) {
            check(cleanup || !cancelled.get()) { "测试已停止：$description" }
            if (ready()) return
            check(SystemClock.elapsedRealtime() < end) { "$description 超时" }
            Thread.sleep(250)
        }
    }
}
