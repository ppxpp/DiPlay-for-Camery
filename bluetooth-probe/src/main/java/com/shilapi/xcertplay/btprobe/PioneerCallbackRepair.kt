package com.shilapi.xcertplay.btprobe

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Root is used for the server's callback registration, never for a second BT stack. */
internal class PioneerCallbackRepair(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit
) {
    data class Port(val index: Int, val cached: Int, val lowerStatus: Int, val connected: Boolean,
                    val address: String, val uuid: String)
    data class Result(val status: String, val registered: Boolean, val pid: Int, val ports: List<Port>)

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer,0,n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun verifyFirmware() {
        check(PioneerBinder.firmwareKnown() && !Process.is64Bit()) { "只支持已核对的先锋 ARM32 固件" }
        val allowed = mapOf(
            "/system/bin/blueforcemanage" to setOf("61c4b1150b35f61f86f13ab9146fd54f32bf89a61094e2cb13016b939d091541",
                "33b419ded996e7e58f70b0a4cf5dc8348462894aaf8e611b16cf60e28ad714ee"),
            "/system/lib/libbluep.so" to setOf("8310b5e3936e33bdc898e948bf71a86373c18c25038209605af7c0d297d6a66c"))
        allowed.forEach { (path, expected) -> check(digest(File(path)) in expected) { "固件文件不匹配：$path；未执行 root 操作" } }
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
                active.single().lowerStatus == 0 && PioneerBlueforceNative.sameAddress(active.single().address,device.address)) {
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
        verifyFirmware()
        check(!cancelled.get()) { "测试已停止" }
        val root = File(context.filesDir,"blueforce-callback").apply { mkdirs(); Os.chmod(path,0x1c0) }
        root.listFiles()?.filter { it.isDirectory && System.currentTimeMillis()-it.lastModified()>3600000 }
            ?.forEach { it.deleteRecursively() }
        val dir=File(root,UUID.randomUUID().toString()).apply { mkdir(); Os.chmod(path,0x1c0) }
        val result=File(dir,"result.txt").apply { writeText(""); Os.chmod(path,0x180) }
        val binary=File(context.codeCacheDir,"blueforce-callback-repair")
        val temporary=File(context.codeCacheDir,"callback-${UUID.randomUUID()}")
        context.assets.open("blueforce-callback-repair").use { src -> temporary.outputStream().use { src.copyTo(it) } }
        Os.chmod(temporary.path,0x1c0)
        check(temporary.renameTo(binary)) { "无法部署 root 诊断 helper" }
        val failure=AtomicReference<Throwable?>()
        Thread({ runCatching { PioneerRootCallbackLauncher.launch(context,binary,result,mode) }
            .onFailure { failure.set(it) } },"blueforce-root-dispatch").apply { isDaemon=true;start() }
        val until=SystemClock.elapsedRealtime()+15000
        var cancelMarked=false
        while(SystemClock.elapsedRealtime()<until) {
            if(cancelled.get() && !cancelMarked) {
                File(result.path+".cancel").writeText("cancel");cancelMarked=true
            }
            failure.get()?.let { throw IllegalStateException("DiPlay root 调度失败",it) }
            val text=result.readText()
            if(text.endsWith("complete=1\n")) {
                log("服务回调 $mode\n${text.trim()}")
                check(!cancelMarked) { "已停止；root 任务已经收尾，见审计日志" }
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
            Thread.sleep(50)
        }
        File(result.path+".cancel").writeText("timeout")
        error("root 回调任务超时；已取消尚未开始的操作。停止测试并检查蓝牙服务，禁止继续发起连接")
    }
}
