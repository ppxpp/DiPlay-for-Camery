package com.shilapi.xcertplay.feedback

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.AppAppearanceOwner
import com.shilapi.xcertplay.DiPlayButton
import com.shilapi.xcertplay.DiPlayButtonKind
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.appDialogBuilder
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.resolveAppNightNow

class FeedbackRecordsActivity : ComponentActivity(), AppAppearanceOwner {
    private var palette = DiPlayPalette.DARK
    private var tab = Tab.PENDING
    private var tabPending: DiPlayButton? = null
    private var tabSent: DiPlayButton? = null
    private var content: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private enum class Tab { PENDING, SENT }

    override val currentAppNight: Boolean get() = resolveAppNightNow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = DiPlayPalette.of(currentAppNight)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        setContentView(buildContent())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContent(): View {
        val baseLeft = dp(16)
        val baseTop = dp(12)
        val baseRight = dp(16)
        val baseBottom = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
        }
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(FeedbackChrome.backButton(this, palette) { finish() }, FeedbackChrome.headerParams(this))
        topBar.addView(
            FeedbackChrome.title(this, palette, getString(R.string.feedback_records_title)),
            FeedbackChrome.headerParams(this, weight = 1f, marginStart = 12),
        )
        root.addView(topBar)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        tabPending = tabButton(getString(R.string.feedback_tab_pending)) { setTab(Tab.PENDING) }
        tabSent = tabButton(getString(R.string.feedback_tab_sent)) { setTab(Tab.SENT) }
        val pendingTab = tabPending!!
        val sentTab = tabSent!!
        tabs.addView(pendingTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        tabs.addView(sentTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                baseLeft + safe.left,
                baseTop + safe.top,
                baseRight + safe.right,
                baseBottom + safe.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
        updateTabStyles()
        return root
    }

    private fun tabButton(text: String, click: () -> Unit) = DiPlayButton(this).apply {
        this.text = text
        textSize = 15f
        minHeight = dp(46)
        style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
        setOnClickListener { click() }
    }

    private fun setTab(next: Tab) {
        if (tab == next) return
        tab = next
        updateTabStyles()
        render()
    }

    private fun updateTabStyles() {
        tabPending?.style(if (tab == Tab.PENDING) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette, overlay = false)
        tabSent?.style(if (tab == Tab.SENT) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette, overlay = false)
    }

    private fun render() {
        val container = content ?: return
        container.removeAllViews()
        if (tab == Tab.PENDING) renderPending(container) else renderSent(container)
        updateTabStyles()
    }

    private fun renderPending(container: LinearLayout) {
        val online = FeedbackConnectivity.isOnline(this)
        val pending = FeedbackOutbox.pending(this)
        val permanent = FeedbackOutbox.permanentFailures(this)
        val networkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        networkRow.addView(TextView(this).apply {
            text = getString(if (online) R.string.feedback_network_online else R.string.feedback_network_offline)
            textSize = 14f
            setTextColor(if (online) palette.success else palette.warning)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        networkRow.addView(DiPlayButton(this).apply {
            text = getString(R.string.feedback_check_network)
            textSize = 13f
            minHeight = dp(40)
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            setOnClickListener { if (!FeedbackConnectivity.isOnline(this@FeedbackRecordsActivity)) showNetworkDialog() else render() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        container.addView(networkRow)

        if (pending.isEmpty() && permanent.isEmpty()) {
            container.addView(emptyState(R.string.feedback_empty_pending))
            container.addView(DiPlayButton(this).apply {
                text = getString(R.string.feedback_send_action)
                textSize = 16f
                minHeight = dp(54)
                style(DiPlayButtonKind.PRIMARY, palette, overlay = false)
                setOnClickListener { startActivity(FeedbackActivity.intent(this@FeedbackRecordsActivity)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(16) })
            return
        }

        if (online && (pending.isNotEmpty() || permanent.isNotEmpty())) {
            container.addView(DiPlayButton(this).apply {
                text = getString(R.string.feedback_retry_all)
                textSize = 15f
                minHeight = dp(46)
                style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
                setOnClickListener {
                    FeedbackScheduler.schedule(this@FeedbackRecordsActivity, immediate = true)
                    toast(getString(R.string.feedback_retrying))
                    handler.postDelayed({ render() }, 900)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
        }

        (permanent + pending).sortedByDescending { it.meta.createdAt }.forEach { record ->
            container.addView(recordCard(record))
        }
    }

    private fun renderSent(container: LinearLayout) {
        val sent = FeedbackOutbox.sentHistory(this)
        if (sent.isEmpty()) {
            container.addView(emptyState(R.string.feedback_empty_sent))
            return
        }
        container.addView(DiPlayButton(this).apply {
            text = getString(R.string.feedback_clear_sent)
            textSize = 14f
            minHeight = dp(44)
            style(DiPlayButtonKind.TONAL_DANGER, palette, overlay = false)
            setOnClickListener { confirmClearSent() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        sent.forEach { item -> container.addView(sentCard(item)) }
    }

    private fun recordCard(record: FeedbackRecord): View = card().apply {
        val header = LinearLayout(this@FeedbackRecordsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this@FeedbackRecordsActivity).apply {
            text = FeedbackText.categoryLabel(this@FeedbackRecordsActivity, record.meta.category)
            textSize = 17f
            setTextColor(palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this@FeedbackRecordsActivity).apply {
            text = FeedbackText.stateLabel(this@FeedbackRecordsActivity, record.state)
            textSize = 13f
            setTextColor(if (record.state == FeedbackState.FAILED_PERMANENT) palette.warning else palette.secondaryText)
        })
        addView(header)
        addView(TextView(this@FeedbackRecordsActivity).apply {
            text = "${FeedbackText.time(record.meta.createdAt)} · ${getString(R.string.feedback_code, FeedbackText.shortId(record.meta.id))}"
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(4), 0, 0)
        })
        addView(TextView(this@FeedbackRecordsActivity).apply {
            text = record.meta.description.ifBlank { getString(R.string.feedback_no_description) }
            textSize = 14f
            setTextColor(palette.secondaryText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })
        record.lastError?.let { error ->
            addView(TextView(this@FeedbackRecordsActivity).apply {
                text = getString(R.string.feedback_reason, error)
                textSize = 12f
                setTextColor(palette.warning)
                setPadding(0, dp(4), 0, 0)
            })
        }
        val actions = LinearLayout(this@FeedbackRecordsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        actions.addView(DiPlayButton(this@FeedbackRecordsActivity).apply {
            text = getString(R.string.feedback_retry)
            textSize = 14f
            minHeight = dp(42)
            isEnabled = record.state != FeedbackState.UPLOADING
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            setOnClickListener { retry(record) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
        actions.addView(DiPlayButton(this@FeedbackRecordsActivity).apply {
            text = getString(R.string.feedback_delete)
            textSize = 14f
            minHeight = dp(42)
            style(DiPlayButtonKind.TONAL_DANGER, palette, overlay = false)
            setOnClickListener { confirmDelete(record) }
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(6) })
        addView(actions)
    }

    private fun sentCard(item: SentFeedback): View = card().apply {
        val header = LinearLayout(this@FeedbackRecordsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this@FeedbackRecordsActivity).apply {
            text = FeedbackText.categoryLabel(this@FeedbackRecordsActivity, item.category)
            textSize = 17f
            setTextColor(palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this@FeedbackRecordsActivity).apply {
            text = getString(R.string.feedback_sent_label)
            textSize = 13f
            setTextColor(palette.success)
        })
        addView(header)
        addView(TextView(this@FeedbackRecordsActivity).apply {
            text = "${FeedbackText.time(item.createdAt)} · ${getString(R.string.feedback_code, FeedbackText.shortId(item.id))}"
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(4), 0, 0)
        })
        addView(TextView(this@FeedbackRecordsActivity).apply {
            text = getString(R.string.feedback_sent_note)
            textSize = 12f
            setTextColor(palette.secondaryText)
            setPadding(0, dp(6), 0, 0)
        })
        addView(DiPlayButton(this@FeedbackRecordsActivity).apply {
            text = getString(R.string.feedback_delete)
            textSize = 13f
            minHeight = dp(40)
            style(DiPlayButtonKind.TONAL_DANGER, palette, overlay = false)
            setOnClickListener {
                FeedbackOutbox.deleteSent(this@FeedbackRecordsActivity, item.id)
                render()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
    }

    private fun retry(record: FeedbackRecord) {
        if (!FeedbackConnectivity.isOnline(this)) {
            showNetworkDialog()
            return
        }
        toast(getString(R.string.feedback_retrying))
        FeedbackScheduler.schedule(this, immediate = true)
        handler.postDelayed({ render() }, 900)
    }

    private fun confirmDelete(record: FeedbackRecord) {
        appDialogBuilder()
            .setTitle(R.string.feedback_delete_confirm_title)
            .setMessage(R.string.feedback_delete_confirm_message)
            .setPositiveButton(R.string.feedback_delete) { _, _ ->
                FeedbackOutbox.delete(this, record.meta.id)
                render()
            }
            .setNegativeButton(R.string.feedback_cancel, null)
            .show()
    }

    private fun confirmClearSent() {
        appDialogBuilder()
            .setTitle(R.string.feedback_clear_sent)
            .setMessage(R.string.feedback_clear_sent_confirm)
            .setPositiveButton(R.string.feedback_delete) { _, _ ->
                FeedbackOutbox.clearSent(this)
                render()
            }
            .setNegativeButton(R.string.feedback_cancel, null)
            .show()
    }

    private fun emptyState(textRes: Int): View = TextView(this).apply {
        text = getString(textRes)
        textSize = 16f
        setTextColor(palette.secondaryText)
        gravity = Gravity.CENTER
        setPadding(0, dp(40), 0, dp(16))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), palette.outline)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        }
    }

    private fun showNetworkDialog() {
        appDialogBuilder()
            .setTitle(R.string.feedback_network_dialog_title)
            .setMessage(R.string.feedback_network_dialog_message)
            .setPositiveButton(R.string.feedback_open_network_settings) { _, _ -> openNetworkSettings() }
            .setNegativeButton(R.string.feedback_ok, null)
            .show()
    }

    private fun openNetworkSettings() {
        val intents = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        toast(getString(R.string.feedback_open_network_failed))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, FeedbackRecordsActivity::class.java)
    }
}
