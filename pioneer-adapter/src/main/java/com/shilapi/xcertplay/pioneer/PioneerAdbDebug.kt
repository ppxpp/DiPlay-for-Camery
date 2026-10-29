package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.UUID

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
        val output=File(context.cacheDir,"adb-toggle-${UUID.randomUUID()}.log")
        output.writeText("")
        try {
            val command="CLASSPATH=${PioneerRootDispatcher.quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                "com.shilapi.xcertplay.pioneer.PioneerAdbHelper ${if(enable) "1" else "0"} " +
                "> ${PioneerRootDispatcher.quote(output.path)} 2>&1 </dev/null &"
            PioneerRootDispatcher.dispatch(command)
            val deadline=SystemClock.elapsedRealtime()+15_000
            while(SystemClock.elapsedRealtime()<deadline) {
                val result=output.readText()
                check(!result.contains("DIPLAY_ADB_RESULT=ERROR")) { "原厂 ADB 切换失败" }
                if(result.contains("DIPLAY_ADB_RESULT=OK") && enabled()==enable)return
                Thread.sleep(250)
            }
            error("ADB 状态未确认，请稍后刷新")
        } finally { output.delete() }
    }
}
