package com.shilapi.xcertplay.feedback

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.DiPlayButton
import com.shilapi.xcertplay.DiPlayButtonKind
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R

/** Header pieces shared by the feedback screens, matching the Settings/Diagnostics header. */
internal object FeedbackChrome {
    fun isCompact(context: Context): Boolean =
        context.resources.configuration.screenHeightDp < 500

    fun headerHeight(context: Context): Int = dp(context, if (isCompact(context)) 44 else 52)

    fun titleSize(context: Context): Float = if (isCompact(context)) 20f else 26f

    fun actionTextSize(context: Context): Float = if (isCompact(context)) 15f else 16f

    fun backButton(context: Context, palette: DiPlayPalette, onClick: () -> Unit): DiPlayButton =
        DiPlayButton(context).apply {
            text = context.getString(R.string.back)
            textSize = actionTextSize(context)
            minHeight = headerHeight(context)
            minWidth = dp(context, 88)
            style(DiPlayButtonKind.SECONDARY, palette, overlay = false)
            val iconSize = dp(context, if (isCompact(context)) 20 else 24)
            val icon = ContextCompat.getDrawable(context, R.drawable.ic_dp_back)?.mutate()?.apply {
                setTint(palette.primaryText)
                setBounds(0, 0, iconSize, iconSize)
            }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(context, 8)
            setOnClickListener { onClick() }
        }

    fun title(context: Context, palette: DiPlayPalette, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = titleSize(context)
            setTextColor(palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

    fun action(
        context: Context,
        palette: DiPlayPalette,
        text: String,
        kind: DiPlayButtonKind,
        onClick: () -> Unit,
    ): DiPlayButton = DiPlayButton(context).apply {
        this.text = text
        textSize = actionTextSize(context)
        minHeight = headerHeight(context)
        minWidth = dp(context, 88)
        style(kind, palette, overlay = false)
        setOnClickListener { onClick() }
    }

    fun headerParams(context: Context, weight: Float = 0f, marginStart: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, headerHeight(context)).apply {
            this.weight = weight
            this.marginStart = dp(context, marginStart)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
