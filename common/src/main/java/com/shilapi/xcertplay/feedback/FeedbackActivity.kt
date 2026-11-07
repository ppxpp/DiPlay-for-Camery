package com.shilapi.xcertplay.feedback

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.AppAppearanceOwner
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayButton
import com.shilapi.xcertplay.DiPlayButtonKind
import com.shilapi.xcertplay.DiPlayHeaderChrome
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.SettingButton
import com.shilapi.xcertplay.appDialogBuilder
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.resolveAppNightNow
import com.shilapi.xcertplay.update.UpdateChannelStore
import java.io.File
import java.util.UUID

class FeedbackActivity : ComponentActivity(), AppAppearanceOwner {
    private var category: FeedbackCategory? = null
    private var failureStage: String? = null
    private var entry = FeedbackEntry.SETTINGS
    private var palette = DiPlayPalette.DARK

    private var categoryButton: DiPlayButton? = null
    private var failureStageView: TextView? = null
    private var descriptionButton: DiPlayButton? = null
    private var descriptionText = ""
    private var sendButton: SettingButton? = null
    private var backButton: SettingButton? = null
    private var networkView: TextView? = null
    private var networkButton: DiPlayButton? = null
    private var formContainer: LinearLayout? = null
    private var statusContainer: LinearLayout? = null
    private var statusView: TextView? = null
    private var actionRow: LinearLayout? = null
    private var retryButton: DiPlayButton? = null
    private var recordsButton: DiPlayButton? = null
    private var doneButton: DiPlayButton? = null
    private var bottomInfo: View? = null

    private var submitting = false
    private var pendingId: String? = null
    private var pollStartedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val id = pendingId ?: return
            if (System.currentTimeMillis() - pollStartedAt > 120_000) {
                showQueued(id)
                return
            }
            val record = FeedbackOutbox.record(this@FeedbackActivity, id)
            if (record == null) {
                if (FeedbackOutbox.sentHistory(this@FeedbackActivity).any { it.id == id }) {
                    showSent(id)
                } else {
                    showQueued(id)
                }
                return
            }
            when (record.state) {
                FeedbackState.UPLOADING -> {
                    statusView?.text = getString(R.string.feedback_uploading)
                    handler.postDelayed(this, 700)
                }
                FeedbackState.PENDING -> {
                    statusView?.text = getString(R.string.feedback_uploading)
                    handler.postDelayed(this, 700)
                }
                FeedbackState.FAILED_RETRYABLE -> showQueued(id, record.lastError)
                FeedbackState.FAILED_PERMANENT -> showPermanent(id, record.lastError)
            }
        }
    }

    private val descriptionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            descriptionText = result.data
                ?.getStringExtra(FeedbackDescriptionActivity.EXTRA_TEXT)
                .orEmpty()
                .trim()
                .take(MAX_DESCRIPTION_CHARS)
            updateDescriptionButton()
        }
    }

    override val currentAppNight: Boolean get() = resolveAppNightNow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = FeedbackCategory.fromWire(intent.getStringExtra(EXTRA_CATEGORY))
        failureStage = intent.getStringExtra(EXTRA_FAILURE_STAGE)
        entry = FeedbackEntry.fromWire(intent.getStringExtra(EXTRA_ENTRY))
        palette = DiPlayPalette.of(currentAppNight)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        setContentView(buildContent())
        DiPlayHeaderChrome.applySystemBars(this, palette)
        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()
        refreshUploadInfo()
        updateSendEnabled()
    }

    override fun onResume() {
        super.onResume()
        refreshUploadInfo()
        if (pendingId != null && !submitting) startPolling()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (submitting && pendingId == null) return
        super.onBackPressed()
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
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val compact = DiPlayHeaderChrome.isCompact(this)
        val headerHeight = DiPlayHeaderChrome.headerHeight(this, compact)
        backButton = DiPlayHeaderChrome.headerButton(
            this,
            palette,
            compact,
            getString(R.string.back),
            R.drawable.ic_dp_back,
            onClick = { finish() },
        )
        topBar.addView(backButton!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight))
        topBar.addView(
            DiPlayHeaderChrome.title(this, palette, compact, getString(R.string.feedback_title)),
            LinearLayout.LayoutParams(0, headerHeight, 1f),
        )
        sendButton = DiPlayHeaderChrome.actionButton(
            this,
            palette,
            compact,
            getString(R.string.feedback_send_short),
            primary = true,
            onClick = { submit() },
        )
        topBar.addView(sendButton!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight))
        top.addView(topBar)

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        formContainer = form
        top.addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        categoryButton = DiPlayButton(this).apply {
            text = categoryText()
            textSize = 16f
            minHeight = dp(52)
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            setOnClickListener { chooseCategory() }
        }
        val categoryView = categoryButton!!
        form.addView(categoryView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        val failureView = TextView(this).apply {
            text = failureStage?.let { getString(R.string.feedback_failure_stage, it) }.orEmpty()
            textSize = 13f
            setTextColor(palette.secondaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (failureStage.isNullOrBlank()) View.GONE else View.VISIBLE
            setPadding(0, dp(6), 0, 0)
        }
        failureStageView = failureView
        form.addView(failureView)

        descriptionButton = DiPlayButton(this).apply {
            text = descriptionButtonText()
            textSize = 15f
            minHeight = dp(52)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            setOnClickListener {
                descriptionLauncher.launch(FeedbackDescriptionActivity.intent(this@FeedbackActivity, descriptionText))
            }
        }
        val descriptionView = descriptionButton!!
        form.addView(descriptionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(120)
            visibility = View.GONE
        }
        statusContainer = status
        top.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(palette.primaryText)
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        val statusText = statusView!!
        status.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow = actions
        status.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        retryButton = actionButton(getString(R.string.feedback_retry)) { retry() }
        recordsButton = actionButton(getString(R.string.feedback_view_records)) {
            startActivity(Intent(this, FeedbackRecordsActivity::class.java))
        }
        doneButton = actionButton(getString(R.string.feedback_done)) { finish() }
        val retry = retryButton!!
        val records = recordsButton!!
        val done = doneButton!!
        actions.addView(retry, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        actions.addView(records, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        actions.addView(done, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })

        val bottom = ScrollView(this).apply { isFillViewport = true }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bottom.addView(info)
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomInfo = bottom
        if (resources.configuration.screenHeightDp < 360) bottom.visibility = View.GONE

        val networkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        networkView = TextView(this).apply {
            textSize = 14f
            setTextColor(palette.secondaryText)
        }
        val networkText = networkView!!
        networkRow.addView(networkText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        networkButton = DiPlayButton(this).apply {
            text = getString(R.string.feedback_open_network_settings)
            textSize = 13f
            minHeight = dp(40)
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            setOnClickListener { openNetworkSettings() }
        }
        val networkAction = networkButton!!
        networkRow.addView(networkAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        info.addView(networkRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        info.addView(infoText(getString(R.string.feedback_privacy), dp(12)))
        info.addView(infoText(getString(R.string.feedback_contents), dp(8)))
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
        return root
    }

    private fun actionButton(text: String, click: () -> Unit) = DiPlayButton(this).apply {
        this.text = text
        textSize = 14f
        minHeight = dp(46)
        style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
        setOnClickListener { click() }
    }

    private fun infoText(text: String, top: Int) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(palette.secondaryText)
        setPadding(0, top, 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun categoryText(): String {
        val label = category?.let { FeedbackText.categoryLabel(this, it) }
            ?: getString(R.string.feedback_category_choose)
        return getString(R.string.feedback_category_value, label)
    }

    private fun descriptionButtonText(): String {
        val preview = descriptionText.replace('\n', ' ').trim()
        return if (preview.isEmpty()) {
            getString(R.string.feedback_description_optional)
        } else {
            getString(R.string.feedback_description_value, preview.take(48))
        }
    }

    private fun updateDescriptionButton() {
        descriptionButton?.text = descriptionButtonText()
    }

    private fun chooseCategory() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val dialog = appDialogBuilder()
            .setTitle(R.string.feedback_category)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(R.string.feedback_cancel, null)
            .create()
        FeedbackCategory.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { item ->
                val button = DiPlayButton(this).apply {
                    text = FeedbackText.categoryLabel(this@FeedbackActivity, item)
                    textSize = 15f
                    minHeight = dp(52)
                    style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
                    setOnClickListener {
                        category = item
                        categoryButton?.text = categoryText()
                        updateSendEnabled()
                        dialog.dismiss()
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(4) })
            }
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(4) })
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(4)
            })
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun refreshUploadInfo() {
        networkView?.text = getString(R.string.feedback_network_requirement)
        networkView?.setTextColor(palette.secondaryText)
        sendButton?.text = getString(R.string.feedback_send_short)
        updateSendEnabled()
    }

    private fun updateSendEnabled() {
        sendButton?.apply {
            isEnabled = !submitting
            alpha = if (category != null) 1f else 0.55f
        }
    }

    private fun submit() {
        if (submitting) return
        val selected = category
        if (selected == null) {
            toast(getString(R.string.feedback_category_required))
            return
        }
        submitting = true
        val id = UUID.randomUUID().toString().replace("-", "")
        val meta = buildMeta(id, selected)
        showBusy()
        Thread({
            val result = runCatching {
                val directory = File(cacheDir, "feedback-build").apply { mkdirs() }
                val output = File.createTempFile("diplay-feedback-", ".zip", directory)
                try {
                    FeedbackBundle.build(this, meta, output)
                    FeedbackOutbox.enqueue(this, meta, output)
                    FeedbackScheduler.schedule(this, immediate = true)
                } catch (error: Throwable) {
                    output.delete()
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isFailure) {
                    submitting = false
                    pendingId = null
                    val failure = result.exceptionOrNull()
                    showBuildFailure(
                        if (failure is FeedbackQueueFullException) getString(R.string.feedback_queue_full) else null,
                    )
                    return@runOnUiThread
                }
                pendingId = id
                submitting = false
                statusView?.text = getString(R.string.feedback_uploading)
                showActions(canRetry = true)
                startPolling()
            }
        }, "diplay-feedback-build").start()
    }

    private fun buildMeta(id: String, selected: FeedbackCategory): FeedbackMeta {
        val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        return FeedbackMeta(
            id = id,
            category = selected,
            description = descriptionText.take(MAX_DESCRIPTION_CHARS),
            entry = entry,
            createdAt = System.currentTimeMillis(),
            failureStage = failureStage?.take(200),
            appVersionName = (packageInfo?.versionName ?: "unknown").take(64),
            appVersionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0,
            updateChannel = UpdateChannelStore.load(this).apiName.take(32),
            deviceManufacturer = (android.os.Build.MANUFACTURER ?: "unknown").take(128),
            deviceModel = (android.os.Build.MODEL ?: "unknown").take(128),
            deviceBoard = (android.os.Build.BOARD ?: "unknown").take(128),
            androidRelease = (android.os.Build.VERSION.RELEASE ?: "unknown").take(64),
            androidApi = android.os.Build.VERSION.SDK_INT,
            sessionMode = if (AirPlayPersistence.loadWirelessEnabled(this)) "wireless" else "usb",
            sessionState = when {
                CarPlayBackgroundSession.active -> "active"
                CarPlayBackgroundSession.hasSession() -> "connecting"
                else -> "stopped"
            },
        )
    }

    private fun showBusy() {
        formContainer?.visibility = View.GONE
        statusContainer?.visibility = View.VISIBLE
        statusView?.setTextColor(palette.primaryText)
        statusView?.text = getString(R.string.feedback_preparing)
        actionRow?.visibility = View.GONE
        sendButton?.visibility = View.GONE
        backButton?.isEnabled = false
        bottomInfo?.visibility = View.GONE
    }

    private fun showBuildFailure(message: String? = null) {
        statusView?.setTextColor(palette.warning)
        statusView?.text = message ?: getString(R.string.feedback_build_failed)
        retryButton?.text = getString(R.string.feedback_retry)
        retryButton?.visibility = View.VISIBLE
        recordsButton?.visibility = View.GONE
        doneButton?.visibility = View.VISIBLE
        actionRow?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        statusContainer?.visibility = View.VISIBLE
        formContainer?.visibility = View.GONE
    }

    private fun showSent(id: String) {
        handler.removeCallbacks(poller)
        statusView?.setTextColor(palette.success)
        statusView?.text = getString(R.string.feedback_sent_with_code, FeedbackText.shortId(id))
        retryButton?.visibility = View.GONE
        recordsButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.VISIBLE
        actionRow?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        sendButton?.visibility = View.GONE
        bottomInfo?.visibility = View.GONE
    }

    private fun showQueued(id: String, error: String? = null) {
        handler.removeCallbacks(poller)
        statusView?.setTextColor(palette.warning)
        statusView?.text = if (error == null) getString(R.string.feedback_queued) else
            getString(R.string.feedback_state_retryable) + "\n" + getString(R.string.feedback_reason, error)
        retryButton?.text = getString(R.string.feedback_retry)
        retryButton?.visibility = View.VISIBLE
        recordsButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.VISIBLE
        actionRow?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        sendButton?.visibility = View.GONE
        bottomInfo?.visibility = View.GONE
        pendingId = id
    }

    private fun showPermanent(id: String, reason: String?) {
        handler.removeCallbacks(poller)
        statusView?.setTextColor(palette.warning)
        statusView?.text = getString(R.string.feedback_failed_permanent, reason ?: "unknown")
        retryButton?.text = getString(R.string.feedback_retry)
        retryButton?.visibility = View.VISIBLE
        recordsButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.VISIBLE
        actionRow?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        sendButton?.visibility = View.GONE
        bottomInfo?.visibility = View.GONE
        pendingId = id
    }

    private fun showActions(canRetry: Boolean) {
        statusContainer?.visibility = View.VISIBLE
        formContainer?.visibility = View.GONE
        retryButton?.visibility = if (canRetry) View.VISIBLE else View.GONE
        recordsButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.VISIBLE
        actionRow?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        sendButton?.visibility = View.GONE
        bottomInfo?.visibility = View.GONE
    }

    private fun startPolling() {
        pollStartedAt = System.currentTimeMillis()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    private fun retry() {
        val id = pendingId
        if (id == null) {
            restoreForm()
            submit()
            return
        }
        statusView?.setTextColor(palette.primaryText)
        statusView?.text = getString(R.string.feedback_uploading)
        FeedbackScheduler.schedule(this, immediate = true)
        startPolling()
    }

    private fun restoreForm() {
        formContainer?.visibility = View.VISIBLE
        statusContainer?.visibility = View.GONE
        sendButton?.visibility = View.VISIBLE
        backButton?.isEnabled = true
        bottomInfo?.visibility = if (resources.configuration.screenHeightDp >= 360) View.VISIBLE else View.GONE
        categoryButton?.isEnabled = true
        updateSendEnabled()
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
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_CATEGORY = "feedback_category"
        private const val EXTRA_FAILURE_STAGE = "feedback_failure_stage"
        private const val EXTRA_ENTRY = "feedback_entry"

        internal fun intent(
            context: Context,
            category: FeedbackCategory? = null,
            failureStage: String? = null,
            entry: FeedbackEntry = if (failureStage != null) FeedbackEntry.CONNECTION_FAILURE else FeedbackEntry.SETTINGS,
        ): Intent = Intent(context, FeedbackActivity::class.java).apply {
            category?.let { putExtra(EXTRA_CATEGORY, it.wireName) }
            failureStage?.let { putExtra(EXTRA_FAILURE_STAGE, it) }
            putExtra(EXTRA_ENTRY, entry.wireName)
        }
    }
}
