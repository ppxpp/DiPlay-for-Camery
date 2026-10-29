package com.shilapi.xcertplay.analytics

import android.content.Context

/**
 * The whole analytics surface app code is allowed to touch.
 *
 * Which implementation gets linked is decided at compile time by `-Pdiplay.analytics`: `src/umeng`
 * and `src/noUmeng` each provide [createAnalyticsTracker], and only one of them is in the source set,
 * so a disabled build contains no Umeng class at all rather than an unused one.
 */
interface AnalyticsTracker {
    /** False when nothing is being collected, either by build choice or by the user's opt-out. */
    val isEnabled: Boolean

    /** Begin collecting. Implementations may start network traffic here, so callers gate on consent. */
    fun start(context: Context)

    fun event(name: String, attributes: Map<String, String> = emptyMap())

    /** One integer observation; attributes remain bounded categorical dimensions. */
    fun metric(name: String, value: Long, attributes: Map<String, String> = emptyMap()) {}
}

/** Linked before consent, and permanently in a `-Pdiplay.analytics=none` build. */
internal object NoopAnalyticsTracker : AnalyticsTracker {
    override val isEnabled: Boolean get() = false
    override fun start(context: Context) = Unit
    override fun event(name: String, attributes: Map<String, String>) = Unit
}
