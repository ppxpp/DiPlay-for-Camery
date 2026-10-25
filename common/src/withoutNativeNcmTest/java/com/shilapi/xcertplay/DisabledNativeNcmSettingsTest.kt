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
class DisabledNativeNcmSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    @After fun cleanup() {
        activity?.finish()
        app.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
    }

    @Test fun disabledBuildOffersThreeDebugChoicesAndDefaultsToVpn() {
        ShadowBuild.setModel("SABRESD_MX6DQ")
        ShadowBuild.setDevice("tamago")
        val screen = open()
        category(screen, SettingsCategory.CONNECTION)
        val title = screen.getString(R.string.settings_wired_backend)
        val controls = descendants(screen.window.decorView).filterIsInstance<TextView>()
            .filter { it.text.toString().startsWith("$title · ") }.toList()
        if (!PioneerWiredBackend.selectorVisible) {
            assertTrue(controls.isEmpty())
            assertEquals(PioneerWiredBackend.Mode.VPN, PioneerWiredBackend.mode(screen))
            return
        }
        val control = controls.single()
        assertTrue(control.text.toString().contains(screen.getString(R.string.settings_wired_backend_vpn)))
        control.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        for (index in 0..2) assertFalse(dialog.listView.adapter.getItem(index).toString().contains("NCM"))
        assertEquals(PioneerWiredBackend.Mode.VPN, PioneerWiredBackend.mode(screen))
        dialog.listView.performItemClick(null, 0, dialog.listView.adapter.getItemId(0))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(PioneerWiredBackend.Mode.LWIP, PioneerWiredBackend.mode(screen))
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
