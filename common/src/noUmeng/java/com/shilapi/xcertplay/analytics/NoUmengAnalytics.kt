package com.shilapi.xcertplay.analytics

import android.content.Context

/**
 * Compiled for `-Pdiplay.analytics=none`. Returning the no-op keeps the call sites in one piece while
 * the Umeng artifacts stay off the classpath entirely, which is what NoUmengPackagingTest asserts.
 */
internal fun createAnalyticsTracker(context: Context): AnalyticsTracker = NoopAnalyticsTracker

/** Pre-consent half of the tracker contract; nothing to pre-initialise without an SDK. */
internal fun preInitAnalytics(context: Context) = Unit
