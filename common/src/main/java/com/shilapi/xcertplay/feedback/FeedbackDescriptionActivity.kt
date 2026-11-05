package com.shilapi.xcertplay.feedback

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.AppAppearanceOwner
import com.shilapi.xcertplay.DiPlayHeaderChrome
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.resolveAppNightNow

/** A dedicated description editor so the keyboard never covers the main feedback form. */
class FeedbackDescriptionActivity : ComponentActivity(), AppAppearanceOwner {
    private var palette = DiPlayPalette.DARK
    private var input: EditText? = null
    private var counter: TextView? = null

    override val currentAppNight: Boolean get() = resolveAppNightNow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = DiPlayPalette.of(currentAppNight)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildContent())
        DiPlayHeaderChrome.applySystemBars(this, palette)
        window.decorView.postDelayed({
            input?.requestFocus()
            input?.let { editor ->
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 250)
    }

    private fun buildContent(): View {
        val baseLeft = dp(16)
        val baseTop = dp(8)
        val baseRight = dp(16)
        val baseBottom = dp(8)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setPadding(baseLeft, baseTop, baseRight, baseBottom)
        }

        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val compact = DiPlayHeaderChrome.isCompact(this)
        val headerHeight = DiPlayHeaderChrome.headerHeight(this, compact)
        topBar.addView(
            DiPlayHeaderChrome.headerButton(
                this,
                palette,
                compact,
                getString(R.string.back),
                R.drawable.ic_dp_back,
                onClick = { finish() },
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight),
        )
        topBar.addView(
            DiPlayHeaderChrome.title(this, palette, compact, getString(R.string.feedback_description_title)),
            LinearLayout.LayoutParams(0, headerHeight, 1f),
        )
        topBar.addView(
            DiPlayHeaderChrome.actionButton(
                this,
                palette,
                compact,
                getString(R.string.feedback_done),
                primary = true,
                onClick = { finishWithText() },
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, headerHeight),
        )
        root.addView(topBar)

        val initial = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(MAX_DESCRIPTION_CHARS)
        input = EditText(this).apply {
            hint = getString(R.string.feedback_description_hint)
            setHintTextColor(palette.secondaryText)
            setTextColor(palette.primaryText)
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(MAX_DESCRIPTION_CHARS))
            isSingleLine = false
            imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), palette.outline)
            }
            setText(initial)
            setSelection(text.length)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val length = s?.length ?: 0
                    counter?.visibility = if (length >= 1800) View.VISIBLE else View.GONE
                    counter?.text = getString(R.string.feedback_counter, length, MAX_DESCRIPTION_CHARS)
                }
            })
        }
        val editor = input!!
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        counter = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.secondaryText)
            gravity = Gravity.END
            visibility = if (initial.length >= 1800) View.VISIBLE else View.GONE
            text = getString(R.string.feedback_counter, initial.length, MAX_DESCRIPTION_CHARS)
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(counter!!)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                baseLeft + safe.left,
                baseTop + safe.top,
                baseRight + safe.right,
                baseBottom + safe.bottom,
            )
            // Keep the system status bar visible like Diagnostics; reclaim the header row for
            // the input while the keyboard is open on short head units.
            topBar.visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun finishWithText() {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_TEXT, input?.text?.toString().orEmpty().take(MAX_DESCRIPTION_CHARS)),
        )
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val EXTRA_TEXT = "feedback_description_text"

        internal fun intent(context: Context, text: String): Intent =
            Intent(context, FeedbackDescriptionActivity::class.java).putExtra(EXTRA_TEXT, text)
    }
}
