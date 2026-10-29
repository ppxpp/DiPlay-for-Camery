package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import java.security.SecureRandom

/** Initializes credentials once, before either Bluetooth identification or AP creation uses them. */
internal object PioneerHotspotConfiguration {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    @Synchronized
    fun ensure(wifi: WifiManager): WifiConfiguration = ensure(
        read = { wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration },
        state = { wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int },
        write = { config ->
            // Verified firmware enforces CHANGE_WIFI_STATE and persists via WifiApConfigStore.
            wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                .invoke(wifi, config) != false
        },
    )

    internal fun ensure(
        read: () -> WifiConfiguration?,
        state: () -> Int,
        write: (WifiConfiguration) -> Boolean,
    ): WifiConfiguration {
        val current = read()
        if (valid(current)) return checkNotNull(current)
        check(state() == 11) { "Stop the vehicle hotspot before initializing its configuration" }
        val created = WifiConfiguration().apply {
            SSID = "Camery-" + token(4)
            preSharedKey = token(16)
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
        }
        check(write(created)) { "Vehicle hotspot configuration was rejected" }
        val saved = read()
        check(saved != null && valid(saved) && saved.SSID == created.SSID && saved.preSharedKey == created.preSharedKey) {
            "Vehicle hotspot configuration could not be confirmed"
        }
        return checkNotNull(saved)
    }

    private fun valid(config: WifiConfiguration?): Boolean {
        val ssid = config?.SSID ?: return false
        val password = config.preSharedKey ?: return false
        return ssid.isNotBlank() && '\u0000' !in ssid && ssid.toByteArray(Charsets.UTF_8).size <= 32 &&
            password.length in 8..63 && password.all { it.code in 32..126 }
    }

    private fun token(length: Int) = buildString {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
