package com.shilapi.xcertplay.pioneer

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PioneerDebugReleaseBoundaryTest {
    @Test fun releaseCannotStartLanListenerThroughEitherEntryPoint() {
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertFalse(PioneerDebugRuntime.automaticStartEnabled(context))
        assertThrows(IllegalStateException::class.java) { PioneerDebugRuntime.start(context) }
        val controller = Robolectric.buildService(PioneerDebugService::class.java).create()
        val service = controller.get()
        assertTrue(PioneerDebugService::class.java.getDeclaredField("closed").apply { isAccessible = true }.getBoolean(service))
        assertNull(PioneerDebugService::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(service))
        controller.destroy()
    }
}
