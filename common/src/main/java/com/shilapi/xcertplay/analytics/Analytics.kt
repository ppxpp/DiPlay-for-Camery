package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.Disclaimer
import com.shilapi.xcertplay.host.BuildConfig

/**
 * App-facing analytics entry point. A singleton rather than an injected dependency because the launcher
 * card, the session service and the main activity can each be the first thing to run, and all of them
 * need the same rule: nothing is collected before the startup disclaimer has been accepted.
 *
 * Nothing in here may throw. An analytics outage is bad enough on its own; it must not take a live
 * CarPlay session down with it, so every call into the tracker is fenced and a failure just stops
 * reporting for the process.
 */
object Analytics {
    private const val PREFS = "diplay"
    private const val KEY_DISABLED = "analytics_disabled"

    @Volatile
    private var tracker: AnalyticsTracker = NoopAnalyticsTracker

    /** Whether this APK ships an analytics SDK at all. Settings and diagnostic reports quote it. */
    val isBundled: Boolean get() = BuildConfig.ANALYTICS_UMENG_BUNDLED

    val isReporting: Boolean get() = tracker.isEnabled

    /**
     * The pre-consent half of Umeng's documented flow: it records the app key without collecting
     * anything, and is meant to run at process start. The SDK raises a compliance warning at `start()`
     * when this never happened, so it belongs next to the crash installer rather than an activity.
     */
    fun preInit(context: Context) {
        runCatching { preInitAnalytics(context.applicationContext) }
    }

    fun startIfAllowed(context: Context) {
        if (isReporting || !isAllowed(context)) return
        val application = context.applicationContext
        val candidate = runCatching { createAnalyticsTracker(application) }.getOrNull() ?: NoopAnalyticsTracker
        val started = runCatching { candidate.start(application) }.isSuccess
        tracker = if (started) candidate else NoopAnalyticsTracker
    }

    fun event(name: String, attributes: Map<String, String> = emptyMap()) {
        val current = tracker
        if (!current.isEnabled) return
        runCatching { current.event(name, attributes) }
    }

    fun isAllowed(context: Context): Boolean =
        isBundled &&
            Disclaimer.acceptedForCurrentVersion(context) &&
            !prefs(context).getBoolean(KEY_DISABLED, false)

    /**
     * The switch Settings writes. Turning it off drops new events immediately; the SDK's session and
     * crash handler cannot be uninstalled from a running process, so they stop at the next launch,
     * which is exactly what the settings copy promises.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLED, !enabled).apply()
        if (enabled) startIfAllowed(context) else tracker = NoopAnalyticsTracker
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
