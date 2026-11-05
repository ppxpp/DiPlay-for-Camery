package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import java.security.SecureRandom

/** Reuses system credentials, with an app-private backup that survives system AP configuration loss. */
internal object PioneerHotspotConfiguration {
    private val random = SecureRandom()
    private const val PREFERENCES = "pioneer-hotspot-credentials"
    internal data class Credentials(val ssid: String, val passphrase: String)
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    @Synchronized
    fun ensure(context: Context, wifi: WifiManager): WifiConfiguration {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return ensure(
            read = { wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration },
            state = { wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int },
            write = { config ->
                // Verified firmware enforces CHANGE_WIFI_STATE and persists via WifiApConfigStore.
                wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
                    .invoke(wifi, config) != false
            },
            loadSaved = { Credentials(preferences.getString("ssid", "").orEmpty(),
                preferences.getString("passphrase", "").orEmpty()) },
            save = { credentials ->
                if (preferences.getString("ssid", null) != credentials.ssid ||
                    preferences.getString("passphrase", null) != credentials.passphrase) {
                    // Commit the pair before changing the system AP so retry/power loss won't generate another pair.
                    check(preferences.edit().putString("ssid", credentials.ssid)
                        .putString("passphrase", credentials.passphrase).commit()) {
                        "Could not persist vehicle hotspot credentials"
                    }
                }
            },
        )
    }

    internal fun ensure(
        read: () -> WifiConfiguration?,
        state: () -> Int,
        write: (WifiConfiguration) -> Boolean,
        loadSaved: () -> Credentials? = { null },
        save: (Credentials) -> Unit = {},
    ): WifiConfiguration {
        val current = read()
        if (valid(current)) {
            // Respect valid changes made in the car settings and synchronize the backup.
            save(Credentials(checkNotNull(current).SSID, current.preSharedKey))
            return current
        }
        check(state() == 11) { "Stop the vehicle hotspot before initializing its configuration" }
        val credentials = loadSaved()?.takeIf { valid(it.ssid, it.passphrase) }
            ?: Credentials("Camery-" + token(4), token(16))
        save(credentials)
        val created = WifiConfiguration().apply {
            SSID = credentials.ssid
            preSharedKey = credentials.passphrase
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
        return valid(ssid, password)
    }

    private fun valid(ssid: String, password: String): Boolean =
        ssid.isNotBlank() && '\u0000' !in ssid && ssid.toByteArray(Charsets.UTF_8).size <= 32 &&
            password.length in 8..63 && password.all { it.code in 32..126 }

    private fun token(length: Int) = buildString {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
