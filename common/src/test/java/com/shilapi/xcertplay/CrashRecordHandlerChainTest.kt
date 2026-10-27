package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What "-Pdiplay.analytics=umeng" means for the crash path: U-APM reports, the local file still gets
 * written for the diagnostic report. The ContentProvider installs ours before any SDK can initialise,
 * so the relink is what keeps the local record from being bypassed when U-APM takes the handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CrashRecordHandlerChainTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun aVendorHandlerInstalledLaterStillWritesTheLocalRecord() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var reachedVendor = false
        try {
            CrashRecord.install(context)
            val vendor = Thread.UncaughtExceptionHandler { _, _ -> reachedVendor = true }
            Thread.setDefaultUncaughtExceptionHandler(vendor)
            CrashRecord.ensureOutermost(context)

            val handler = Thread.getDefaultUncaughtExceptionHandler()
            assertNotSame(vendor, handler)
            handler?.uncaughtException(Thread.currentThread(), IllegalStateException("usb detach during session"))

            assertTrue(reachedVendor)
            assertTrue(CrashRecord.report(context).contains("IllegalStateException"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
