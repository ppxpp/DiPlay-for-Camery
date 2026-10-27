package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.Disclaimer
import com.shilapi.xcertplay.host.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The consent contract, asserted identically in both builds: the gate is the startup disclaimer, and an
 * un-started tracker drops events instead of throwing. Calling start() is deliberately avoided here
 * because the umeng build would reach the real SDK, and this test is not the place for that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AnalyticsConsentTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun nothingIsCollectedBeforeTheDisclaimerIsAccepted() {
        assertFalse(Analytics.isAllowed(context))
        Analytics.startIfAllowed(context)
        assertFalse(Analytics.isReporting)
        Analytics.event("carplay_connect_result", mapOf("result" to "success"))
    }

    @Test fun theDisclaimerIsWhatOpensTheGate() {
        Disclaimer.accept(context)
        assertEquals(BuildConfig.ANALYTICS_UMENG_BUNDLED, Analytics.isAllowed(context))
    }

    @Test fun anOptOutClosesTheGateAgain() {
        Disclaimer.accept(context)
        Analytics.setEnabled(context, false)
        assertFalse(Analytics.isAllowed(context))
    }
}
