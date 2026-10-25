package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.media.CarMicTestRecorder
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarMicTestActivityTest {
    private val controller = Robolectric.buildActivity(CarMicTestActivity::class.java)

    @After fun close() { controller.pause().stop().destroy() }

    @Test fun openingWaitsForConfirmationAndCancelNeverRecords() {
        controller.setup()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertEquals("waiting-confirmation", JSONObject(PioneerDebugRuntime.carMicTestState).getString("phase"))
        assertFalse(CarMicTestRecorder.isRunning())
        assertEquals(5, CarMicTestRecorder.DEFAULT_SECONDS)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("cancelled", JSONObject(PioneerDebugRuntime.carMicTestState).getString("phase"))
        assertFalse(CarMicTestRecorder.isRunning())
    }

    @Test fun feedbackRequiresSelectionAndSupportsCombinedAbnormalities() {
        controller.setup()
        shadowOf(Looper.getMainLooper()).idle()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        CarMicTestActivity::class.java.getDeclaredMethod("showGuidedFeedback").apply { isAccessible = true }.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        assertEquals("重播", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        fun checks(view: android.view.View): List<android.widget.CheckBox> = when (view) {
            is android.widget.CheckBox -> listOf(view)
            is android.view.ViewGroup -> (0 until view.childCount).flatMap { checks(view.getChildAt(it)) }
            else -> emptyList()
        }
        val choices = checks(dialog.window!!.decorView)
        assertEquals(8, choices.size)
        for (index in listOf(1, 3, 4)) choices[index].performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val result = JSONObject(PioneerDebugRuntime.carMicTestState)
        assertEquals("complete", result.getString("phase"))
        assertEquals("quiet,echo,pitch-or-speed", result.getString("feedback"))
        assertEquals(3, result.getJSONArray("feedbackChoices").length())
        val saved = JSONObject(activity.getSharedPreferences("pioneer-debug", 0).getString("car_mic_guided_result", "{}")!!)
        assertEquals(result.getString("feedback"), saved.getString("feedback"))
        fun findBack(view: android.view.View): android.widget.Button? {
            if (view is android.widget.Button && view.text.toString() == "返回") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                findBack(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        findBack(activity.window.decorView)!!.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertEquals("complete", JSONObject(PioneerDebugRuntime.carMicTestState).getString("phase"))
    }
}
