package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.Disclaimer
import com.shilapi.xcertplay.host.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the umeng build has to guarantee: the flag is on and a real key reached BuildConfig, because a
 * blank one produces an APK that reports to nobody and never says so.
 *
 * Deliberately no `Class.forName` and no tracker instantiation in here. Robolectric's class verifier
 * rejects Umeng's own bytecode outright (`VerifyError` in `UMConfigure.checkShareSdk`), so loading the
 * SDK from a unit test fails for reasons that have nothing to do with this build. The umeng source set
 * only compiles against `com.umeng.*` when the artifacts resolved, and that is the packaging guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UmengWiringTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun theUmengBuildCarriesItsOwnKey() {
        assertTrue(BuildConfig.ANALYTICS_UMENG_BUNDLED)
        assertTrue(BuildConfig.UMENG_APP_KEY.isNotBlank())
        assertTrue(BuildConfig.UMENG_CHANNEL.isNotBlank())
    }

    @Test fun anAcceptedDisclaimerOpensTheGateOnlyInThisBuild() {
        Disclaimer.accept(context)
        assertTrue(Analytics.isAllowed(context))
        Analytics.setEnabled(context, false)
        assertFalse(Analytics.isAllowed(context))
    }
}
