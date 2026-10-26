package com.shilapi.xcertplay.vehicle.adapters.pioneer

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.shilapi.xcertplay.media.AudioChannel
import com.shilapi.xcertplay.vehicle.api.AudioRouteController
import com.shilapi.xcertplay.vehicle.api.DefaultAudioRouteController

/** Pioneer firmware encodes both its output bus and MediaManager token in contentType. */
internal object PioneerAudioRouteAdapter : AudioRouteController {
    override val available: Boolean get() =
        com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled() && capabilitiesAvailable

    private val capabilitiesAvailable: Boolean by lazy {
        runCatching {
            AudioManager::class.java.getField("STREAM_TAI_WECARFLOW_MEDIA").getInt(null) == 401 &&
                AudioManager::class.java.getField("STREAM_AMAP_NAVI_GUIDE").getInt(null) == 400 &&
                AudioTrack::class.java.getMethod("setTokenName", String::class.java) != null
        }.getOrDefault(false)
    }

    override fun streamType(channel: AudioChannel): Int? = if (!available) null else when (channel) {
        AudioChannel.MEDIA -> 401 // FRONT bus, com.tencent.wecarflow.media token
        AudioChannel.PHONE -> com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.current().callStream // Native WeChat VoIP route on GUANGFENG_819.
        else -> 400 // MIX bus, same navigation token/path as Amap
    }

    override fun focusStream(attributes: AudioAttributes): Int? =
        attributes.contentType.takeIf { available && it in 400..402 }

    override val nonBlockingWrites: Boolean get() = available

    override fun independentSpeechFocus(attributes: AudioAttributes): Boolean =
        focusStream(attributes) == 400

    override fun acceptsLegacyStream(stream: Int): Boolean =
        DefaultAudioRouteController.acceptsLegacyStream(stream) || (available && stream in 400..402)

    /** Audio focus alone cannot switch from FM/BT to TAI_AUDIO on this firmware. */
    override fun selectMediaSource(report: (String) -> Unit) {
        if (!available) return
        runCatching {
            val controllerClass = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            val controller = controllerClass.getMethod("getInstance").invoke(null)
            PioneerMediaSourceGate.withSelectedSource(controller, report) { }
        }.onFailure {
            report("Audio: Pioneer media source selection failed: ${it.javaClass.simpleName}")
        }
    }

}
