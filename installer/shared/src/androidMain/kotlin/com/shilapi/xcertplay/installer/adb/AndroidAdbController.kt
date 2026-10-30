package com.shilapi.xcertplay.installer.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ACTION_USB_PERMISSION = "com.shilapi.xcertplay.installer.USB_PERMISSION"
private const val LOCAL_ADB_PORT = 5555

class AndroidAdbController(
    private val context: Context,
) : AdbController {
    private val usbManager = requireNotNull(context.getSystemService(UsbManager::class.java)) {
        "USB Host 不可用"
    }
    private val includeLocalTarget =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<AdbDeviceInfo>>(emptyList())
    override val devices: StateFlow<List<AdbDeviceInfo>> = _devices.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    override val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private val _status = MutableStateFlow("正在查找设备…")
    override val status: StateFlow<String> = _status.asStateFlow()

    private val _log = MutableStateFlow("")
    override val log: StateFlow<String> = _log.asStateFlow()

    private val _session = MutableStateFlow<AdbSession?>(null)
    override val session: StateFlow<AdbSession?> = _session.asStateFlow()

    private val _activeDevice = MutableStateFlow<AdbDeviceInfo?>(null)
    override val activeDevice: StateFlow<AdbDeviceInfo?> = _activeDevice.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    override val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val usbDevices = mutableMapOf<String, UsbAdbDevice>()
    private var pendingDevice: UsbAdbDevice? = null
    private var pendingInfo: AdbDeviceInfo? = null
    private var receiver: BroadcastReceiver? = null

    override fun register() {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = pendingDevice
                val info = pendingInfo
                pendingDevice = null
                pendingInfo = null
                if (granted && device != null && info != null) {
                    startSession(info, UsbAdbSession(context, device))
                } else {
                    _status.value = "没有获得设备访问权限"
                }
            }
        }
        receiver = newReceiver
        ContextCompat.registerReceiver(
            context,
            newReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        disconnect()
        scope.cancel()
    }

    override fun refresh() {
        val found = findAdbDevices(context)
        usbDevices.clear()
        val targets = found.mapIndexed { index, device ->
            val id = "usb:${device.usbDevice.deviceName}:$index"
            usbDevices[id] = device
            AdbDeviceInfo(
                id = id,
                title = device.label,
                subtitle = "通过 OTG 数据线连接",
                transport = AdbTransport.Usb,
            )
        }.toMutableList()

        if (includeLocalTarget) {
            targets += AdbDeviceInfo(
                id = "tcp:127.0.0.1:$LOCAL_ADB_PORT",
                title = "这台手机",
                subtitle = "无需数据线",
                transport = AdbTransport.Tcp,
            )
        }

        _devices.value = targets
        _diagnostics.value = describeUsbDevices(context)
        _status.value = when {
            found.isNotEmpty() -> "发现 ${found.size} 个可用设备"
            includeLocalTarget -> "没有发现通过数据线连接的设备；可以尝试连接这台手机"
            else -> "没有发现通过数据线连接的设备"
        }
    }

    override fun connect(device: AdbDeviceInfo) {
        when (device.transport) {
            AdbTransport.Tcp -> startSession(
                device,
                TcpAdbSession(context, "127.0.0.1", LOCAL_ADB_PORT),
            )

            AdbTransport.Usb -> {
                val usbDevice = usbDevices[device.id] ?: return
                if (usbManager.hasPermission(usbDevice.usbDevice)) {
                    startSession(device, UsbAdbSession(context, usbDevice))
                } else {
                    pendingDevice = usbDevice
                    pendingInfo = device
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                    val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                    val pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags)
                    usbManager.requestPermission(usbDevice.usbDevice, pendingIntent)
                    _status.value = "等待确认…"
                }
            }
        }
    }

    override fun connectAddress(address: String) {
        val normalized = if (address.contains(":")) address else "$address:5555"
        val host = normalized.substringBeforeLast(":")
        val port = normalized.substringAfterLast(":").toIntOrNull() ?: LOCAL_ADB_PORT
        if (!host.matches(Regex("[A-Za-z0-9._-]+"))) {
            _status.value = "地址格式不正确"
            return
        }
        startSession(
            AdbDeviceInfo(
                id = normalized,
                title = normalized,
                subtitle = "无线 ADB",
                transport = AdbTransport.Tcp,
            ),
            TcpAdbSession(context, host, port),
        )
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

    private fun startSession(device: AdbDeviceInfo, newSession: AdbSession) {
        if (_connecting.value) return
        _connecting.value = true
        _status.value = "正在连接 ${device.title}…"

        scope.launch {
            val result = runCatching { newSession.connect() }
            result.onSuccess { info ->
                _session.value = newSession
                _activeDevice.value = device
                _status.value = "已连接：${device.title}"
                appendLog(info)
            }.onFailure { error ->
                runCatching { newSession.close() }
                _status.value = "连接失败：${error.message ?: error.javaClass.simpleName}"
                appendLog("连接失败：\n${error.stackTraceToString()}")
            }
            _connecting.value = false
        }
    }
}
