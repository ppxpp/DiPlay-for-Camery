package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.pioneer.ShortcutBarPolicy
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h900dp")
class CamryDisplaySettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun cleanup() {
        activity?.finish()
        RuntimeEnvironment.setFontScale(1f)
        app.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
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

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    @Test fun camryArrowIsOnlyInDisplayAndItsChoicePersists() {
        val screen = open()
        val title = screen.getString(R.string.settings_camry_hide_bottom_arrow)
        for (value in listOf(SettingsCategory.VEHICLE, SettingsCategory.ADVANCED, SettingsCategory.CONNECTION)) {
            category(screen, value)
            assertFalse(texts(screen).any { it.text == title })
        }
        category(screen, SettingsCategory.DISPLAY)
        fun control() = descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == title }
        assertTrue(control().isChecked)
        control().performClick()
        assertFalse(ShortcutBarPolicy.hidesBottomArrow(screen))
        category(screen, SettingsCategory.AUDIO)
        category(screen, SettingsCategory.DISPLAY)
        assertFalse(control().isChecked)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun usbCamryAdviceIsHighlightedAndReducedCurrentIsSelectable() {
        val screen = open()
        category(screen, SettingsCategory.CONNECTION)
        val advice = texts(screen).single { it.text == screen.getString(R.string.settings_camry_usb_charging_advice) }
        val description = texts(screen).single { it.text == screen.getString(R.string.settings_usb_charging_description) }
        assertTrue("USB advice must retain a bold typeface", advice.typeface.isBold)
        assertNotEquals(description.currentTextColor, advice.currentTextColor)
        texts(screen).single { it.text.startsWith(screen.getString(R.string.settings_usb_charging) + " ·") }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val index = UsbChargingCurrent.entries.indexOf(UsbChargingCurrent.REDUCED)
        assertEquals(screen.getString(R.string.settings_usb_charging_reduced), dialog.listView.adapter.getItem(index))
        shadowOf(Looper.getMainLooper()).idle()
        dialog.listView.performItemClick(null, index, dialog.listView.adapter.getItemId(index))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1500, AirPlayPersistence.loadUsbChargingCurrent(screen).milliAmps)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h700dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactCamrySettingsFitLargeFonts() = screenshots("compact")

    @Test
    @Config(qualifiers = "zh-rCN-w1000dp-h700dp-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wideCamrySettingsFitLargeFonts() = screenshots("wide")

    private fun screenshots(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val screen = open()
        for (value in listOf(SettingsCategory.DISPLAY, SettingsCategory.CONNECTION)) {
            category(screen, value)
            val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = screen.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idle()
            val id = if (value == SettingsCategory.DISPLAY) R.string.settings_camry_hide_bottom_arrow
                else R.string.settings_camry_usb_charging_advice
            val label = texts(screen).single { it.text == screen.getString(id) }
            assertTrue(label.width > 0)
            for (line in 0 until label.layout.lineCount) assertEquals(0, label.layout.getEllipsisCount(line))
            // Capture the actual settings card so a long category cannot hide the new control
            // below the viewport in the evidence image.
            val card = generateSequence(label.parent as? View) { it.parent as? View }
                .first { it.background is GradientDrawable }
            val image = Bitmap.createBitmap(card.width, minOf(card.height, root.height), Bitmap.Config.ARGB_8888)
            try {
                card.draw(Canvas(image))
                val output = Paths.get("build/reports/camry-display-ui/$name-${value.name.lowercase()}.png")
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
        }
    }
}
