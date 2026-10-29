package com.shilapi.xcertplay.installer.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

class UsbAdbBridge(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) {
    private val closed = AtomicBoolean(false)
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private var socket: Socket? = null

    val port: Int get() = server.localPort

    fun start() {
        thread(name = "adb-usb-bridge-accept", isDaemon = true) {
            try {
                val accepted = server.accept()
                socket = accepted
                thread(name = "adb-usb-bridge-tcp-usb", isDaemon = true) {
                    tcpToUsb(accepted)
                }
                thread(name = "adb-usb-bridge-usb-tcp", isDaemon = true) {
                    usbToTcp(accepted)
                }
            } catch (_: IOException) {
                close()
            }
        }
    }

    private fun tcpToUsb(accepted: Socket) {
        val buffer = ByteArray(64 * 1024)
        try {
            val input = accepted.getInputStream()
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                var offset = 0
                while (offset < read && !closed.get()) {
                    val chunk = min(read - offset, 16 * 1024)
                    val sent = connection.bulkTransfer(outEndpoint, buffer, offset, chunk, 1000)
                    if (sent <= 0) {
                        close()
                        return
                    }
                    offset += sent
                }
            }
        } catch (_: IOException) {
            close()
        }
    }

    private fun usbToTcp(accepted: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            val output = accepted.getOutputStream()
            while (!closed.get()) {
                val read = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 1000)
                if (read > 0) {
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        } catch (_: IOException) {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server.close() }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }
}
