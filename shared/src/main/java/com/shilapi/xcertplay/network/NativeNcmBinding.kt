package com.shilapi.xcertplay.network

import android.net.Network
import java.net.*
import java.util.Arrays

/** b819 native transport contract, shared by every control/media socket. */
class NativeNcmBinding(localAddress: Inet6Address, val interfaceIndex: Int, val network: Network? = null) {
    val localAddress: Inet6Address
    init {
        require(interfaceIndex > 0 && localAddress.isLinkLocalAddress) { "Native NCM requires a link-local source and positive interface index" }
        require(localAddress.scopeId == 0 || localAddress.scopeId == interfaceIndex) { "Native NCM source belongs to another interface" }
        this.localAddress = Inet6Address.getByAddress(null, localAddress.address, interfaceIndex)
    }
    fun peer(address: InetAddress): Inet6Address {
        if (address !is Inet6Address || !address.isLinkLocalAddress) throw SocketException("Native NCM peer must be link-local IPv6")
        if (address.scopeId != 0 && address.scopeId != interfaceIndex) throw SocketException("Native NCM peer belongs to another interface")
        return Inet6Address.getByAddress(null, address.address, interfaceIndex)
    }
    fun peerEndpoint(address: InetAddress, port: Int) = InetSocketAddress(peer(address), port)
    fun validatePeer(address: InetAddress, expected: InetAddress? = null): Inet6Address = peer(address).also {
        if (expected != null && !it.address.contentEquals(peer(expected).address)) throw SocketException("Native NCM peer differs from control session")
    }
    fun validateAccepted(socket: Socket, expected: InetAddress? = null) = closeOnFailure(socket) {
        val source = socket.localAddress
        if (source !is Inet6Address || !source.address.contentEquals(localAddress.address) ||
            source.scopeId != 0 && source.scopeId != interfaceIndex) throw SocketException("Native NCM accepted socket uses another local interface")
        validatePeer(socket.inetAddress, expected)
        Unit
    }
    fun bind(socket: ServerSocket, port: Int = 0) = closeOnFailure(socket) { socket.bind(InetSocketAddress(localAddress, port)) }
    fun bind(socket: DatagramSocket, port: Int = 0) = closeOnFailure(socket) {
        check(!socket.isBound && !socket.isConnected) { "Native NCM UDP socket already bound" }
        network?.bindSocket(socket)
        socket.bind(InetSocketAddress(localAddress, port))
    }
    fun bind(socket: Socket) = closeOnFailure(socket) {
        check(!socket.isBound && !socket.isConnected) { "Native NCM TCP socket already bound" }
        network?.bindSocket(socket)
        socket.bind(InetSocketAddress(localAddress, 0))
    }

    /**
     * Proves that the app UID can select this NCM interface before CarPlay starts. UDP connect()
     * installs no peer state and sends no packet; it only exercises the kernel route and the
     * ConnectivityManager network binding used by the real channels.
     */
    fun verifyRoute() {
        DatagramSocket(null).use { socket ->
            network?.bindSocket(socket)
            socket.bind(InetSocketAddress(localAddress, 0))
            socket.connect(InetSocketAddress(routeProbe(localAddress), 9))
            val selected = socket.localAddress
            if (selected !is Inet6Address || !selected.address.contentEquals(localAddress.address))
                throw SocketException("Native NCM route selected another local IPv6 address")
        }
    }

    private inline fun <T> closeOnFailure(socket: java.io.Closeable, action: () -> T): T = try { action() }
        catch (error: Throwable) { runCatching { socket.close() }.onFailure(error::addSuppressed); throw error }

    companion object {
        /** b819 route probe: fe80::1, or fe80::2 when the host itself owns fe80::1. */
        internal fun routeProbe(localAddress: Inet6Address): Inet6Address {
            val bytes = ByteArray(16)
            bytes[0] = 0xfe.toByte()
            bytes[1] = 0x80.toByte()
            bytes[15] = 1
            if (Arrays.equals(localAddress.address, bytes)) bytes[15] = 2
            return Inet6Address.getByAddress(null, bytes, localAddress.scopeId)
        }
    }
}
