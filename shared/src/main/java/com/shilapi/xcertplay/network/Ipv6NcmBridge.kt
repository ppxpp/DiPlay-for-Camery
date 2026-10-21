package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    private val neighborAddress: ByteArray? = null,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var inboundTcpLogBudget = 16
    private var outboundLogBudget = 24
    private val running = AtomicBoolean(false)
    private var nativeHandle = 0L
    private val nativePackets get() = com.shilapi.xcertplay.pioneer.PioneerVpnPacketNative
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        try {
        if (neighborAddress != null && com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice()) {
            nativeHandle = nativePackets.create(tun.fd, hostMac, neighborAddress)
            check(nativeHandle != 0L) { "VPN native packet context unavailable; no fallback" }
            diagnostic("VPN_DATAPATH direct-native-ntb-tun double-buffered-rx; automatic fallback disabled")
        }
        ncmToTunThread = Thread({ if (nativeHandle != 0L) runNativeNcmToTun() else runNcmToTun() }, "ncm-ipv6-in").apply { isDaemon = true }
        tunToNcmThread = Thread({ if (nativeHandle != 0L) runNativeTunToNcm() else runTunToNcm() }, "ncm-ipv6-out").apply { isDaemon = true }
        ncmToTunThread.start()
        tunToNcmThread.start()
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    @Synchronized override fun close() {
        if (!running.getAndSet(false) && nativeHandle == 0L) return
        ncm.close()
        if (nativeHandle == 0L) tun.close() // Wake the legacy blocking FileInputStream reader.
        if (::ncmToTunThread.isInitialized) join(ncmToTunThread)
        if (::tunToNcmThread.isInitialized) join(tunToNcmThread)
        if (nativeHandle != 0L) {
            check((!::ncmToTunThread.isInitialized || !ncmToTunThread.isAlive) &&
                (!::tunToNcmThread.isInitialized || !tunToNcmThread.isAlive)) { "VPN native I/O did not stop; context retained" }
            nativePackets.close(nativeHandle)
            nativeHandle = 0L
            tun.close()
        }
    }

    private fun runNativeNcmToTun() {
        val reply = java.nio.ByteBuffer.allocateDirect(16384)
        val metadata = LongArray(3)
        val consume: (java.nio.ByteBuffer, Int) -> Int = { buffer, length ->
            val count = nativePackets.inputNtb(nativeHandle, buffer, length, reply, metadata)
            if (metadata[0] > 0) requireCompleteWrite(ncm.sendDirectNtbMeasured(reply,
                metadata[0].toInt(), metadata[1].toInt(), WRITE_TIMEOUT_MILLIS, assignSequence = true))
            count
        }
        try {
            while (running.get()) ncm.recvDirectChunk(READ_TIMEOUT_MILLIS, consume)
        } catch (error: Exception) { if (running.get()) onError(error) }
    }

    private fun runNativeTunToNcm() {
        val output = java.nio.ByteBuffer.allocateDirect(16384)
        try {
            while (running.get()) {
                val limit = if (com.shilapi.xcertplay.pioneer.PioneerWiredSession.current?.firstFrame == true)
                    com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.usbTxBatchFrames.coerceIn(1, 8) else 1
                val length = nativePackets.outputNtb(nativeHandle, output, 100, limit)
                if (length > 0) requireCompleteWrite(ncm.sendDirectNtbMeasured(output,
                    length, ((output.get(16).toInt() and 0xff) - 12) / 4, WRITE_TIMEOUT_MILLIS, assignSequence = true))
            }
        } catch (error: Exception) { if (running.get()) onError(error) }
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac
                val neighborReply = neighborAddress?.let { PioneerNcmNeighborDiscovery.reply(frame, it, hostMac) }
                if (neighborReply != null) {
                    sendFrame(neighborReply)
                    diagnostic("PIONEER_NDP_REPLY neighbor advertisement submitted bytes=${neighborReply.size}")
                    continue
                }
                if (!loggedInbound) {
                    loggedInbound = true
                    diagnostic(
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    diagnostic("ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                if (ipv6.payloadLength >= 60 && frame[ipv6.payloadOffset + 6].toInt() and 255 == 6 && inboundTcpLogBudget > 0) {
                    inboundTcpLogBudget--
                    diagnostic("NCM_TCP ${frame.summary(ipv6.payloadOffset)} flags=0x${(frame[ipv6.payloadOffset + 53].toInt() and 255).toString(16)}")
                }
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    diagnostic("ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    diagnostic("ncm outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        diagnostic("ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    diagnostic(
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                sendFrame(frame)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun sendFrame(frame: ByteArray) {
        // A failed ioctl may have delivered a prefix without reporting its length. Retain no
        // silent drops and never blindly replay UDP/control packets with uncertain delivery.
        requireCompleteWrite(ncm.sendMeasured(frame, WRITE_TIMEOUT_MILLIS))
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    companion object {
        internal fun requireCompleteWrite(result: NcmUsbBridge.WriteResult) {
            if (!result.completed) throw IOException("VPN_NCM_WRITE_FAILED failure=${result.failure} " +
                "transferred=${result.transferred} expected=${result.expected}; delivery unconfirmed, reconnect required")
        }

        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
