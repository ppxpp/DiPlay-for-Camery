package com.shilapi.xcertplay.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.DiPlayButton
import com.shilapi.xcertplay.DiPlayButtonKind
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.appDialogBuilder
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.CamryHudAdvance
import com.shilapi.xcertplay.hud.CamryHudConfig
import com.shilapi.xcertplay.hud.CamryHudSettings
import com.shilapi.xcertplay.hud.CamryHudStrategy

/** Both settings surfaces edit the same preferences immediately, without reconnecting CarPlay. */
internal object CamryHudSettingsSection {
    private const val BLOCK_GAP_DP = 16
    private const val CONTROL_HEIGHT_DP = 56

    fun create(context: Context, palette: DiPlayPalette, overlay: Boolean = false,
        indexText: (String) -> Unit = {}): LinearLayout {
        val theme = if (overlay) SettingsTheme.overlay(palette) else SettingsTheme.card(palette)
        fun text(id: Int) = context.getString(id)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun note(parent: LinearLayout, id: Int, vararg args: Any) {
            parent.addView(TextView(context).apply {
                text = if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
                textSize = 14f; setTextColor(theme.textSecondary)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(context, BLOCK_GAP_DP) })
        }
        fun button(parent: LinearLayout, title: String, click: () -> Unit) {
            indexText(title.substringBefore(" · "))
            parent.addView(DiPlayButton(context).apply {
                text = title; textSize = 16f
                minHeight = theme.dp(context, CONTROL_HEIGHT_DP)
                style(DiPlayButtonKind.SECONDARY, palette, overlay)
                setOnClickListener { click() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(context, BLOCK_GAP_DP) })
        }
        lateinit var render: () -> Unit
        fun choice(title: Int, options: List<String>, current: Int, save: (Int) -> Unit) {
            button(details, "${text(title)} · ${options[current]}") {
                var selected = current
                context.appDialogBuilder().setTitle(title)
                    .setSingleChoiceItems(options.toTypedArray(), current) { _, which -> selected = which }
                    .setPositiveButton(R.string.save) { _, _ -> save(selected); render() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        }
        fun distance(title: Int, value: Int, update: (CamryHudConfig, Int) -> CamryHudConfig) {
            choice(title, CamryHudSettings.distances.map { context.getString(R.string.settings_camry_hud_meters, it) },
                CamryHudSettings.distances.indexOf(value)) { index ->
                CamryHudSettings.save(context, update(CamryHudSettings.load(context), CamryHudSettings.distances[index]))
            }
        }
        render = {
            details.removeAllViews()
            val config = CamryHudSettings.load(context)
            details.visibility = if (config.enabled) View.VISIBLE else View.GONE
            // Expectation setting first: it applies to every strategy, so it sits above the parameters.
            details.addView(TextView(context).apply {
                setText(R.string.settings_camry_hud_experience_note); textSize = 14f
                setTextColor(palette.warning)
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(context, BLOCK_GAP_DP) })
            choice(R.string.settings_camry_hud_strategy, listOf(text(R.string.settings_camry_hud_fixed),
                text(R.string.settings_camry_hud_by_turn), text(R.string.settings_camry_hud_adaptive)), config.strategy.ordinal) {
                CamryHudSettings.save(context, CamryHudSettings.load(context).copy(strategy = CamryHudStrategy.entries[it]))
            }
            when (config.strategy) {
                CamryHudStrategy.FIXED_DISTANCE -> {
                    note(details, R.string.settings_camry_hud_fixed_hint)
                    distance(R.string.settings_camry_hud_distance, config.fixedMeters) { c, n -> c.copy(fixedMeters = n) }
                }
                CamryHudStrategy.MANEUVER_DISTANCE -> {
                    note(details, R.string.settings_camry_hud_by_turn_hint)
                    distance(R.string.settings_camry_hud_turn_distance, config.turnMeters) { c, n -> c.copy(turnMeters = n) }
                    distance(R.string.settings_camry_hud_complex_distance, config.complexMeters) { c, n -> c.copy(complexMeters = n) }
                    distance(R.string.settings_camry_hud_straight_distance, config.straightMeters) { c, n -> c.copy(straightMeters = n) }
                }
                CamryHudStrategy.ADAPTIVE -> {
                    note(details, R.string.settings_camry_hud_adaptive_hint)
                    choice(R.string.settings_camry_hud_advance, listOf(text(R.string.settings_camry_hud_later),
                        text(R.string.settings_camry_hud_standard), text(R.string.settings_camry_hud_earlier)), config.advance.ordinal) {
                        CamryHudSettings.save(context, CamryHudSettings.load(context).copy(advance = CamryHudAdvance.entries[it]))
                    }
                    val timing = config.advance
                    note(details, R.string.settings_camry_hud_adaptive_values,
                        timing.turnSeconds, timing.turnFloorMeters,
                        timing.complexSeconds, timing.complexFloorMeters,
                        timing.straightSeconds, timing.straightFloorMeters,
                        CamryHudAdvance.TURN_MAX_METERS, CamryHudAdvance.COMPLEX_MAX_METERS,
                        CamryHudAdvance.STRAIGHT_MAX_METERS)
                }
            }
            note(details, R.string.settings_camry_hud_apply_hint)
        }
        indexText(text(R.string.settings_camry_hud_title))
        root.addView(SettingsWidgets.createSwitchRow(context, text(R.string.settings_camry_hud_title),
            text(R.string.settings_camry_hud_description), CamryHudSettings.load(context).enabled, theme) { enabled ->
            CamryHudSettings.save(context, CamryHudSettings.load(context).copy(enabled = enabled))
            render()
        }.rowView)
        root.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(context, BLOCK_GAP_DP) })
        render()
        return root
    }
}
