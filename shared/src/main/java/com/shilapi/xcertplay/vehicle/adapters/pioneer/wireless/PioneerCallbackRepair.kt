package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Root is used for the server's callback registration, never for a second BT stack. */
internal class PioneerCallbackRepair(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit,
    private val cleanupAuthorized: Boolean = false,
) {
    data class Port(val index: Int, val cached: Int, val lowerStatus: Int, val connected: Boolean,
                    val address: String, val uuid: String)
    data class Result(val status: String, val registered: Boolean, val pid: Int, val ports: List<Port>)

    companion object {
        fun boot(): String = File("/proc/sys/kernel/random/boot_id").readText().trim()
        fun ownsPreviousPort(context: Context, address: String, uuid: String): Boolean {
            val record=context.getSharedPreferences("pioneer-wireless-serial-lease",Context.MODE_PRIVATE)
            return PioneerSerialOwnership.mayRecover(record.getString("phone",null),record.getString("boot",null),
                boot(),address,address,uuid,0,true,0)
        }
    }
    private fun verifyRuntime() {
        check(com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled() || cleanupAuthorized) { "凯美瑞 A 导航适配未启用" }
        check(!Process.is64Bit()) { "先锋蓝牙回调处理需要32位进程" }
        check(File("/system/bin/blueforcemanage").canRead() && File("/system/lib/libbluep.so").canRead()) {
            "先锋蓝牙服务或运行库不可用"
        }
        // The isolated helper still checks instructions, vtables, pointer chains and idle state.
    }
    fun audit(): Result = task("--audit")
    fun repair(): Result {
        val before = audit()
        if (before.registered) { log("服务内部串口回调已注册，跳过补注册"); return before }
        log("确认内部串口回调缺失，调用服务自身注册接口；异常时可能重启蓝牙服务，不修改 ROM")
        val fixed = task("--repair")
        check(fixed.status in setOf("REGISTERED","ALREADY_REGISTERED") && fixed.registered) {
            "回调补注册未确认：${fixed.status}；停止连接测试"
        }
        return audit().also { check(it.registered) { "补注册后审计未通过" } }
    }
    fun clearIdle(): Result {
        val result = task("--clear-idle")
        check(result.status == "BASELINE_IDLE") { "未清理残留：${result.status}；底层忙时不会强行清零" }
        return audit()
    }
    fun prepareBaseline(device: PairedDevice, binder: PioneerBinder): Result {
        var current = repair()
        // Only the selected phone's lower-level identity can authorize disconnect.
        val active = current.ports.filter { it.connected || it.lowerStatus != 0 }
        if (active.isNotEmpty()) {
            check(active.size == 1 && active.single().index == 0 && active.single().connected &&
                active.single().lowerStatus == 0 &&
                ownsPreviousPort(context,device.address,active.single().uuid) && PioneerBlueforceNative.sameAddress(active.single().address,device.address)) {
                "底层存在其他占用/未完成请求；不自动清理，请先从原车蓝牙界面断开后重试"
            }
            log("补通回调后，正常断开目标 iPhone 的残留 index0；不强改连接标志")
            check(binder.stopCandidate() == listOf(0)) { "目标残留串口断开未受理" }
            val end = SystemClock.elapsedRealtime()+8000
            while (SystemClock.elapsedRealtime()<end) {
                check(!cancelled.get()) { "已停止，残留状态请人工检查" }
                if (binder.query(134,0)==listOf(0,0)) break
                Thread.sleep(250)
            }
            current = audit()
        }
        check(current.registered) { "服务回调注册丢失" }
        check(current.ports.all { !it.connected && it.lowerStatus == 0 }) { "底层端口尚未空闲，停止配置变更" }
        if (current.ports.any { it.cached == 2 || it.cached == 3 }) current=clearIdle()
        check(current.ports.all { it.cached == 0 }) { "上层仍有占用，不开始新连接" }
        return current
    }
    private fun task(mode: String): Result {
        verifyRuntime()
        check(!cancelled.get()) { "连接已停止" }
        val binary=File(context.codeCacheDir,"blueforce-callback-repair")
        val temporary=File(context.codeCacheDir,"callback-${UUID.randomUUID()}")
        context.assets.open("blueforce-callback-repair").use { src -> temporary.outputStream().use { src.copyTo(it) } }
        Os.chmod(temporary.path,0x1c0)
        check(temporary.renameTo(binary)) { "无法部署 root 诊断 helper" }
        val text=com.shilapi.xcertplay.pioneer.PioneerRootSession.request(context,"CALLBACK",
            org.json.JSONObject().put("mode",mode).put("binary",binary.canonicalPath)
                .put("cleanupAuthorized",cleanupAuthorized),15_000) { !cancelled.get() }.getString("text")
        check(!cancelled.get()) { "已停止；root 任务已经收尾，见审计日志" }
        check(text.endsWith("complete=1\n")) { "root 回调任务未完成" }
        log("服务回调 $mode\n${text.trim()}")
        val fields=text.lineSequence().mapNotNull { line ->
            val split=line.indexOf('=');if(split<1)null else line.substring(0,split) to line.substring(split+1)
        }.toMap()
        val status=fields["status"].orEmpty()
        check(status in setOf("AUDIT_OK","REGISTERED","ALREADY_REGISTERED","BASELINE_IDLE")) {
            "服务回调处理失败：$status；详情已记录（未进入 CarPlay 验证）"
        }
        val ports=(0..8).map { index ->
            val values=checkNotNull(fields["port$index"]) { "审计缺少 port$index" }.split(',')
            check(values.size==6) { "端口审计格式异常" }
            Port(index,values[0].toInt(),values[1].toInt(),values[2]=="1",values[3],values[4])
        }
        return Result(status,fields["serial_callback_registered_after"]=="1" ||
            fields["serial_callback_registered"]=="1",checkNotNull(fields["pid"]).toInt(),ports)
    }
}
