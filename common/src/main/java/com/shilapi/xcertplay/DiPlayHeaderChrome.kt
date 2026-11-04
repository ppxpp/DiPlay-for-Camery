package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.view.doOnLayout
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

internal const val VALUE_SEPARATOR = " · "

/**
 * Header chrome shared by Settings/Diagnostics and the feedback screens.
 * The visual style and padding are the same implementation, not a copy.
 */
internal object DiPlayHeaderChrome {
    fun isCompact(context: Context): Boolean =
        context.resources.configuration.screenHeightDp < 500

    fun headerHeight(context: Context, compact: Boolean = isCompact(context)): Int =
        dp(context, if (compact) 44 else 52)

    fun headerButton(
        context: Context,
        palette: DiPlayPalette,
        compact: Boolean,
        title: String,
        icon: Int,
        onClick: () -> Unit,
        onCreated: (SettingButton) -> Unit = {},
    ): SettingButton {
        val height = headerHeight(context, compact)
        val button = baseButton(context, palette, compact, title, primary = false, onClick, onCreated)
        button.alignVisibleGlyphs = false
        if (compact) button.textSize = 16f
        button.includeFontPadding = false
        button.setTextColor(palette.primaryText)
        button.isSingleLine = true
        button.gravity = Gravity.CENTER
        val iconSize = dp(context, if (compact) 20 else 24)
        val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
            setTint(palette.primaryText)
            setBounds(0, 0, iconSize, iconSize)
        }
        button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(context, 8)
        button.doOnLayout {
            if (button.baseline >= 0 && button.text.isNotEmpty()) {
                val glyphCentre = button.textAlignmentCentreY()
                val drawableCentre = button.paddingTop + (button.height - button.paddingTop - button.paddingBottom) / 2f
                val offset = (glyphCentre - drawableCentre).roundToInt()
                drawable?.setBounds(0, offset, iconSize, iconSize + offset)
                button.contentOffsetY = drawableCentre - glyphCentre
                button.invalidate()
            }
        }
        return button
    }

    fun actionButton(
        context: Context,
        palette: DiPlayPalette,
        compact: Boolean,
        title: String,
        primary: Boolean,
        onClick: () -> Unit,
        onCreated: (SettingButton) -> Unit = {},
    ): SettingButton = baseButton(context, palette, compact, title, primary, onClick, onCreated)

    fun title(context: Context, palette: DiPlayPalette, compact: Boolean, text: String): TextView =
        HeaderTitleView(context).apply {
            this.text = text
            textSize = if (compact) 20f else 26f
            setTextColor(palette.primaryText)
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun baseButton(
        context: Context,
        palette: DiPlayPalette,
        compact: Boolean,
        title: String,
        primary: Boolean,
        onClick: () -> Unit,
        onCreated: (SettingButton) -> Unit,
    ): SettingButton {
        val height = headerHeight(context, compact)
        fun cornerFor(buttonHeight: Int): Float =
            minOf(dp(context, 24).toFloat(), buttonHeight * 20f / 56f)

        val button = SettingButton(context, palette.secondaryText, palette.accent).apply {
            isAllCaps = false
            textSize = 18f
            setTextColor(if (primary) palette.onAccent else palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = RippleDrawable(
                ColorStateList.valueOf(palette.ripple),
                shape(context, palette, primary, cornerFor(height)),
                null,
            )
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            minHeight = dp(context, 56)
            stateListAnimator = null
            compoundDrawablePadding = dp(context, 12)
            foreground = focusRing(context, palette, cornerFor(height))
            text = title
            setOnClickListener { onClick() }
            doOnLayout {
                val actual = cornerFor(this.height)
                background = RippleDrawable(
                    ColorStateList.valueOf(palette.ripple),
                    shape(context, palette, primary, actual),
                    null,
                )
                foreground = focusRing(context, palette, actual)
            }
        }
        onCreated(button)
        return button
    }

    private fun shape(
        context: Context,
        palette: DiPlayPalette,
        primary: Boolean,
        corner: Float,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(if (primary) palette.accent else palette.button)
        cornerRadius = corner
        setStroke(dp(context, 1), if (primary) palette.accent else palette.outline)
    }

    private fun focusRing(
        context: Context,
        palette: DiPlayPalette,
        corner: Float,
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = corner
            setStroke(dp(context, 3), palette.focusRing)
        })
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

internal class HeaderTitleView(context: Context) : TextView(context) {
    private val glyphBounds = Rect()

    override fun onDraw(canvas: Canvas) {
        val value = text.toString()
        paint.getTextBounds(value, 0, value.length, glyphBounds)
        val checkpoint = canvas.save()
        if (layout?.lineCount == 1 && !glyphBounds.isEmpty && baseline >= 0) {
            val textCentre = alignedTextCentreY(this, value, glyphBounds)
            val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
            canvas.translate(0f, contentCentre - textCentre)
        }
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }
}

internal class SettingButton(
    context: Context,
    private val muted: Int,
    private val accent: Int,
) : Button(context) {
    var action = false
    var contentOffsetY = 0f
    var alignVisibleGlyphs = true
    private val glyphBounds = Rect()

    fun textAlignmentCentreY(): Float = alignedTextCentreY(this, text, glyphBounds)

    override fun onDraw(canvas: Canvas) {
        var offset = contentOffsetY
        if (alignVisibleGlyphs) {
            offset = 0f
            if (layout?.lineCount == 1 && baseline >= 0) {
                val textCentre = textAlignmentCentreY()
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                offset = contentCentre - textCentre
            }
            compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                val bounds = drawable.bounds
                val top = -offset.roundToInt()
                drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
            }
        }
        val checkpoint = canvas.save()
        canvas.translate(0f, offset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
        if (text == null || split <= 0) {
            super.setText(text, type)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val styled = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(muted), split, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        super.setText(styled, BufferType.SPANNABLE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            null,
            null,
            context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) },
            null,
        )
    }
}

internal fun alignedTextCentreY(
    view: TextView,
    text: CharSequence,
    bounds: Rect,
    drawingBaseline: Int = view.baseline,
): Float {
    if (usesChineseGlyphAlignment(ConfigurationCompat.getLocales(view.resources.configuration)[0]!!.language, text)) {
        val value = text.toString()
        view.paint.getTextBounds(value, 0, value.length, bounds)
        return drawingBaseline + (bounds.top + bounds.bottom) / 2f
    }
    val metrics = view.paint.fontMetrics
    return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
}

internal fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean =
    language == "zh" && text.isNotBlank() && text.none { it in 'A'..'Z' || it in 'a'..'z' }
