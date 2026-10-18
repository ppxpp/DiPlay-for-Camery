package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class ShortcutBarPolicyTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Before fun reset() {
        ShadowBuild.setModel("SABRESD_MX6DQ")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "tamago_test")
        ReflectionHelpers.setStaticField(ShortcutBarPolicy::class.java, "attached", false)
        ReflectionHelpers.setStaticField(ShortcutBarPolicy::class.java, "foreground", false)
        app.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).broadcastIntents.clear()
    }

    private fun shown() = shadowOf(app).broadcastIntents
        .filter { it.action == "jp.pioneer.ceam.ui.show.shortcutbar" }
        .map { it.getBooleanExtra("BAR_KEY", true) }

    @Test fun foregroundHidesByDefaultAndBackgroundRestores() {
        ShortcutBarPolicy.attach(app, owner.lifecycle)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(listOf(false, true), shown())
    }

    @Test fun changingTheSettingAppliesImmediatelyAndBackgroundNeverHides() {
        ShortcutBarPolicy.attach(app, owner.lifecycle)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        ShortcutBarPolicy.setHidesBottomArrow(app, false)
        assertFalse(ShortcutBarPolicy.hidesBottomArrow(app))
        ShortcutBarPolicy.setHidesBottomArrow(app, true)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        ShortcutBarPolicy.setHidesBottomArrow(app, false)
        ShortcutBarPolicy.setHidesBottomArrow(app, true)
        assertEquals(listOf(false, true, false, true, true, true), shown())
    }

    @Test fun disabledPreferenceSurvivesForegroundTransitions() {
        ShortcutBarPolicy.setHidesBottomArrow(app, false)
        ShortcutBarPolicy.attach(app, owner.lifecycle)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(true, true, true), shown())
    }

    @Test fun otherHeadUnitsNeverReceiveThePioneerBroadcast() {
        ShadowBuild.setModel("Other head unit")
        ShortcutBarPolicy.attach(app, owner.lifecycle)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        ShortcutBarPolicy.setHidesBottomArrow(app, false)
        ShortcutBarPolicy.setHidesBottomArrow(app, true)
        assertTrue(shown().isEmpty())
    }
}
