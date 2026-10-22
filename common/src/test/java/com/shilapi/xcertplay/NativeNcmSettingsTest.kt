package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.app.AlertDialog
import org.robolectric.shadows.ShadowAlertDialog
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], qualifiers = "en-w600dp-h900dp")
class NativeNcmSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    @After fun cleanup() {
        activity?.finish()
        app.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
    }

    @Test fun fourManualChoicesLiveInConnectionAndPersistIndependently() {
        ShadowBuild.setModel("SABRESD_MX6DQ")
        ShadowBuild.setDevice("tamago")
        val screen = open()
        category(screen, SettingsCategory.CONNECTION)
        val title = screen.getString(R.string.settings_wired_backend)
        fun control() = descendants(screen.window.decorView).filterIsInstance<TextView>()
            .single { it.text.toString().startsWith("$title · ") }
        assertEquals(PioneerWiredBackend.Mode.LWIP, PioneerWiredBackend.mode(screen))
        for (mode in listOf(PioneerWiredBackend.Mode.NATIVE_NCM, PioneerWiredBackend.Mode.LWIP_OPTIMIZED, PioneerWiredBackend.Mode.VPN, PioneerWiredBackend.Mode.LWIP)) {
            control().performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(4, dialog.listView.adapter.count)
            dialog.listView.performItemClick(null, mode.ordinal, dialog.listView.adapter.getItemId(mode.ordinal))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(mode, PioneerWiredBackend.mode(screen))
            category(screen, SettingsCategory.AUDIO)
            assertFalse(descendants(screen.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().startsWith("$title · ") })
            category(screen, SettingsCategory.CONNECTION)
            assertEquals(mode, PioneerWiredBackend.mode(screen))
        }
    }

    @Test fun otherFirmwareDoesNotOfferTheWiredBackendSelector() {
        ShadowBuild.setModel("other")
        val screen = open()
        category(screen, SettingsCategory.CONNECTION)
        assertFalse(descendants(screen.window.decorView).filterIsInstance<TextView>()
            .any { it.text.toString().startsWith(screen.getString(R.string.settings_wired_backend) + " · ") })
    }

    private fun open() = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"))
        .setup().get().also { activity = it }
    private fun category(screen: DiPlayActivity, value: SettingsCategory) {
        ReflectionHelpers.setField(screen, "settingsCategory", value)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
