package com.shilapi.xcertplay.installer.adb

import android.content.Context
import com.cgutman.adblib.AdbConnection
import java.net.InetSocketAddress
import java.net.Socket

class TcpAdbSession(
    context: Context,
    private val host: String,
    private val port: Int,
) : BaseAdbSession(context) {
    private var socket: Socket? = null

    override fun connect(): String {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 3000)
        this.socket = socket

        try {
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
        socket = null
    }
}
