package com.shilapi.xcertplay.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.shilapi.xcertplay.DiPlayButton
import com.shilapi.xcertplay.DiPlayButtonKind
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.pioneer.CamryAdaptation
import com.shilapi.xcertplay.pioneer.PioneerApplicationControl
import com.shilapi.xcertplay.pioneer.PioneerApplicationControl.State
import com.shilapi.xcertplay.pioneer.PioneerManagedApplication
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A visible-page controller: queries only on attach, resume/recreation, package events or explicit refresh. */
internal object CamryApplicationSettingsSection {
    private const val BLOCK_GAP_DP = 12
    private const val STATE_GAP_DP = 4

    fun create(context: Context, palette: DiPlayPalette, indexText: (String) -> Unit): View =
        Controller(context, SettingsTheme.card(palette), palette, indexText).root

    private class Controller(private val context: Context, private val theme: SettingsTheme, private val palette: DiPlayPalette,
                             indexText: (String) -> Unit) : View.OnAttachStateChangeListener, DefaultLifecycleObserver {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val app = context.applicationContext
        private val main = Handler(Looper.getMainLooper())
        private val states = mutableMapOf<PioneerManagedApplication, State>()
        private val rows = mutableMapOf<PioneerManagedApplication, SettingsWidgets.SwitchRowResult>()
        private val statuses = mutableMapOf<PioneerManagedApplication, TextView>()
        private var executor: ExecutorService? = null
        @Volatile private var active = false
        @Volatile private var generation = 0L
        private var registered = false
        private var owner: LifecycleOwner? = null
        private val preferences = app.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE)
        private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "camry_adaptation") {
                stopWork()
                // Preference callbacks can run inside save(), before the applied gate is updated.
                main.post { startWork() }
            }
        }
        private var working = false
        private var refreshPending = false
        private var rendering = false
        private var changing: PioneerManagedApplication? = null
        private var disabling = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (PioneerManagedApplication.values().any { it.packageName == intent.data?.schemeSpecificPart }) refresh()
            }
        }
        private val refreshButton = DiPlayButton(context).apply {
            setText(R.string.settings_camry_apps_refresh)
            style(DiPlayButtonKind.SECONDARY, palette, false)
            setOnClickListener { refresh() }
        }

        init {
            indexText(context.getString(R.string.settings_camry_apps_description))
            root.addView(TextView(context).apply {
                setText(R.string.settings_camry_apps_description); textSize = 14f; setTextColor(theme.textSecondary)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(context, BLOCK_GAP_DP) })
            for (target in PioneerManagedApplication.values()) {
                val title = context.getString(when (target) {
                    PioneerManagedApplication.AMAP -> R.string.settings_camry_apps_amap
                    PioneerManagedApplication.TENCENT_MAP -> R.string.settings_camry_apps_tencent_map
                    PioneerManagedApplication.TENCENT_SPEECH -> R.string.settings_camry_apps_tencent_speech
                })
                val description = context.getString(when (target) {
                    PioneerManagedApplication.AMAP -> R.string.settings_camry_apps_amap_description
                    PioneerManagedApplication.TENCENT_MAP -> R.string.settings_camry_apps_tencent_map_description
                    PioneerManagedApplication.TENCENT_SPEECH -> R.string.settings_camry_apps_tencent_speech_description
                })
                indexText(title); indexText(description)
                rows[target] = SettingsWidgets.createSwitchRow(context, title, description, false, theme,
                    enabled = false) { disabled -> if (!rendering) change(target, disabled) }
                root.addView(rows.getValue(target).rowView)
                statuses[target] = TextView(context).apply {
                    textSize = 14f; setTextColor(theme.textSecondary)
                    setPadding(0, 0, 0, theme.dp(context, STATE_GAP_DP))
                }
                root.addView(statuses.getValue(target))
            }
            root.addView(refreshButton, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = theme.dp(context, BLOCK_GAP_DP)
            })
            render()
            root.addOnAttachStateChangeListener(this)
        }

        private fun supported() = PioneerUsbPolicy.enabled(app) && CamryAdaptation.enabled(app)

        private fun render() {
            rendering = true
            try {
                val supported = supported()
                for (target in PioneerManagedApplication.values()) {
                    val state = states[target] ?: State.UNKNOWN
                    val row = rows.getValue(target)
                    row.switch.isChecked = if (changing == target) disabling else state == State.DISABLED
                    row.switch.isEnabled = active && supported && !working && state in setOf(State.ENABLED, State.DISABLED)
                    val status = when {
                        !supported -> R.string.settings_camry_apps_adaptation_required
                        changing == target -> if (disabling) R.string.settings_camry_apps_disabling else R.string.settings_camry_apps_enabling
                        working -> R.string.settings_camry_apps_loading
                        state == State.NOT_INSTALLED -> R.string.settings_camry_apps_not_installed
                        state == State.DISABLED -> R.string.settings_camry_apps_disabled
                        state == State.ENABLED -> R.string.settings_camry_apps_enabled
                        else -> R.string.settings_camry_apps_unknown
                    }
                    statuses.getValue(target).setText(status)
                    row.rowView.isEnabled = row.switch.isEnabled
                }
                refreshButton.isEnabled = active && supported && !working
            } finally { rendering = false }
        }

        private fun refresh() {
            if (!active) return
            if (working) { refreshPending = true; return }
            if (!supported()) { render(); return }
            working = true; render()
            val version = generation
            executor?.execute {
                val result = runCatching { PioneerApplicationControl.read(app) { active && generation == version } }
                main.post {
                    if (!active || generation != version) return@post
                    states.clear(); result.getOrNull()?.let(states::putAll)
                    finish()
                }
            }
        }

        private fun change(target: PioneerManagedApplication, disabled: Boolean) {
            if (!active || !supported() || working || states[target] !in setOf(State.ENABLED, State.DISABLED)) { render(); return }
            working = true; changing = target; disabling = disabled; render()
            val version = generation
            executor?.execute {
                val current = { active && generation == version }
                val result = runCatching { PioneerApplicationControl.setDisabled(app, target, disabled, current) }
                // A lost reply never replays the mutation. Reconcile using a read-only query instead.
                val reconciled = if (result.isFailure) runCatching { PioneerApplicationControl.read(app, current) }.getOrNull() else null
                main.post {
                    if (!active || generation != version) return@post
                    if (result.isSuccess) states[target] = result.getOrThrow()
                    else {
                        states.clear(); reconciled?.let(states::putAll)
                        Toast.makeText(context, R.string.settings_camry_apps_failed, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }
        }

        private fun finish() {
            working = false; changing = null; render()
            if (refreshPending) { refreshPending = false; refresh() }
        }

        private fun startWork() {
            if (active || !root.isAttachedToWindow ||
                owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false) return
            active = true; generation++
            executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pioneer-app-settings").apply { isDaemon = true } }
            refresh()
        }

        private fun stopWork() {
            active = false; generation++
            executor?.shutdownNow(); executor = null
            main.removeCallbacksAndMessages(null)
            states.clear(); working = false; changing = null; refreshPending = false
            render()
        }

        override fun onResume(owner: LifecycleOwner) = startWork()
        override fun onPause(owner: LifecycleOwner) = stopWork()

        override fun onViewAttachedToWindow(view: View) {
            if (registered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
            owner = root.findViewTreeLifecycleOwner() ?: context as? LifecycleOwner
            owner?.lifecycle?.addObserver(this)
            startWork()
        }

        override fun onViewDetachedFromWindow(view: View) {
            owner?.lifecycle?.removeObserver(this); owner = null
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            stopWork()
            if (registered) { app.unregisterReceiver(receiver); registered = false }
        }
    }
}
