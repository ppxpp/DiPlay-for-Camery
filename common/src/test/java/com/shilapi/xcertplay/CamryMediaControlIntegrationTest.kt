package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.pioneer.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CamryMediaControlIntegrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private lateinit var phone: AirPlaySession
    private lateinit var queue: ExecutorService
    private val release = CountDownLatch(1)
    @Before fun setup() {
        app.getSharedPreferences("pioneer-media-control", 0).edit().clear().commit()
        PioneerUsbPolicy.setMode(app, "ON")
        controller = CarPlayController(app, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        phone = mock(AirPlaySession::class.java)
        controller.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, phone)
        queue = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }.get(controller) as ExecutorService
    }
    @After fun cleanup() {
        release.countDown(); controller.close(); controller.awaitClosed(2000)
        PioneerUsbPolicy.setMode(app, "AUTO")
        app.getSharedPreferences("pioneer-media-control", 0).edit().clear().commit()
    }
    private fun drain() { queue.submit {}.get(3, TimeUnit.SECONDS) }
    @Test fun defaultOnAndLiveToggleOnlyConsumesCompanionPlayPause() {
        assertTrue(PioneerMediaControlSettings.acceptsPlayback(app))
        assertTrue(controller.sendMediaButton(CarPlayMediaButton.PLAY, "vehicle_companion")); drain()
        verify(phone).sendMedia(CarPlayMediaButton.PLAY); clearInvocations(phone)
        PioneerMediaControlSettings.setAcceptsPlayback(app, false)
        for (key in listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE)) {
            assertTrue(controller.sendMediaButton(key, "vehicle_companion"))
        }
        for (key in listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS)) {
            assertTrue(controller.sendMediaButton(key, "vehicle_companion"))
        }
        assertTrue(controller.sendMediaButton(CarPlayMediaButton.PAUSE, "ui")); drain()
        verify(phone, never()).sendMedia(CarPlayMediaButton.PLAY)
        verify(phone).sendMedia(CarPlayMediaButton.PAUSE)
        verify(phone).sendMedia(CarPlayMediaButton.NEXT); verify(phone).sendMedia(CarPlayMediaButton.PREVIOUS)
        clearInvocations(phone)
        PioneerMediaControlSettings.setAcceptsPlayback(app, true); drain()
        verifyNoInteractions(phone) // previously consumed commands are not saved or replayed
        controller.sendMediaButton(CarPlayMediaButton.PLAY, "vehicle_companion"); drain()
        verify(phone).sendMedia(CarPlayMediaButton.PLAY)
    }
    @Test fun queuedPlaybackRechecksLiveSettingAndNeverReplaysAfterReenable() {
        val entered = CountDownLatch(1)
        queue.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        controller.sendMediaButton(CarPlayMediaButton.PLAY, "vehicle_companion")
        controller.sendMediaButton(CarPlayMediaButton.NEXT, "vehicle_companion")
        PioneerMediaControlSettings.setAcceptsPlayback(app, false)
        release.countDown(); drain()
        verify(phone, never()).sendMedia(CarPlayMediaButton.PLAY)
        verify(phone).sendMedia(CarPlayMediaButton.NEXT); clearInvocations(phone)
        PioneerMediaControlSettings.setAcceptsPlayback(app, true); drain(); verifyNoInteractions(phone)
    }
    @Test fun adaptationOffPreservesNormalCompanionPlaybackAndClosedControllerRejects() {
        PioneerMediaControlSettings.setAcceptsPlayback(app, false)
        PioneerUsbPolicy.setMode(app, "OFF")
        controller.sendMediaButton(CarPlayMediaButton.PAUSE, "vehicle_companion"); drain()
        verify(phone).sendMedia(CarPlayMediaButton.PAUSE)
        controller.close()
        assertFalse(controller.sendMediaButton(CarPlayMediaButton.PLAY, "vehicle_companion"))
    }
}
