package com.shilapi.xcertplay.connection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.progress.ConnectionOutcome
import com.shilapi.xcertplay.orchestration.progress.ConnectionProgress
import com.shilapi.xcertplay.orchestration.progress.ConnectionStep
import com.shilapi.xcertplay.orchestration.progress.ConnectionTask
import com.shilapi.xcertplay.orchestration.progress.ConnectionTaskState
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Apple-style full-screen connection progress panel.
 *
 * Drop-in replacement for [ConnectionProgressPanel]. It keeps the exact same public API,
 * the same [ConnectionProgress] model, the same localized strings, the same
 * [DiPlayPalette] day/night colors and the same lifecycle/ticker contract.
 *
 * Feature parity with the original panel (nothing is dropped):
 *  - per-step marker / state color / duration, including "skipped" and "pending"
 *  - summary (attempt / elapsed / overall) and the full estimate/outcome copy
 *  - per-step hints derived from ConnectionTask details (vehicle Bluetooth / Wi-Fi),
 *    including the danger-colored "Bluetooth not connected" hint
 *  - PIONEER_WIRELESS-specific step titles
 *  - day/night palette injection and adaptive step columns on the HTML side
 *  - accessibility: importantForAccessibility + aria-live in the page
 *
 * Transport events still go to the model, never to this view. Only a local asset is
 * loaded: no network, no JavascriptInterface, no file/content access.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class AppleStyleConnectionProgressPanel(context: Context) : FrameLayout(context) {

    private val history = ConnectionProgressHistory(context)
    private var model: ConnectionProgress? = null
    private var pendingWireless = false
    private var pendingChannel = com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.FRAMEWORK
    private val displayedChannel get() = model?.channel ?: pendingChannel
    private var night = true
    private var pageReady = false
    private var tickerPosted = false
    private var initialized = false
    private val webView: WebView

    private val tick = object : Runnable {
        override fun run() {
            tickerPosted = false
            if (!isShown || !isAttachedToWindow) return
            render()
            // Same condition as the original panel: keep ticking only while connecting/retrying.
            if (model?.snapshot()?.outcome in listOf(ConnectionOutcome.CONNECTING, ConnectionOutcome.RETRYING)) schedule()
        }
    }

    init {
        webView = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // The page is a bundled local asset. Keep JS on for state injection,
            // but do not expose any Java object to the DOM.
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    render()
                }
            }
        }
        addView(webView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        webView.loadUrl("file:///android_asset/carplay-progress.html")
        initialized = true
    }

    /** Select the waiting layout before a controller exists, without starting attempt timing. */
    fun pendingTransport(wireless: Boolean, vehicleWireless: Boolean) {
        pendingWireless = wireless
        pendingChannel = when {
            wireless && vehicleWireless -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.PIONEER_WIRELESS
            wireless -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.WIRELESS
            else -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.FRAMEWORK
        }
        render()
    }

    fun begin(config: CarPlayRuntimeConfig): ConnectionProgress = history.create(config, model).also(::bind)

    fun bind(progress: ConnectionProgress?) {
        model = progress
        render()
        schedule()
    }

    fun retrying() { model?.stop(ConnectionOutcome.RETRYING); render(); schedule() }
    fun failed() { model?.stop(ConnectionOutcome.FAILED); render() }
    fun cancelled() { model?.stop(ConnectionOutcome.CANCELLED); render() }

    fun setNight(value: Boolean) { night = value; render() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); tickerPosted = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && w != oldw) render()
    }

    private fun schedule() {
        if (!tickerPosted && isAttachedToWindow && isShown) { tickerPosted = true; postDelayed(tick, 500) }
    }

    private fun render() {
        if (!pageReady) return
        val state = model?.snapshot()
        val steps = state?.steps ?: ConnectionProgress.stepsFor(pendingWireless, pendingChannel)
        val palette = DiPlayPalette.of(night)
        val json = JSONObject()

        // --- Palette: the original setNight() switches the whole color set ---
        json.put("night", night)
        json.put("palette", JSONObject()
            .put("text", hex(palette.primaryText))
            .put("dim", hex(palette.secondaryText))
            .put("accent", hex(palette.accent))
            .put("success", hex(palette.success))
            .put("danger", hex(palette.danger))
            .put("surface", hex(palette.surface)))

        // Every visible string comes from existing R.string resources (no hardcoded copy).
        json.put("labels", JSONObject()
            .put("title", context.getString(R.string.carplay))
            .put("waiting", context.getString(R.string.connect_progress_waiting))
            .put("complete", context.getString(R.string.carplay_connected))
            .put("failed", context.getString(R.string.connect_progress_failed))
            .put("cancelled", context.getString(R.string.connect_progress_cancelled)))

        val outcome = state?.outcome ?: ConnectionOutcome.CONNECTING
        json.put("outcome", outcome.name)
        json.put("running", outcome == ConnectionOutcome.CONNECTING || outcome == ConnectionOutcome.RETRYING)
        json.put("channel", displayedChannel.name)
        json.put("attempt", state?.attempt ?: 0)
        json.put("elapsed", seconds(state?.elapsedMillis ?: 0L))
        json.put("overall", seconds(state?.overallMillis ?: 0L))
        json.put("summary", state?.let {
            context.getString(
                R.string.connect_progress_summary,
                NumberFormat.getIntegerInstance().format(it.attempt),
                seconds(it.elapsedMillis),
                seconds(it.overallMillis),
            )
        } ?: context.getString(R.string.connect_progress_waiting))

        // --- The original estimate() row, with its full outcome/estimate branching ---
        val expected = state?.expectedMillis
        val estimate = if (state == null) context.getString(R.string.connect_progress_learning) else when (state.outcome) {
            ConnectionOutcome.RETRYING -> context.getString(R.string.connect_progress_retrying)
            ConnectionOutcome.FAILED -> context.getString(R.string.connect_progress_failed)
            ConnectionOutcome.CANCELLED -> context.getString(R.string.connect_progress_cancelled)
            ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_complete, seconds(state.elapsedMillis))
            ConnectionOutcome.CONNECTING -> when {
                state.waitingForUser -> context.getString(R.string.connect_progress_user_wait)
                expected == null -> context.getString(R.string.connect_progress_learning)
                state.elapsedMillis >= expected || (state.remainingMillis ?: 0) == 0L ->
                    context.getString(R.string.connect_progress_overrun)
                else -> context.getString(
                    R.string.connect_progress_estimate,
                    seconds(state.remainingMillis ?: 0),
                    seconds(expected),
                    NumberFormat.getIntegerInstance().format(state.historySamples),
                )
            }
        }
        json.put("estimate", estimate)
        json.put("estimateTone", when (outcome) {
            ConnectionOutcome.FAILED, ConnectionOutcome.CANCELLED -> "danger"
            ConnectionOutcome.COMPLETE -> "success"
            ConnectionOutcome.RETRYING -> "dim"
            else -> "normal"
        })
        json.put("waitingForUser", state?.waitingForUser == true)

        // --- Steps: state, duration, localized title, and per-step task hints ---
        val arr = JSONArray()
        var doneCount = 0
        var hasActive = false
        var currentTitle = ""
        var currentTime = ""
        steps.forEach { step ->
            val current = state != null && state.current == step && state.outcome != ConnectionOutcome.COMPLETE
            val visited = state != null && step in state.visited
            val finished = visited && !current
            val stepState = when {
                current && state?.outcome == ConnectionOutcome.FAILED -> "failed"
                current -> "active"
                finished -> "done"
                else -> "pending"
            }
            val time = when {
                visited -> context.getString(R.string.connect_progress_seconds, seconds(state!!.durations.getValue(step)))
                state?.outcome == ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_skipped)
                else -> context.getString(R.string.connect_progress_pending)
            }
            val title = context.getString(title(step))
            if (stepState == "done") doneCount++
            if (stepState == "active" || stepState == "failed") { hasActive = true; currentTitle = title; currentTime = time }

            // Verbatim parity with the original PIONEER_WIRELESS hint logic.
            val hintRes: Int? = if (displayedChannel == com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.PIONEER_WIRELESS) {
                when (step) {
                    ConnectionStep.DEVICE -> when (state?.tasks?.firstOrNull { it.task == ConnectionTask.WIRELESS_LINK }?.detail) {
                        "vehicle_bluetooth_wait" -> R.string.vehicle_bluetooth_connect_hint
                        "vehicle_bluetooth_not_connected" -> R.string.vehicle_bluetooth_missing_hint
                        else -> null
                    }
                    ConnectionStep.NETWORK -> state?.tasks?.firstOrNull {
                        it.task == ConnectionTask.WIRELESS_NETWORK && it.state == ConnectionTaskState.RUNNING
                    }?.let { task -> when (task.detail) {
                        "vehicle_wifi_enabling" -> R.string.connect_progress_wifi_enabling
                        "vehicle_wifi_wait" -> R.string.connect_progress_wifi_wait
                        else -> null
                    } }
                    else -> null
                }
            } else null

            arr.put(JSONObject()
                .put("title", title)
                .put("state", stepState)
                .put("time", time)
                .put("hint", hintRes?.let(context::getString).orEmpty())
                .put("hintDanger", hintRes == R.string.vehicle_bluetooth_missing_hint))
        }
        json.put("steps", arr)
        json.put("currentTitle", currentTitle)
        json.put("currentTime", currentTime)
        json.put("percent", if (outcome == ConnectionOutcome.COMPLETE) 100
            else ((doneCount + if (hasActive) 0.5f else 0f) / steps.size * 100).roundToInt())
        push(json)
    }

    private fun push(json: JSONObject) {
        val script = "window.applyConnectionState&&window.applyConnectionState(" + json.toString() + ");"
        webView.evaluateJavascript(script, null)
    }

    private fun title(step: ConnectionStep): Int {
        if (displayedChannel == com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.PIONEER_WIRELESS) return when (step) {
            ConnectionStep.NETWORK -> R.string.connect_progress_vehicle_hotspot
            ConnectionStep.DEVICE -> R.string.connect_progress_vehicle_bluetooth
            ConnectionStep.CONTROL -> R.string.connect_progress_vehicle_auth
            ConnectionStep.SESSION -> R.string.connect_progress_vehicle_session
            else -> ordinaryTitle(step)
        }
        return ordinaryTitle(step)
    }

    private fun ordinaryTitle(step: ConnectionStep) = when (step) {
        ConnectionStep.WIRELESS_BOOTSTRAP -> R.string.connect_progress_vehicle_bootstrap
        ConnectionStep.WIRELESS_JOIN -> R.string.connect_progress_vehicle_join
        ConnectionStep.PERMISSIONS -> R.string.connect_progress_permissions
        ConnectionStep.CHANNEL -> R.string.connect_progress_channel
        ConnectionStep.PREPARATION -> R.string.connect_progress_preparation
        ConnectionStep.DEVICE -> R.string.connect_progress_device
        ConnectionStep.CONTROL -> R.string.connect_progress_control
        ConnectionStep.NETWORK -> R.string.connect_progress_network
        ConnectionStep.SESSION -> R.string.connect_progress_session
        ConnectionStep.VIDEO -> R.string.connect_progress_video
    }

    private fun seconds(millis: Long): String = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(millis / 1000.0)

    private fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
}
