package com.shilapi.xcertplay.installer.adb

import com.shilapi.xcertplay.installer.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class DesktopAdbController(
    private val platform: PlatformContext,
) : AdbController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binary: File? = DesktopAdbPaths.resolve()
    private val environment: Map<String, String> = emptyMap()
    private val adbServerPort = System.getenv("ANDROID_ADB_SERVER_PORT")?.toIntOrNull() ?: 5037
    private val ownsServer = !isServerRunning()

    private val _devices = MutableStateFlow<List<AdbDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<AdbDeviceInfo>> = _devices.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    override val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _status = MutableStateFlow(
        if (binary == null) "未找到 adb，请使用打包版或设置 ADB_PATH" else "正在查找设备…",
    )
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow("")
    override val log: StateFlow<String> = _log.asStateFlow()

    private val _session = MutableStateFlow<AdbSession?>(null)
    override val session: StateFlow<AdbSession?> = _session.asStateFlow()

    private val _activeDevice = MutableStateFlow<AdbDeviceInfo?>(null)
    override val activeDevice: StateFlow<AdbDeviceInfo?> = _activeDevice.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    override val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    override fun register() = Unit

    override fun unregister() {
        disconnect()
        if (ownsServer) {
            binary?.let { adb ->
                runCatching {
                    AdbExec(adb, environment).run("kill-server", timeoutSeconds = 10)
                }
            }
        }
        scope.cancel()
    }

    private fun isServerRunning(): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", adbServerPort), 200)
            true
        }
    }.getOrDefault(false)

    override fun refresh() {
        scope.launch { refreshInternal() }
    }

    private suspend fun refreshInternal() {
        val adb = binary
        if (adb == null) {
            _devices.value = emptyList()
            _diagnostics.value = ""
            _status.value = "未找到 adb，请使用打包版或设置 ADB_PATH"
            return
        }
        val first = runCatching {
            AdbExec(adb, environment).run("devices", "-l", timeoutSeconds = 30)
        }
        var text = first.getOrNull()
        if (text != null && (text.contains("error:") || text.contains("protocol fault"))) {
            delay(800)
            text = runCatching {
                AdbExec(adb, environment).run("devices", "-l", timeoutSeconds = 30)
            }.getOrNull() ?: text
        }

        if (text != null) {
            _diagnostics.value = text
            val devices = parseDevices(text)
            _devices.value = devices
            val unauthorized = devices.count { it.state == "unauthorized" }
            _status.value = when {
                devices.isEmpty() -> "没有发现设备；请检查 USB 驱动或使用无线 ADB"
                unauthorized > 0 -> "$unauthorized 个设备未授权，请在车机上允许 USB 调试"
                else -> "发现 ${devices.size} 个设备"
            }
        } else {
            val error = first.exceptionOrNull()
            _diagnostics.value = error?.message.orEmpty()
            _devices.value = emptyList()
            _status.value = "查找设备失败：${error?.message ?: "未知错误"}"
        }
    }

    override fun connect(device: AdbDeviceInfo) {
        val adb = binary ?: return
        if (device.state != "device") {
            _status.value = "设备未就绪（${device.state}），请先在车机上允许 USB 调试"
            return
        }
        if (_connecting.value) return
        _connecting.value = true
        _status.value = "正在连接 ${device.title}…"

        scope.launch {
            if (device.transport == AdbTransport.Tcp) {
                runCatching {
                    AdbExec(adb, environment).run("connect", device.id, timeoutSeconds = 30)
                }.onFailure {
                    _status.value = "无线连接失败：${it.message}"
                    _connecting.value = false
                    return@launch
                }
            }

            val session = CliAdbSession(adb, device.id, environment)
            val result = runCatching { session.connect() }
            result.onSuccess { info ->
                _session.value = session
                _activeDevice.value = device
                _status.value = "已连接：${device.title}"
                appendLog(info)
            }.onFailure { error ->
                runCatching { session.close() }
                _status.value = "连接失败：${error.message ?: error.javaClass.simpleName}"
                appendLog("连接失败：\n${error.stackTraceToString()}")
            }
            _connecting.value = false
        }
    }

    override fun connectAddress(address: String) {
        val adb = binary ?: return
        val normalized = if (address.contains(":")) address else "$address:5555"
        val host = normalized.substringBeforeLast(":")
        val port = normalized.substringAfterLast(":").toIntOrNull()
        if (!host.matches(Regex("[A-Za-z0-9._-]+")) || port == null || port !in 1..65535) {
            _status.value = "无线地址格式不正确，请使用 IP:端口"
            return
        }

        scope.launch {
            _status.value = "正在连接 $normalized…"
            val output = runCatching {
                AdbExec(adb, environment).run("connect", normalized, timeoutSeconds = 30)
            }.getOrElse { error ->
                _status.value = "无线连接失败：${error.message}"
                return@launch
            }
            appendLog("$ adb connect $normalized\n${output.trim()}")
            refreshInternal()
            val device = _devices.value.firstOrNull { it.id == normalized }
            if (device != null) {
                connect(device)
            } else {
                _status.value = output.trim().ifBlank { "未发现无线设备" }
            }
        }
    }

    override fun disconnect() {
        _session.value?.close()
        _session.value = null
        _activeDevice.value = null
        _status.value = "已断开"
    }

    override fun appendLog(text: String) {
        _log.update { current -> if (current.isBlank()) text else "$current\n\n$text" }
    }

    override fun updateStatus(text: String) {
        _status.value = text
    }

    private fun parseDevices(text: String): List<AdbDeviceInfo> =
        text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !line.startsWith("List of devices") &&
                    !line.startsWith("*") &&
                    !line.startsWith("adb server") &&
                    !line.startsWith("error:") &&
                    !line.startsWith("adb:")
            }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val state = parts[1]
                if (state !in KNOWN_DEVICE_STATES) return@mapNotNull null
                val model = parts.drop(2)
                    .firstOrNull { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.replace('_', ' ')
                val tcp = serial.contains(":")
                AdbDeviceInfo(
                    id = serial,
                    title = model?.takeIf { it.isNotBlank() } ?: serial,
                    subtitle = if (tcp) "无线 ADB" else "USB 数据线",
                    transport = if (tcp) AdbTransport.Tcp else AdbTransport.Usb,
                    state = state,
                    model = model,
                )
            }
            .toList()

    private companion object {
        val KNOWN_DEVICE_STATES = setOf(
            "device",
            "offline",
            "unauthorized",
            "bootloader",
            "recovery",
            "sideload",
            "rescue",
            "host",
        )
    }
}
