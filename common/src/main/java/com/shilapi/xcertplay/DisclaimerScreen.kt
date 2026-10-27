package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.shilapi.xcertplay.host.R

/** Colors and density the gate needs, passed from the host activity's runtime palette. */
class DisclaimerStyle(
    val background: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val button: Int,
    val border: Int,
    val warning: Int,
    val density: Float,
)

private fun dpOf(style: DisclaimerStyle, value: Int) = (value * style.density).toInt()

private fun bodyLabel(context: Context, style: DisclaimerStyle, value: CharSequence, size: Float, color: Int, bold: Boolean) =
    TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(6f * style.density, 1f)
    }

private fun actionLabel(context: Context, style: DisclaimerStyle, value: CharSequence, primary: Boolean) =
    TextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(if (primary) style.onAccent else style.text)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // The agree label carries a second swipe-hint line, and some locales wrap it further. Padded
        // vertically so a wrapped label grows the button instead of being clipped by its height.
        setPadding(0, dpOf(style, 10), 0, dpOf(style, 10))
        background = GradientDrawable().apply {
            setColor(if (primary) style.accent else style.button)
            cornerRadius = 26f * style.density
        }
    }

private class DisclaimerPage(
    val title: CharSequence,
    val lines: List<CharSequence>,
    val warning: CharSequence? = null,
)

/** Full-screen, swipeable disclaimer shown before the app becomes usable. */
object DisclaimerScreen {
    fun build(
        activity: Activity,
        style: DisclaimerStyle,
        onAgree: (doNotShowAgain: Boolean) -> Unit,
        onDisagree: () -> Unit,
    ): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(style.background)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(style, 28), dpOf(style, 28), dpOf(style, 28), dpOf(style, 4))
        }
        header.addView(bodyLabel(activity, style, activity.getText(R.string.disclaimer_title), 26f, style.text, true))
        header.addView(
            bodyLabel(activity, style, activity.getText(R.string.disclaimer_subtitle), 15f, style.muted, false)
                .apply { setPadding(0, dpOf(style, 8), 0, dpOf(style, 16)) },
        )
        root.addView(header)

        val pages = listOf(
            DisclaimerPage(
                activity.getText(R.string.disclaimer_page1_title),
                listOf(
                    activity.getText(R.string.disclaimer_page1_l1),
                    activity.getText(R.string.disclaimer_page1_l2),
                ),
            ),
            DisclaimerPage(
                activity.getText(R.string.disclaimer_page2_title),
                listOf(
                    activity.getText(R.string.disclaimer_page2_l1),
                    activity.getText(R.string.disclaimer_page2_l2),
                    activity.getText(R.string.disclaimer_page2_l3),
                ),
            ),
            DisclaimerPage(
                activity.getText(R.string.disclaimer_page3_title),
                listOf(
                    activity.getText(R.string.disclaimer_page3_l1),
                    activity.getText(R.string.disclaimer_page3_l2),
                    activity.getText(R.string.disclaimer_page3_l3),
                ),
                activity.getText(R.string.disclaimer_page3_warn),
            ),
            // Its own page rather than a fourth line on page 3: the consent sentence has to be visible
            // without scrolling, and the agree button only unlocks on the last page.
            DisclaimerPage(
                activity.getText(R.string.disclaimer_page4_title),
                listOf(
                    activity.getText(R.string.disclaimer_page4_l1),
                    activity.getText(R.string.disclaimer_page4_l2),
                ),
            ),
        )

        val pager = ViewPager2(activity).apply {
            adapter = PageAdapter(pages, style)
            offscreenPageLimit = pages.size
        }
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val dots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dotViews = pages.indices.map { View(activity) }
        val dotSize = dpOf(style, 8)
        val dotGap = dpOf(style, 8)
        dotViews.forEachIndexed { index, dot ->
            dot.setBackgroundColor(if (index == 0) style.accent else style.border)
            dots.addView(
                dot,
                LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginStart = if (index == 0) 0 else dotGap
                    marginEnd = if (index == pages.lastIndex) 0 else dotGap
                },
            )
        }
        root.addView(
            dots,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpOf(style, 4); bottomMargin = dpOf(style, 10) },
        )

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(style, 28), dpOf(style, 8), dpOf(style, 28), dpOf(style, 24))
        }
        val disagree = actionLabel(activity, style, activity.getText(R.string.disclaimer_disagree), false)
        val agree = actionLabel(activity, style, activity.getText(R.string.disclaimer_agree), true)
        // Agree stays disabled until the last page is reached, so the user scrolls through it all.
        agree.isEnabled = false
        agree.alpha = 0.4f
        val buttonRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        // Height follows the label: the agree button carries a second hint line until the last page is
        // reached, and a fixed height clipped it (and would clip translated labels at large font scales).
        val minButtonHeight = dpOf(style, 52)
        disagree.minimumHeight = minButtonHeight
        agree.minimumHeight = minButtonHeight
        buttonRow.gravity = Gravity.CENTER_VERTICAL
        buttonRow.addView(
            disagree,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpOf(style, 12) },
        )
        buttonRow.addView(agree, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The swipe hint used to live inside the agree button, where wrapping clipped it. As its own
        // line it can vanish once the last page is reached, and both buttons stay one line tall.
        val swipeHint = bodyLabel(
            activity, style, activity.getText(R.string.disclaimer_swipe_hint), 14f, style.muted, false,
        ).apply { gravity = Gravity.CENTER }
        controls.addView(
            swipeHint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpOf(style, 4) },
        )
        controls.addView(
            buttonRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dpOf(style, 12) },
        )
        root.addView(controls)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotViews.forEachIndexed { index, dot ->
                    dot.setBackgroundColor(if (index == position) style.accent else style.border)
                }
                val reachedLastPage = position == pages.lastIndex
                swipeHint.visibility = if (reachedLastPage) View.GONE else View.VISIBLE
                if (reachedLastPage) {
                    agree.isEnabled = true
                    agree.alpha = 1f
                }
            }
        })

        agree.setOnClickListener { onAgree(true) }
        disagree.setOnClickListener { onDisagree() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return root
    }

    private class PageAdapter(
        private val pages: List<DisclaimerPage>,
        private val style: DisclaimerStyle,
    ) : RecyclerView.Adapter<PageAdapter.PageHolder>() {
        class PageHolder(val column: LinearLayout, itemView: View) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val context = parent.context
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpOf(style, 28), dpOf(style, 12), dpOf(style, 28), dpOf(style, 12))
            }
            val scroll = ScrollView(context).apply {
                // ViewPager2 requires the page root to fill its RecyclerView in both directions.
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(column)
                isFillViewport = true
            }
            return PageHolder(column, scroll)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = pages[position]
            holder.column.removeAllViews()
            holder.column.addView(bodyLabel(holder.column.context, style, page.title, 20f, style.text, true))
            if (page.warning != null) {
                holder.column.addView(
                    bodyLabel(holder.column.context, style, page.warning, 18f, style.warning, true)
                        .apply { setPadding(0, dpOf(style, 14), 0, 0) },
                )
            }
            page.lines.forEach { line ->
                holder.column.addView(
                    bodyLabel(holder.column.context, style, line, 15f, style.muted, false)
                        .apply { setPadding(0, dpOf(style, 12), 0, 0) },
                )
            }
        }

        override fun getItemCount(): Int = pages.size
    }
}
