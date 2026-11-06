package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import androidx.activity.ComponentActivity
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.pioneer.*
import com.shilapi.xcertplay.pioneer.PioneerApplicationControl.State
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CamryApplicationsLifecycleTest.ControlShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CamryApplicationsLifecycleTest {
    companion object {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        @Volatile var actual = State.ENABLED
        @Volatile var failWrite = false
        @Volatile var block: CountDownLatch? = null
        @Volatile var entered = CountDownLatch(1)
        @Volatile var finished = CountDownLatch(1)
    }
    @Implements(PioneerApplicationControl::class)
    class ControlShadow {
        @Implementation fun read(context: Context, current: () -> Boolean): Map<PioneerManagedApplication, State> {
            reads.incrementAndGet()
            val snapshot = actual
            val gate = block
            entered.countDown()
            if (gate != null) {
                // A Binder call may complete despite interruption; the page must reject its result.
                while (true) { try { gate.await(); break } catch (_: InterruptedException) {} }
            }
            finished.countDown()
            return PioneerManagedApplication.values().associateWith { snapshot }
        }
        @Implementation fun setDisabled(context: Context, target: PioneerManagedApplication, disabled: Boolean,
                                        current: () -> Boolean): State {
            check(current())
            writes.incrementAndGet(); actual = if (disabled) State.DISABLED else State.ENABLED
            if (failWrite) throw java.io.IOException("lost Binder reply")
            return actual
        }
    }
    private lateinit var activity: org.robolectric.android.controller.ActivityController<ComponentActivity>
    private lateinit var container: LinearLayout
    private lateinit var page: View
    private lateinit var controller: Any
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private fun field(name: String): Any? = controller.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(controller)
    private fun switches() = descendants(page).filterIsInstance<Switch>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun drain() {
        (field("executor") as? ExecutorService)?.submit {}?.get(3, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Before fun setup() {
        reads.set(0); writes.set(0); actual = State.ENABLED; failWrite = false
        block = null; entered = CountDownLatch(1); finished = CountDownLatch(1)
        PioneerUsbPolicy.setMode(app, "ON")
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        container = LinearLayout(activity.get()); activity.get().setContentView(container)
        val type = Class.forName("com.shilapi.xcertplay.settings.CamryApplicationSettingsSection\$Controller")
        controller = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(
            activity.get(), SettingsTheme.card(DiPlayPalette.LIGHT), DiPlayPalette.LIGHT, { _: String -> Unit })
        page = field("root") as View
        container.addView(page); drain()
    }
    @After fun cleanup() {
        block?.countDown()
        container.removeAllViews()
        activity.pause().stop().destroy()
        PioneerUsbPolicy.setMode(app, "AUTO")
    }
    @Test fun pauseDisablesRowsAndResumeQueriesActualStateWithoutRecreation() {
        assertTrue(switches().all { it.isEnabled && !it.isChecked })
        val before = reads.get()
        activity.pause()
        assertTrue(switches().none { it.isEnabled })
        actual = State.DISABLED
        activity.resume(); drain()
        assertEquals(before + 1, reads.get())
        assertTrue(switches().all { it.isEnabled && it.isChecked })
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
        assertEquals(before + 1, reads.get()) // no polling
    }
    @Test fun disableThenReenableAndLostReplyReconcilesWithoutReplay() {
        switches().first().performClick(); drain()
        assertTrue(switches().first().isChecked); assertEquals(1, writes.get())
        switches().first().performClick(); drain()
        assertFalse(switches().first().isChecked); assertEquals(2, writes.get())
        failWrite = true
        switches().first().performClick(); drain(); drain()
        assertTrue(switches().all { it.isChecked }); assertEquals(3, writes.get())
    }
    @Test fun detachAndReattachRejectsOldBinderCompletion() {
        block = CountDownLatch(1); entered = CountDownLatch(1); finished = CountDownLatch(1)
        val oldExecutor = field("executor") as ExecutorService
        val refresh = controller.javaClass.getDeclaredMethod("refresh").apply { isAccessible = true }
        refresh.invoke(controller)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        container.removeView(page)
        actual = State.DISABLED
        val gate = block!!; block = null
        container.addView(page); drain()
        assertTrue(switches().all { it.isChecked })
        gate.countDown(); assertTrue(oldExecutor.awaitTermination(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(switches().all { it.isChecked })
    }
    @Test fun refreshEventsWhileBusyAreCoalescedAndUnknownRowsCannotMutate() {
        actual = State.UNKNOWN
        block = CountDownLatch(1); entered = CountDownLatch(1)
        val refresh = controller.javaClass.getDeclaredMethod("refresh").apply { isAccessible = true }
        val before = reads.get()
        refresh.invoke(controller)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(20) { refresh.invoke(controller) }
        assertTrue(switches().none { it.isEnabled })
        block!!.countDown(); block = null
        drain(); drain()
        assertEquals(before + 2, reads.get())
        assertTrue(switches().none { it.isEnabled })
        assertEquals(0, writes.get())
    }
    @Test fun adaptationOffInvalidatesRowsAndOnReloadsWithoutMutation() {
        PioneerUsbPolicy.setMode(app, "OFF"); shadowOf(Looper.getMainLooper()).idle(); drain()
        assertTrue(switches().none { it.isEnabled })
        actual = State.DISABLED
        PioneerUsbPolicy.setMode(app, "ON"); shadowOf(Looper.getMainLooper()).idle(); drain()
        assertTrue(switches().all { it.isEnabled && it.isChecked }); assertEquals(0, writes.get())
    }
    @Test fun packageEventsRefreshOnlyAllowlistedAppsAndDetachUnregisters() {
        val before = reads.get()
        app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:android")))
        shadowOf(Looper.getMainLooper()).idle(); drain(); assertEquals(before, reads.get())
        actual = State.DISABLED
        app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:com.autonavi.amapauto")))
        shadowOf(Looper.getMainLooper()).idle(); drain()
        assertEquals(before + 1, reads.get()); assertTrue(switches().all { it.isChecked })
        container.removeView(page)
        app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:com.autonavi.amapauto")))
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(before + 1, reads.get())
    }
}
