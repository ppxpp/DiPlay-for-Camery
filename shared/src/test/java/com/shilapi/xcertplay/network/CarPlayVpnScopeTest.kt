package com.shilapi.xcertplay.network

import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the real attachment transaction while controlling only the Android VPN boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [VpnScopeBuilderShadow::class])
class CarPlayVpnScopeTest {
    @Before fun resetVpnBoundary() {
        VpnScopeBoundary.reset()
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
    }

    @Test fun allowlistRejectionNeverEstablishesAnUnrestrictedTunnel() {
        VpnScopeBoundary.allowFailure = PackageManager.NameNotFoundException("scope rejected")
        withService { service ->
            val result = attachWired(service)

            assertEquals(CarPlayVpnService.AttachResult.Failed("scope rejected"), result)
            assertEquals(listOf("allow:${service.packageName}"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun runtimePackageIsAllowedBeforeEstablishAndRevokedConsentCleansUp() {
        withService { service ->
            val result = attachWired(service)

            assertEquals(CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"), result)
            assertEquals(listOf("allow:${service.packageName}", "establish"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun platformEstablishmentFailureDoesNotRetryOrKeepAttachmentState() {
        VpnScopeBoundary.establishFailure = SecurityException("consent revoked")
        withService { service ->
            assertEquals(CarPlayVpnService.AttachResult.Failed("consent revoked"), attachWired(service))
            assertEquals(listOf("allow:${service.packageName}", "establish"), VpnScopeBoundary.calls)
            assertReleased(service)
        }
    }

    @Test fun rejectedWiredReplacementClosesWirelessListenerAndAllowsFreshWirelessAttach() {
        withService { service ->
            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            assertTrue(service.isAttached())
            assertTrue(service.boundPort()!! > 0)
            assertTrue("Wireless must not establish a VPN", VpnScopeBoundary.calls.isEmpty())
            val oldServer = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")

            VpnScopeBoundary.allowFailure = PackageManager.NameNotFoundException("scope rejected")
            assertEquals(CarPlayVpnService.AttachResult.Failed("scope rejected"), attachWired(service))
            assertTrue("Replacement releases the previous listener", oldServer.isClosed)
            assertEquals(listOf("allow:${service.packageName}"), VpnScopeBoundary.calls)
            assertReleased(service)

            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            assertTrue(service.isAttached())
            val newServer = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
            service.detach()
            service.detach()
            assertTrue(newServer.isClosed)
            assertReleased(service)
        }
    }

    @Test fun missingConsentStopsBeforeTunWithoutFallingBackToLwip() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(android.content.Intent("consent"))
        withService { service ->
            val result = attachWired(service, com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode.VPN)
            assertTrue(result is CarPlayVpnService.AttachResult.Failed)
            assertTrue(VpnScopeBoundary.calls.isEmpty())
            assertReleased(service)
        }
    }

    @Test
    @Config(sdk = [25])
    fun explicitVpnSnapshotUsesAdaptationAndIgnoresLaterLwipPreference() {
        org.robolectric.shadows.ShadowBuild.setModel("SABRESD_MX6DQ")
        org.robolectric.shadows.ShadowBuild.setDevice("tamago")
        withService { service ->
            val prefs = service.getSharedPreferences("pioneer-adapter", 0)
            try {
                com.shilapi.xcertplay.pioneer.PioneerWiredBackend.save(service,
                    com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode.LWIP)
                completeVpnAuthorization(service)
                assertEquals(CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"),
                    attachWired(service, com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode.VPN))
                assertEquals(com.shilapi.xcertplay.pioneer.PioneerWiredSession.Backend.VPN, VpnScopeBoundary.backendAtEstablish)
                assertFalse(com.shilapi.xcertplay.pioneer.PioneerLwipRuntime.active)
                assertNull(com.shilapi.xcertplay.pioneer.PioneerWiredSession.current)
                assertEquals(com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode.LWIP,
                    com.shilapi.xcertplay.pioneer.PioneerWiredBackend.mode(service))
                assertReleased(service)
            } finally {
                resetVpnAuthorization()
                prefs.edit().clear().commit()
            }
        }
    }

    /**
     * Production starts root authorization before attach; seed the completed attempt so this test
     * still exercises backend selection and TUN establishment itself.
     */
    private fun completeVpnAuthorization(owner: Any) {
        val runtime = com.shilapi.xcertplay.pioneer.PioneerVpnInitialization::class.java
        val attemptType = Class.forName("com.shilapi.xcertplay.pioneer.PioneerVpnInitialization\$Attempt")
        @Suppress("UNCHECKED_CAST")
        val constructor = attemptType.declaredConstructors.first { it.parameterCount == 3 }
            as java.lang.reflect.Constructor<Any>
        constructor.isAccessible = true
        val attempt = constructor.newInstance(owner, java.util.concurrent.CountDownLatch(0), null)
        runtime.getDeclaredField("attempt").apply { isAccessible = true }
            .set(runtime.getField("INSTANCE").get(null), attempt)
    }

    private fun resetVpnAuthorization() {
        val runtime = com.shilapi.xcertplay.pioneer.PioneerVpnInitialization::class.java
        runtime.getDeclaredField("attempt").apply { isAccessible = true }
            .set(runtime.getField("INSTANCE").get(null), null)
    }

    @Test fun revokeWithoutTunDoesNotStopWirelessAttachment() {
        withService { service ->
            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            service.onRevoke()
            assertTrue(service.isAttached())
            service.detach()
            assertReleased(service)
        }
    }

    @Test fun revokeClosesTunAndVehicleSessionAndReportsSpecificReason() {
        withService { service ->
            val notified = java.util.concurrent.CountDownLatch(1)
            var reason = ""
            val listener = object : AirPlaySessionListener {
                override fun onTransportError(message: String) { reason = message; notified.countDown() }
            }
            assertEquals(CarPlayVpnService.AttachResult.Started,
                service.attachWireless(InetAddress.getLoopbackAddress(), config, identity, PairingStore(), null,
                    listener, object : AirPlayMediaHandler {}))
            ReflectionHelpers.setField(service, "vpnRevokeListener", listener)
            val pipes = ParcelFileDescriptor.createPipe()
            val owner = com.shilapi.xcertplay.pioneer.PioneerWiredSession.begin(
                com.shilapi.xcertplay.pioneer.PioneerWiredSession.Backend.VPN, true)
            try {
                ReflectionHelpers.setField(service, "tun", pipes[0])
                ReflectionHelpers.setField(service, "pioneerSession", owner)
                service.onRevoke()
                assertTrue(notified.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("VPN_PERMISSION_REVOKED", reason)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (ReflectionHelpers.getField<Any?>(service, "tun") != null && System.nanoTime() < deadline)
                    Thread.yield()
                synchronized(service) {
                    assertNull(com.shilapi.xcertplay.pioneer.PioneerWiredSession.current)
                    assertReleased(service)
                }
            } finally { pipes.forEach { it.close() }; owner.close() }
        }
    }

    @Test fun revokeAfterGenericTeardownStillReportsOnceAndCannotCloseReplacement() {
        withService { service ->
            var revoked = 0
            val listener = object : AirPlaySessionListener {
                override fun onTransportError(message: String) {
                    assertEquals("VPN_PERMISSION_REVOKED", message)
                    assertFalse(service.isAttached())
                    revoked++
                }
            }
            ReflectionHelpers.setField(service, "vpnRevokeListener", listener)
            service.detach() // Generic teardown won the race before onRevoke.
            service.onRevoke()
            service.onRevoke()
            assertEquals(1, revoked)
            assertEquals(CarPlayVpnService.AttachResult.Started, attachWireless(service))
            service.onRevoke()
            assertTrue(service.isAttached())
            assertEquals(1, revoked)
        }
    }

    @Test
    @Config(sdk = [25])
    fun usbAdaptationOffIgnoresLwipSnapshotAndUsesOrdinaryVpn() {
        org.robolectric.shadows.ShadowBuild.setModel("SABRESD_MX6DQ")
        org.robolectric.shadows.ShadowBuild.setDevice("tamago")
        withService { service ->
            com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.setMode(service, "OFF")
            try {
                assertEquals(CarPlayVpnService.AttachResult.Failed("VpnService.establish returned null"),
                    attachWired(service, com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode.LWIP))
                assertEquals(listOf("allow:${service.packageName}", "establish"), VpnScopeBoundary.calls)
                assertNull(VpnScopeBoundary.backendAtEstablish)
                assertReleased(service)
            } finally {
                service.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit()
                com.shilapi.xcertplay.pioneer.CamryAdaptation.useRequestMode(
                    org.json.JSONObject().put("camryAdaptation", "AUTO"))
            }
        }
    }

    private fun withService(check: (CarPlayVpnService) -> Unit) {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try { check(controller.get()) } finally { controller.destroy() }
    }

    private fun attachWired(service: CarPlayVpnService,
        backend: com.shilapi.xcertplay.pioneer.PioneerWiredBackend.Mode? = null): CarPlayVpnService.AttachResult =
        service.attach(ncm(), "fe80::1234", ByteArray(6), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, wiredBackend = backend)

    private fun attachWireless(service: CarPlayVpnService): CarPlayVpnService.AttachResult =
        service.attachWireless(InetAddress.getLoopbackAddress(), config, identity, PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})

    private fun assertReleased(service: CarPlayVpnService) {
        assertFalse(service.isAttached())
        assertFalse(ReflectionHelpers.getField<AtomicBoolean>(service, "active").get())
        assertNull(service.boundPort())
        assertNull(ReflectionHelpers.getField<Any?>(service, "attachment"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "serverSocket"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "bridge"))
        assertNull(ReflectionHelpers.getField<Any?>(service, "tun"))
    }

    private fun ncm(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return NcmUsbBridge(connection, endpoint(0x04), endpoint(0x85), null, emptyList(), null)
    }

    private val config = AirPlayConfig("test", "00:00:00:00:00:01", "00:00:00:00:00:02",
        "1", AirPlayDisplayConfig(800, 480), port = 0)
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
}

/** No TUN can be created here: rejected scoping must stop before this boundary is reached. */
object VpnScopeBoundary {
    val calls = mutableListOf<String>()
    var allowFailure: Exception? = null
    var establishFailure: Exception? = null
    var backendAtEstablish: com.shilapi.xcertplay.pioneer.PioneerWiredSession.Backend? = null

    fun reset() {
        calls.clear()
        allowFailure = null
        establishFailure = null
        backendAtEstablish = null
    }
}

@Implements(VpnService.Builder::class)
class VpnScopeBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder

    // The Android implementation calls InetAddress.parseNumericAddress, which the host JVM lacks.
    // Control that platform plumbing while keeping scope/establish ordering in the real service.
    @Implementation fun addAddress(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun addRoute(address: String, prefixLength: Int): VpnService.Builder = builder

    @Implementation fun addAllowedApplication(packageName: String): VpnService.Builder {
        VpnScopeBoundary.calls += "allow:$packageName"
        VpnScopeBoundary.allowFailure?.let { throw it }
        return builder
    }

    @Implementation fun establish(): ParcelFileDescriptor? {
        VpnScopeBoundary.calls += "establish"
        VpnScopeBoundary.backendAtEstablish = com.shilapi.xcertplay.pioneer.PioneerWiredSession.current?.backend
        VpnScopeBoundary.establishFailure?.let { throw it }
        return null
    }
}
