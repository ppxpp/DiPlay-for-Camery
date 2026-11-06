package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreparationLayoutTest {
    @Test @Config(qualifiers = "sw720dp-w1280dp-h720dp-xhdpi", fontScale = 1.3f)
    fun vehicleNoticeUsesTitleSizing() = preparationContentScrollsInsteadOfShrinkingText()

    @Test @Config(qualifiers = "ar-rEG-w360dp-h640dp-mdpi", fontScale = 1.5f)
    fun rtlLargeTextRemainsReadable() = preparationContentScrollsInsteadOfShrinkingText()

    @Config(qualifiers = "w360dp-h640dp-mdpi")
    @Test fun preparationContentScrollsInsteadOfShrinkingText() {
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
        method.isAccessible = true
        val root = method.invoke(activity) as android.widget.FrameLayout
        // The viewport is named; a video-only overlay can be inserted before it.
        val viewport = CarPlayHostActivity::class.java.getDeclaredField("connectionPanel")
            .apply { isAccessible = true }.get(activity) as FrameLayout
        val preparationScroll = viewport.getChildAt(0) as android.widget.ScrollView
        val content = preparationScroll.getChildAt(0) as LinearLayout
        val noticeView = content.getChildAt(0) as TextView
        val panel = content.getChildAt(1) as LinearLayout
        val density = activity.resources.displayMetrics.density
        fun layout(widthDp: Int, heightDp: Int) {
            val width = (widthDp * density).toInt()
            val height = (heightDp * density).toInt()
            repeat(3) {
                // This detached view has no ViewRoot to schedule the next traversal.
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) invalidateMeasurement(view.getChildAt(index))
                    }
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            val title = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<TextView>()
                .first { it.text == activity.getString(R.string.diplay) }
            assertEquals("width=$widthDp height=$heightDp notice must follow title sizing", title.textSize, noticeView.textSize, 0.01f)
            assertTrue(noticeView.lineCount > 0)
            assertEquals("Full notice must remain readable", noticeView.text.length,
                noticeView.layout.getLineEnd(noticeView.lineCount - 1))
            assertTrue("Notice must precede controls", noticeView.bottom <= panel.top)
            val last = panel.getChildAt(panel.childCount - 1)
            assertTrue("height=$heightDp bottom=${last.bottom} padding=${panel.paddingBottom} panel=${panel.height}", last.bottom + panel.paddingBottom <= panel.height)
            assertEquals(0, preparationScroll.scrollY)
            if (content.height > viewport.height) {
                // Short viewports scroll the preparation content instead of shrinking its text.
                assertTrue("height=$heightDp", preparationScroll.canScrollVertically(1))
                assertFalse("height=$heightDp", preparationScroll.canScrollVertically(-1))
                val overflow = panel.top + last.bottom + panel.paddingBottom - viewport.height
                assertTrue("height=$heightDp overflow=$overflow scrollable=${content.height - viewport.height}",
                    overflow <= content.height - viewport.height + 1)
            } else {
                assertFalse("height=$heightDp", preparationScroll.canScrollVertically(1))
            }
        }
        for (width in listOf(320, 640, 1280))
            for (height in listOf(200, 240, 320, 400, 479, 480, 720)) layout(width, height)
        val title = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.diplay) }
        assertEquals(28f, title.textSize / activity.resources.displayMetrics.scaledDensity, 0.01f)
        val notice = activity.getString(R.string.home_license_notice)
        assertEquals(notice, noticeView.text.toString())
        assertFalse((0 until panel.childCount).map(panel::getChildAt).filterIsInstance<TextView>().any { it.text == notice })
    }
}
