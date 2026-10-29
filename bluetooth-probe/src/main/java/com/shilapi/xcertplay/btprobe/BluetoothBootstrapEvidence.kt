package com.shilapi.xcertplay.btprobe

/** Protocol delivery and actual Wi-Fi takeover are deliberately separate facts. */
internal data class BluetoothBootstrapEvidence(
    val linkReady: Boolean,
    val authenticatedAndSubscribed: Boolean,
    val wifiConfigurationsSent: Int,
    val startSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportConfigurationsSent: Int,
    val wirelessAvailableSeen: Boolean,
    val wifiRuntimeAuthenticated: Boolean,
) {
    val bluetoothControlComplete: Boolean get() = linkReady && authenticatedAndSubscribed &&
        wifiConfigurationsSent > 0 && startSessionsSent > 0 && transportNotificationSeen &&
        postTransportConfigurationsSent > 0 && wirelessAvailableSeen
    val bluetoothWorkVerified: Boolean get() = bluetoothControlComplete && wifiRuntimeAuthenticated
}
