package com.shilapi.xcertplay.media

import android.os.Build
import android.Manifest
import android.media.AudioRecord
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import jp.pioneer.ceam.MediaManager.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerMicrophoneLeaseTest {
    private val controller get() = MediaController.getInstance()
    private val leases = mutableListOf<PioneerMicrophoneLease>()

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "SABRESD-MX6DQ")
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", "tamago_3T_uart")
        PioneerLwipRuntime.active = false
        PioneerWiredSession.current?.close()
        controller.reset()
    }

    @After fun tearDown() {
        leases.forEach { it.close() }
        PioneerLwipRuntime.active = false
        PioneerWiredSession.current?.close()
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.command(app, org.json.JSONObject().put("action", "RESET"))
    }

    private fun acquire(): PioneerMicrophoneLease? =
        PioneerMicrophoneLease.acquire("test.diplay", {}).also { if (it != null) leases.add(it) }

    @Test fun nativeNcmRequestsAndReleasesTheVehicleMicrophone() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        val lease = acquire()
        assertNotNull("Native NCM calls must request the Pioneer microphone", lease)
        assertEquals(1, controller.requests)
        assertEquals("test.diplay", controller.token)
        lease!!.close()
        lease.close()
        assertEquals(1, controller.releases)
        assertNull(controller.token)
    }

    @Test fun lwipStillRequestsTheVehicleMicrophone() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.LWIP)
        assertNotNull(acquire())
        assertEquals(1, controller.requests)
    }

    @Test fun allBackendsAndInputTypesCaptureWithTheVendorGateAndMicSource() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        for (backend in PioneerWiredSession.Backend.entries) {
            for (audioType in listOf("telephony", "speechrecognition", "default", "compatibility")) {
                controller.reset()
                val started = CountDownLatch(1)
                val recorder = AtomicReference<AudioRecord?>()
                ShadowAudioRecord.setSourceProvider { record ->
                    recorder.set(record)
                    started.countDown()
                    object : ShadowAudioRecord.AudioRecordSource {
                        override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                            Thread.sleep(5)
                            return 0
                        }
                    }
                }
                try {
                    PioneerWiredSession.begin(backend).use {
                        val config = MicrophoneConfig(audioType = audioType, sampleRate = 16_000,
                            channels = 1, payloadType = 100, frameMillis = 20,
                            host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32))
                        MicrophoneUplink(config, applicationPackage = context.packageName).use { uplink ->
                            assertTrue("$backend/$audioType capture must start", uplink.start())
                            assertTrue(started.await(5, TimeUnit.SECONDS))
                            assertEquals(1, controller.requests)
                            assertEquals(MediaRecorder.AudioSource.MIC, recorder.get()!!.audioSource)
                        }
                        assertEquals(1, controller.releases)
                    }
                } finally {
                    ShadowAudioRecord.clearSource()
                }
            }
        }
    }

    @Test fun sourceTransitionMustFinishBeforeMicGrantAndLaterPlaybackMustNotResetIt() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN)
        controller.source = 0
        val lease = checkNotNull(acquire())
        assertEquals(300, controller.source)
        assertEquals("test.diplay", controller.token)
        assertEquals(1, controller.sourceChanges)
        com.shilapi.xcertplay.vehicle.adapters.pioneer.PioneerMediaSourceGate.withSelectedSource(controller, {}) { }
        assertEquals("test.diplay", controller.token)
        assertEquals(1, controller.sourceChanges)
        lease.close()
        assertEquals(1, controller.releases)
    }

    @Test fun failedAppRecordingStartupReleasesItsMicrophoneLease() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        val config = MicrophoneConfig(audioType = "default", sampleRate = 16_000,
            channels = 1, payloadType = 100, frameMillis = 20,
            host = InetAddress.getLoopbackAddress(), port = 9, key = ByteArray(32),
            codec = com.shilapi.xcertplay.airplay.AudioCodecKind.OPUS)
        MicrophoneUplink(config, applicationPackage = "test.diplay", opusEncoderFactory = { null }).use {
            assertFalse(it.start())
            assertEquals(1, controller.requests)
            assertEquals(1, controller.releases)
            assertNull(controller.token)
        }
        assertEquals(1, controller.releases)
    }

    @Test fun inactiveAndOtherHeadUnitsDoNotRequestTheVehicleMicrophone() {
        assertNull(acquire())
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Other")
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        assertNull(acquire())
        assertEquals(0, controller.requests)
    }

    @Test fun overlappingStreamsReleaseOnlyAfterTheLastUser() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        val first = checkNotNull(acquire())
        val second = checkNotNull(acquire())
        first.close()
        assertEquals(0, controller.releases)
        second.close()
        assertEquals(1, controller.releases)
    }

    @Test fun changedOwnerIsNeverReleased() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        val lease = checkNotNull(acquire())
        controller.token = "another.app"
        lease.close()
        assertEquals(0, controller.releases)
        assertEquals("another.app", controller.token)
    }

    @Test fun restoringMicrophoneMustPreserveActivePhoneSource() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
        com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.command(app, org.json.JSONObject().put("action", "SET")
            .put("configuration", org.json.JSONObject().put("testId", "preserve-phone-source").put("callSourcePolicy", "PRESERVE_PHONE")))
        PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN)
        val lease = checkNotNull(acquire())
        controller.source = 302
        controller.token = "com.tencent.mm"
        assertTrue(lease.restoreOwnership())
        assertEquals(302, controller.source)
        assertEquals(0, controller.sourceChanges)
        assertEquals("test.diplay", controller.token)
    }

    @Test fun activeCallCanRestoreOwnershipAfterVendorPlaybackChangesIt() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN)
        val lease = checkNotNull(acquire())
        controller.token = "com.tencent.mm"
        controller.source = 0
        assertTrue(lease.restoreOwnership())
        assertEquals(300, controller.source)
        assertEquals(1, controller.sourceChanges)
        assertEquals("test.diplay", controller.token)
        lease.close()
        assertFalse(lease.restoreOwnership())
        assertEquals(1, controller.releases)
    }

    @Test fun interruptedSecondAcquisitionKeepsExistingUsersMicrophone() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN)
        val first = checkNotNull(acquire())
        try {
            Thread.currentThread().interrupt()
            acquire()
            fail("Interrupted acquisition must fail")
        } catch (_: InterruptedException) {
            assertEquals(0, controller.releases)
            assertEquals("test.diplay", controller.token)
        } finally {
            Thread.interrupted()
        }
        first.close()
        assertEquals(1, controller.releases)
    }

    @Test fun refusedVehicleRequestFailsInsteadOfSilentlyUsingAnUnopenedMic() {
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM)
        controller.grant = false
        try {
            acquire()
            fail("Vehicle refusal must fail capture startup")
        } catch (_: IllegalStateException) {
            assertEquals(1, controller.requests)
            assertEquals(0, controller.releases)
        }
    }
}
