package com.shilapi.xcertplay.installer.adb

import kotlinx.coroutines.flow.StateFlow

enum class AdbTransport { Usb, Tcp }

data class AdbDeviceInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val transport: AdbTransport,
    val state: String = "device",
    val model: String? = null,
) {
    val isAuthorized: Boolean get() = state == "device"
}

interface AdbController {
    val devices: StateFlow<List<AdbDeviceInfo>>
    val diagnostics: StateFlow<String>
    val status: StateFlow<String>
    val log: StateFlow<String>
    val session: StateFlow<AdbSession?>
    val activeDevice: StateFlow<AdbDeviceInfo?>
    val connecting: StateFlow<Boolean>

    fun register()
    fun unregister()
    fun refresh()
    fun connect(device: AdbDeviceInfo)
    fun connectAddress(address: String)
    fun disconnect()
    fun appendLog(text: String)
    fun updateStatus(text: String)
}
