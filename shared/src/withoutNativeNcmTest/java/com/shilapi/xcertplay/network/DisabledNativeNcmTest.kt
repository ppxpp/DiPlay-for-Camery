package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class DisabledNativeNcmTest {
    @Test fun noPrewarmOrKernelFilesAreCreated() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "native-ncm")
        root.deleteRecursively()
        NativeNcmPreparation.prepareInBackground(app, { false }, { false }) { fail("No worker expected") }
        NativeNcmLease.requireReleased(app)
        assertFalse(root.exists())
    }

    @Test fun directNativeOpenFailsWithoutStartingPrivilegedWork() {
        val app = RuntimeEnvironment.getApplication()
        val phone = org.robolectric.util.ReflectionHelpers.newInstance(android.hardware.usb.UsbDevice::class.java)
        assertThrows(IOException::class.java) {
            NativeNcmLease.open(app, phone, "fe80::2", { true }, { fail("No worker expected") }, { fail("No async work expected") })
        }
    }
}
