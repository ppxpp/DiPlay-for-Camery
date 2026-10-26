package com.shilapi.xcertplay.pioneer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** A proven root connection grants USB itself; avoid Android's separate app-choice prompt. */
object PioneerUsbLaunchPolicy {
    private const val ALIAS = "com.shilapi.xcertplay.IphoneUsbAttachActivity"
    private const val RECEIVER = "com.shilapi.xcertplay.PioneerUsbAttachReceiver"

    fun restoreSystemLaunchIfDisabled(context: Context) {
        if (CamryAdaptation.enabled()) return
        val manager = context.packageManager
        runCatching {
            manager.setComponentEnabledSetting(ComponentName(context.packageName, ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            manager.setComponentEnabledSetting(ComponentName(context.packageName, RECEIVER),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    @Synchronized fun rootReady(context: Context) {
        if (!PioneerUsbPolicy.matchesDevice() || enabled(context)) return
        val manager = context.packageManager
        val receiver = ComponentName(context.packageName, RECEIVER)
        val alias = ComponentName(context.packageName, ALIAS)
        try {
            // Install the replacement before removing the resolver candidate. These states persist.
            manager.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            manager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            PioneerDebugRuntime.log(context, "USB_LAUNCH_POLICY root-ready broadcast launch enabled")
        } catch (error: Exception) {
            runCatching { manager.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
            runCatching { manager.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            PioneerDebugRuntime.log(context, "USB_LAUNCH_POLICY fallback ${error.javaClass.simpleName}")
        }
    }

    fun snapshot(context: Context): org.json.JSONObject = org.json.JSONObject()
        .put("enabled", enabled(context))
        .put("aliasState", context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, ALIAS)))
        .put("receiverState", context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, RECEIVER)))

    fun enabled(context: Context): Boolean = PioneerUsbPolicy.matchesDevice() &&
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, RECEIVER)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
        context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, ALIAS)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}
