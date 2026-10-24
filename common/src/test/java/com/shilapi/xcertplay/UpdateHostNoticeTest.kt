package com.shilapi.xcertplay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateChannel
import com.shilapi.xcertplay.update.UpdateChannelStore
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [29],
    qualifiers = "en-w900dp-h600dp-land",
    shadows = [UpdateHostNoticeTest.Bootstrap::class],
)
class UpdateHostNoticeTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test fun cachedUpdateShowsOnTheUsbWaitingScreenAndOpensTheAboutDownloadFlow() {
        saveCachedUpdate()
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java).setup()
        val activity = controller.get()
        val title = activity.getString(R.string.update_available_connection, "v99.0.0")
        val content = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
            .apply { isAccessible = true }.invoke(activity) as View
        val titleView = descendants(content).filterIsInstance<TextView>()
            .single { it.text == title }
        val banner = generateSequence<View>(titleView) { it.parent as? View }
            .first { it.isClickable && it.contentDescription?.toString()?.contains(title) == true }

        val night = ReflectionHelpers.getField<Boolean>(activity, "appNight")
        assertEquals(DiPlayPalette.of(night).warning, titleView.currentTextColor)
        assertTrue(banner.isClickable)
        assertTrue(banner.isFocusable)
        banner.performClick()

        val started = generateSequence { shadowOf(activity).nextStartedActivity }
            .take(10)
            .firstOrNull { it.component?.className == DiPlayActivity::class.java.name }
        assertEquals("update", started?.getStringExtra("page"))
        controller.pause().stop().destroy()
    }

    @Test fun updatePageIntentOpensAboutWithTheDownloadAction() {
        saveCachedUpdate()
        val intent = Intent(application, DiPlayActivity::class.java).putExtra("page", "update")
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup()
        val activity = controller.get()

        assertEquals(SettingsCategory.ABOUT, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.update_download)
        })
        controller.pause().stop().destroy()
    }

    private fun saveCachedUpdate() {
        shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName)?.versionCode = 1
        UpdateChannelStore.save(application, UpdateChannel.STABLE)
        UpdateAvailability.save(application, release(), UpdateChannel.STABLE)
    }

    private fun release() = UpdateRelease(
        tagName = "v99.0.0",
        versionCode = 99L,
        apkName = "DiPlay.apk",
        apkUrl = "https://example.com/DiPlay.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }

    /** The projection screen must start without the private MFi identity used by real cars. */
    @Implements(DiPlayBootstrap::class, isInAndroidSdk = false)
    internal class Bootstrap {
        @Implementation fun ensure(context: Context) = Unit

        @Implementation fun deviceId(identity: AirPlayIdentity): String = "02:00:00:00:00:01"
    }
}
