package com.shilapi.xcertplay.installer.adb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_USB_PERMISSION = "com.shilapi.xcertplay.installer.USB_PERMISSION"
private const val LOCAL_ADB_PORT = 5555

class AdbConnectionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val includeLocalTarget: Boolean,
) {
    private val usbManager = requireNotNull(context.getSystemService(UsbManager::class.java)) {
        "USB Host 不可用"
    }

    var usbDevices by mutableStateOf<List<UsbAdbDevice>>(emptyList())
        private set
    var targets by mutableStateOf<List<AdbTarget>>(emptyList())
        private set
    var deviceDump by mutableStateOf("")
        private set
    var status by mutableStateOf("正在查找设备…")
        private set
    var log by mutableStateOf("")
        private set
    var session by mutableStateOf<AdbSession?>(null)
        private set
    var activeTarget by mutableStateOf<AdbTarget?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set

    private var pendingUsbDevice: UsbAdbDevice? = null
    private var receiver: BroadcastReceiver? = null

    fun register() {
        if (receiver != null) return
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = pendingUsbDevice
                pendingUsbDevice = null
                if (granted && device != null) {
                    startSession(AdbTarget.Usb(device))
                } else {
                    status = "没有获得设备访问权限"
                }
            }
        }
        receiver = newReceiver
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            newReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        disconnect()
    }

    fun refresh() {
        usbDevices = findAdbDevices(context)
        deviceDump = describeUsbDevices(context)
        targets = buildList {
            addAll(usbDevices.map { AdbTarget.Usb(it) })
            if (includeLocalTarget) {
                add(AdbTarget.Tcp("127.0.0.1", LOCAL_ADB_PORT))
            }
        }
        status = if (usbDevices.isNotEmpty()) {
            "发现 ${usbDevices.size} 个可用设备"
        } else if (includeLocalTarget) {
            "没有发现通过数据线连接的设备；可以尝试连接这台手机"
        } else {
            "没有发现通过数据线连接的设备"
        }
    }

    fun connect(target: AdbTarget) {
        when (target) {
            is AdbTarget.Tcp -> startSession(target)
            is AdbTarget.Usb -> {
                if (usbManager.hasPermission(target.device.usbDevice)) {
                    startSession(target)
                } else {
                    pendingUsbDevice = target.device
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                    val permissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                    val pendingIntent = PendingIntent.getBroadcast(context, 0, permissionIntent, flags)
                    usbManager.requestPermission(target.device.usbDevice, pendingIntent)
                    status = "等待确认…"
                }
            }
        }
    }

    fun disconnect() {
        session?.close()
        session = null
        activeTarget = null
        status = "已断开"
    }

    fun appendLog(text: String) {
        log = if (log.isBlank()) text else "$log\n\n$text"
    }

    fun updateStatus(text: String) {
        status = text
    }

    private fun startSession(target: AdbTarget) {
        if (connecting) return
        connecting = true
        status = when (target) {
            is AdbTarget.Usb -> "正在连接 ${target.device.label}…"
            is AdbTarget.Tcp -> "正在连接这台手机…"
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val newSession: AdbSession = when (target) {
                        is AdbTarget.Usb -> UsbAdbSession(context, target.device)
                        is AdbTarget.Tcp -> TcpAdbSession(context, target.host, target.port)
                    }
                    val info = newSession.connect()
                    newSession to info
                }
            }
            result.onSuccess { (newSession, info) ->
                session = newSession
                activeTarget = target
                status = "已连接：${target.title}"
                appendLog(info)
            }.onFailure { error ->
                status = "连接失败：${error.message ?: error.javaClass.simpleName}"
                appendLog("连接失败：\n${error.stackTraceToString()}")
            }
            connecting = false
        }
    }
}

@Composable
fun rememberAdbConnectionController(
    includeLocalTarget: Boolean = true,
): AdbConnectionController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(includeLocalTarget) {
        AdbConnectionController(context.applicationContext, scope, includeLocalTarget)
    }
    DisposableEffect(controller) {
        controller.register()
        onDispose { controller.unregister() }
    }
    LaunchedEffect(controller) {
        controller.refresh()
    }
    return controller
}
