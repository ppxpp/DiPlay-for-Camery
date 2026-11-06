package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.SystemClock

/** USB diagnostics only. Does not depend on ADB, Bluetooth, or a CarPlay session. */
object PioneerAdbDebug {
    fun supported() = PioneerUsbPolicy.matchesModel()
    private fun property(name: String): String = Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java).invoke(null,name) as String

    /** null means changing/unavailable, never a cached preference presented as actual state. */
    fun enabled(): Boolean? = runCatching {
        actualState(property("sys.usb.state"), property("init.svc.adbd"))
    }.getOrNull()

    internal fun actualState(usbState: String, daemonState: String): Boolean? {
        val adb = "adb" in usbState.split(',')
        return when {
            adb && daemonState == "running" -> true
            !adb && daemonState == "stopped" -> false
            else -> null
        }
    }

    @Synchronized fun setEnabled(context: Context, enable: Boolean) {
        check(supported()) { "仅支持凯美瑞 A 导航" }
        if(enabled()==enable)return
        val result = PioneerRootSession.request(context, "ADB", org.json.JSONObject().put("enabled", enable), 15_000)
        check(result.optBoolean("accepted")) { "原厂 ADB 切换未受理" }
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (enabled() == enable) return
            Thread.sleep(100)
        }
        error("ADB 状态未确认 enable=$enable state=${enabled()}")
    }
}
