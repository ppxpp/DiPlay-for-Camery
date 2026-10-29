package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.CrashRecord
import com.shilapi.xcertplay.host.BuildConfig
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure

internal fun createAnalyticsTracker(context: Context): AnalyticsTracker = UmengAnalyticsTracker()

/**
 * Called at process start, before the disclaimer gate, so [UmengAnalyticsTracker.start] does not trip
 * the SDK's own compliance warning. `preInit` only records key and channel; Umeng starts collecting at
 * `init`, which stays behind the gate.
 */
internal fun preInitAnalytics(context: Context) {
    UMConfigure.preInit(context, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_CHANNEL)
}

/**
 * Umeng U-App for sessions and events, U-APM for crash and ANR reporting.
 *
 * `init` is the call that makes the SDK register its session tracking and its crash handler, so it only
 * ever runs from here, behind the disclaimer gate in [Analytics], and `submitPolicyGrantResult` states
 * that much out loud for the SDK's own compliance logic.
 *
 * The collection toggles stay off because a head unit has no cellular modem to read IMEI, IMSI or ICCID
 * from, and an installed-app list has no business being attached to a CarPlay log.
 */
internal class UmengAnalyticsTracker : AnalyticsTracker {
    private var application: Context? = null

    override val isEnabled: Boolean get() = true

    override fun start(context: Context) {
        application = context
        UMConfigure.setLogEnabled(BuildConfig.DEBUG)
        UMConfigure.enableImeiCollection(false)
        UMConfigure.enableImsiCollection(false)
        UMConfigure.enableIccidCollection(false)
        UMConfigure.enableWiFiMacCollection(false)
        UMConfigure.enableAplCollection(false)
        UMConfigure.submitPolicyGrantResult(context, true)
        UMConfigure.init(
            context,
            BuildConfig.UMENG_APP_KEY,
            BuildConfig.UMENG_CHANNEL,
            UMConfigure.DEVICE_TYPE_PHONE,
            "",
        )
        // U-APM just took the default handler; the local crash file still has to be written first.
        CrashRecord.ensureOutermost(context)
    }

    override fun event(name: String, attributes: Map<String, String>) {
        val context = application ?: return
        MobclickAgent.onEvent(context, name, attributes)
    }
    override fun metric(name: String, value: Long, attributes: Map<String, String>) {
        val context = application ?: return
        val clipped = value.coerceIn(0, Int.MAX_VALUE.toLong())
        val dimensions = if (clipped != value) attributes + ("value_clamped" to "true") else attributes
        MobclickAgent.onEventValue(context, name, dimensions, clipped.toInt())
    }
}
