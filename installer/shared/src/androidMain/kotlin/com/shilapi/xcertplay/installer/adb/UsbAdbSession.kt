package com.shilapi.xcertplay.installer.adb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.cgutman.adblib.AdbConnection
import java.net.InetSocketAddress
import java.net.Socket

class UsbAdbSession(
    context: Context,
    private val device: UsbAdbDevice,
) : BaseAdbSession(context) {
    private var usbConnection: UsbDeviceConnection? = null
    private var bridge: UsbAdbBridge? = null
    private var socket: Socket? = null

    override fun connect(): String {
        val manager = context.getSystemService(UsbManager::class.java)
        check(manager.hasPermission(device.usbDevice)) { "USB 权限未授予" }

        val opened = manager.openDevice(device.usbDevice)
            ?: error("无法打开 USB 设备")
        if (!opened.claimInterface(device.usbInterface, true)) {
            opened.close()
            error("无法占用 ADB 接口")
        }
        usbConnection = opened

        try {
            val bridge = UsbAdbBridge(
                connection = opened,
                usbInterface = device.usbInterface,
                inEndpoint = device.inEndpoint,
                outEndpoint = device.outEndpoint,
            )
            bridge.start()
            this.bridge = bridge

            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", bridge.port), 3000)
            this.socket = socket

            val connection = AdbConnection.create(socket, loadCrypto())
            connection.connect()
            return attach(connection)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        super.close()
        runCatching { socket?.close() }
        runCatching { bridge?.close() }
        runCatching { usbConnection?.close() }
        socket = null
        bridge = null
        usbConnection = null
    }
}
