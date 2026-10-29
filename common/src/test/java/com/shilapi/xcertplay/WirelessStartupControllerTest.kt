package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()
    @Before fun resetAdaptationBefore() { com.shilapi.xcertplay.pioneer.CamryAdaptation.save(RuntimeEnvironment.getApplication(),com.shilapi.xcertplay.pioneer.CamryAdaptation.Mode.AUTO) }
    @After fun resetAdaptationAfter() { resetAdaptationBefore() }
    private fun controller(mode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P, transport: CarPlayTransport = CarPlayTransport.WIRELESS, progress: com.shilapi.xcertplay.orchestration.progress.ConnectionProgress? = null): CarPlayController {
        if(mode==WirelessHotspotMode.PIONEER_VEHICLE)
            com.shilapi.xcertplay.pioneer.CamryAdaptation.save(RuntimeEnvironment.getApplication(),com.shilapi.xcertplay.pioneer.CamryAdaptation.Mode.ON)
        return CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
    },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = transport, wirelessHotspotMode = mode,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add, connectionProgress=progress)
    }

    @Test fun vehicleRestartWaitsForLeaseCleanupBeyondTheOrdinaryBudget() {
        val controller = controller(WirelessHotspotMode.PIONEER_VEHICLE)
        val done = ReflectionHelpers.getField<java.util.concurrent.CountDownLatch>(controller, "teardownComplete")
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val entered = java.util.concurrent.CountDownLatch(1)
            val waiting = worker.submit<Boolean> { entered.countDown(); controller.awaitClosedForRestart(1) { false } }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(waiting.isDone)
            done.countDown()
            assertTrue(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { done.countDown(); worker.shutdownNow(); controller.close(); controller.awaitClosed(2000) }
    }

    @Test fun cancelledVehicleRestartDoesNotStartAReplacement() {
        val controller = controller(WirelessHotspotMode.PIONEER_VEHICLE)
        try {
            assertFalse(controller.awaitClosedForRestart(1) { true })
        } finally { controller.close(); controller.awaitClosed(2000) }
    }

    @Test fun ordinaryWirelessRetainsItsBoundedTeardownWait() {
        val controller = controller()
        try {
            assertFalse(controller.requiresCompleteTeardownBeforeRestart)
            assertFalse(controller.awaitClosedForRestart(1) { false })
        } finally { controller.close(); controller.awaitClosed(2000) }
    }

    @Test fun initialWirelessCleanupPreservesPrewarmButRealTeardownClosesIt() {
        val controller=controller(WirelessHotspotMode.PIONEER_VEHICLE)
        val pending=com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.PioneerVehicleHotspot(
            RuntimeEnvironment.getApplication(),{ false },{})
        val cleanup=controller.javaClass.getDeclaredMethod("closeWirelessStack",CarPlayVpnService::class.java,
            Int::class.javaObjectType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
        try {
            ReflectionHelpers.setField(controller,"pioneerHotspotPrewarm",pending)
            cleanup.invoke(controller,null,0,true)
            assertSame(pending,ReflectionHelpers.getField(controller,"pioneerHotspotPrewarm"))
            assertFalse(ReflectionHelpers.getField<Boolean>(pending,"closed"))
            cleanup.invoke(controller,null,0,false)
            assertNull(ReflectionHelpers.getField<Any?>(controller,"pioneerHotspotPrewarm"))
            assertTrue(ReflectionHelpers.getField<Boolean>(pending,"closed"))
        } finally { controller.close();controller.awaitClosed(2000) }
    }

    @Test fun unifiedAdaptationForcesTheControllerEvenWithAnOrdinaryRequestedMode() {
        com.shilapi.xcertplay.pioneer.CamryAdaptation.save(RuntimeEnvironment.getApplication(),com.shilapi.xcertplay.pioneer.CamryAdaptation.Mode.ON)
        val c=controller()
        try {
            assertEquals(WirelessHotspotMode.PIONEER_VEHICLE,ReflectionHelpers.getField<WirelessHotspotMode>(c,"wirelessHotspotMode"))
            assertTrue(c.requiresCompleteTeardownBeforeRestart)
        } finally { c.close();c.awaitClosed(2000) }
    }
    @Test fun forceOnDoesNotChangeWiredTransport() {
        com.shilapi.xcertplay.pioneer.CamryAdaptation.save(RuntimeEnvironment.getApplication(),com.shilapi.xcertplay.pioneer.CamryAdaptation.Mode.ON)
        val c=controller(transport=CarPlayTransport.WIRED)
        try { assertFalse(c.requiresCompleteTeardownBeforeRestart) }
        finally { c.close();c.awaitClosed(2000) }
    }

    @Test fun disconnectedPhoneFailsTheBluetoothNodeWithANonRetryableReason() {
        val p=com.shilapi.xcertplay.orchestration.progress.ConnectionProgress("test",true,
            channel=com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.PIONEER_WIRELESS)
        val c=controller(WirelessHotspotMode.PIONEER_VEHICLE,progress=p)
        try {
            fail(c,0,WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,"connect phone"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(com.shilapi.xcertplay.orchestration.progress.ConnectionStep.DEVICE,p.snapshot().current)
            assertEquals(com.shilapi.xcertplay.orchestration.progress.ConnectionOutcome.FAILED,p.snapshot().outcome)
            assertEquals(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,(statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { c.close();c.awaitClosed(2000) }
    }

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            controller.javaClass.getDeclaredMethod("closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(controller, null, 0, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }
}
