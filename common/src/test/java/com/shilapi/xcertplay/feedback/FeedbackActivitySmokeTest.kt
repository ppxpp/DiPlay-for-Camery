package com.shilapi.xcertplay.feedback

import android.content.Intent
import org.junit.Assert.assertNotNull
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
        val controller = Robolectric.buildActivity(FeedbackActivity::class.java, FeedbackActivity.intent(
            org.robolectric.RuntimeEnvironment.getApplication(),
        )).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
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
