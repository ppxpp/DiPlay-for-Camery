package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateChannel
import com.shilapi.xcertplay.update.UpdateChannelStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w600dp-h700dp")
class UpdateChannelSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private var originalMetaData: Bundle? = null

    @Before fun setUp() {
        val info = requireNotNull(
            shadowOf(context.packageManager)
                .getInternalMutablePackageInfo(context.packageName).applicationInfo,
        )
        originalMetaData = info.metaData
        info.metaData = Bundle().apply {
            putString(UpdateChannelStore.BUILD_CHANNEL_META_DATA, "beta")
        }
        UpdateChannelStore.clearForTest(context)
    }

    @After fun tearDown() {
        activity?.finish()
        UpdateChannelStore.clearForTest(context)
        requireNotNull(shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName).applicationInfo).metaData = originalMetaData
    }

    @Test fun userCanSwitchFromBuildDefaultBetaToStableAndChoicePersists() {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        ReflectionHelpers.setField(screen, "updateCheckWorker", { _: Runnable -> })
        ReflectionHelpers.callInstanceMethod<Unit>(
            screen,
            "openSettingsCategory",
            ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.ABOUT),
        )

        val title = screen.getString(R.string.settings_update_channel)
        val beta = screen.getString(R.string.update_channel_beta)
        val stable = screen.getString(R.string.update_channel_stable)
        assertEquals(UpdateChannel.BETA, UpdateChannelStore.load(screen))
        assertNull(UpdateChannelStore.selected(screen))
        descendants(screen.window.decorView)
            .filterIsInstance<TextView>()
            .single { it.text.toString() == "$title · $beta" }
            .performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, UpdateChannel.STABLE.ordinal, UpdateChannel.STABLE.ordinal.toLong())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(UpdateChannel.STABLE, UpdateChannelStore.load(screen))
        assertEquals(UpdateChannel.STABLE, UpdateChannelStore.selected(screen))
        descendants(screen.window.decorView)
            .filterIsInstance<TextView>()
            .single { it.text.toString() == "$title · $stable" }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
