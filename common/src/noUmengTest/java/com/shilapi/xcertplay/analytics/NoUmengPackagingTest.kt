package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.host.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The packaging guard for `-Pdiplay.analytics=none`. A unit test runs against the module's runtime
 * classpath, so the missing Umeng class here is the same missing class in the APK: it proves the switch
 * removed the dependency instead of merely hiding it behind an unused code path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NoUmengPackagingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun thisBuildCarriesNoUmengSdk() {
        assertFalse(BuildConfig.ANALYTICS_UMENG_BUNDLED)
        assertTrue(runCatching { Class.forName("com.umeng.commonsdk.UMConfigure") }.isFailure)
        assertFalse(createAnalyticsTracker(context).isEnabled)
    }
}
