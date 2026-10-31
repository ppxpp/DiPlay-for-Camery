package com.shilapi.xcertplay.btprobe

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException

internal data class PairedDevice(val native: Any, val name: String, val address: String, val handle: Int) {
    override fun toString() = "$name · $address"
}

/** 使用车机启动类路径中的 SDK；查询及显式实验用 profile 连接；后者会改变厂商连接状态机。 */
internal class PioneerAdapter {
    @Volatile private var timedOut = false
    private fun <T> bounded(name: String, action: () -> T): T {
        check(!timedOut) { "先锋 SDK 本轮已超时，请检查服务并重启测试应用" }
        val task=FutureTask(action)
        Thread(task,"pioneer-sdk-$name").apply { isDaemon=true;start() }
        return try { task.get(3000,TimeUnit.MILLISECONDS) }
        catch (failure: TimeoutException) { timedOut=true;error("先锋 SDK $name 超时，停止后续调用") }
        catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
    }
    private var type: Class<*>? = null
    private var adapter: Any? = null
    fun initialize() {
        type = Class.forName("jp.pioneer.ceam.bluetooth.BluetoothAdapter")
        adapter = bounded("getDefaultAdapter") { type!!.getMethod("getDefaultAdapter").invoke(null) }
            ?: error("先锋适配器为空")
    }
    fun query(name: String): Any? = bounded(name) { type!!.getMethod(name).invoke(adapter) }
    fun query(name: String, device: PairedDevice): Any? =
        bounded(name) { type!!.getMethod(name, device.native.javaClass).invoke(adapter, device.native) }
    fun connectProfiles(device: PairedDevice, mask: Int): Boolean {
        check(mask and 0xff.inv() == 0) { "未知 profile 位，不能无损回写" }
        val mapping = listOf(107 to 1, 108 to 2, 109 to 4, 113 to 8,
            112 to 16, 111 to 32, 110 to 64, 114 to 128)
        val pack = type!!.getDeclaredMethod("profilePackage", List::class.java).apply { isAccessible = true }
        mapping.forEach { (profile, bit) ->
            check((pack.invoke(adapter, listOf(profile)) as Number).toInt() == bit) { "运行时 SDK profile 映射与固件证据不符" }
        }
        val profiles = mapping.filter { mask and it.second != 0 }.map { it.first }
        return bounded("connectProfile") { type!!.getMethod("connectProfile", device.native.javaClass, List::class.java)
            .invoke(adapter, device.native, profiles) == true }
    }
    fun paired(): List<PairedDevice> = (query("getBondedDevices") as? List<*>).orEmpty().filterNotNull().map {
        val cls = it.javaClass
        PairedDevice(it, cls.getMethod("getName").invoke(it).toString(),
            cls.getMethod("getAddress").invoke(it).toString(),
            (cls.getMethod("getHandler").invoke(it) as Number).toInt())
    }
}
