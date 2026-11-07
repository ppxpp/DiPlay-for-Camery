package com.shilapi.xcertplay.feedback

import android.content.Intent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedbackActivitySmokeTest {
    @Test
    fun opensFromSettingsEntry() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).clearAllNetworks()
        org.robolectric.Shadows.shadowOf(manager).setActiveNetworkInfo(null)
        val controller = Robolectric.buildActivity(FeedbackActivity::class.java, FeedbackActivity.intent(
            app,
        )).setup()
        assertNotNull(controller.get().window.decorView)
        val texts = visibleTexts(controller.get().window.decorView)
        assertTrue(texts.contains(app.getString(com.shilapi.xcertplay.host.R.string.feedback_send_short)))
        assertFalse(texts.contains(app.getString(com.shilapi.xcertplay.host.R.string.feedback_network_offline)))
        assertFalse(texts.contains(app.getString(com.shilapi.xcertplay.host.R.string.feedback_save_offline)))
        controller.pause().stop().destroy()
    }

    private fun visibleTexts(view: android.view.View): List<String> {
        if (view.visibility != android.view.View.VISIBLE) return emptyList()
        val own = if (view is android.widget.TextView) listOf(view.text.toString()) else emptyList()
        return own + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap {
            visibleTexts(view.getChildAt(it))
        } else emptyList()
    }

    @Test
    fun opensFromConnectionFailureWithPrefilledCategory() {
        val intent = FeedbackActivity.intent(
            org.robolectric.RuntimeEnvironment.getApplication(),
            FeedbackCategory.CONNECTION,
            "hotspot not ready",
        )
        val controller = Robolectric.buildActivity(FeedbackActivity::class.java, intent).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }

    @Test
    fun recordsPageOpens() {
        val controller = Robolectric.buildActivity(
            FeedbackRecordsActivity::class.java,
            Intent(org.robolectric.RuntimeEnvironment.getApplication(), FeedbackRecordsActivity::class.java),
        ).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }

    @Test
    fun descriptionEditorOpensWithExistingText() {
        val controller = Robolectric.buildActivity(
            FeedbackDescriptionActivity::class.java,
            FeedbackDescriptionActivity.intent(
                org.robolectric.RuntimeEnvironment.getApplication(),
                "existing description",
            ),
        ).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }
}
