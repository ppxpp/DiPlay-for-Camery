package com.shilapi.xcertplay.pioneer

import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerNativeNcmStartupPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() = clear()
    @After fun cleanup() = clear()
    private fun clear() { context.getSharedPreferences("pioneer-debug", 0).edit().clear().commit() }

    @Test fun productionDefaultsEnablePrewarmAndWorkerReuse() {
        assertTrue(PioneerNativeNcmStartupPolicy.prewarmEnabled(context))
        assertTrue(PioneerNativeNcmStartupPolicy.warmWorkerEnabled(context))
    }

    @Test fun disablingReuseDoesNotDisableModulePrewarm() {
        PioneerNativeNcmStartupPolicy.save(context, false, null)
        assertFalse(PioneerNativeNcmStartupPolicy.warmWorkerEnabled(context))
        assertTrue(PioneerNativeNcmStartupPolicy.prewarmEnabled(context))
    }

    @Test fun partialUpdatesPreserveOtherExperimentAndCanRestoreDefaults() {
        PioneerNativeNcmStartupPolicy.save(context, false, false)
        PioneerNativeNcmStartupPolicy.save(context, null, true)
        assertFalse(PioneerNativeNcmStartupPolicy.warmWorkerEnabled(context))
        assertTrue(PioneerNativeNcmStartupPolicy.prewarmEnabled(context))
        PioneerNativeNcmStartupPolicy.save(context, true, null)
        assertTrue(PioneerNativeNcmStartupPolicy.warmWorkerEnabled(context))
        assertTrue(PioneerNativeNcmStartupPolicy.prewarmEnabled(context))
    }
}
