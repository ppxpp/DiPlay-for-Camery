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
                com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("buffers", "RUNNING")
                nativeHandle = nativePackets.create(tun.fd, hostMac, neighborAddress)
                check(nativeHandle != 0L) { "VPN native packet context unavailable; no fallback" }
                check(nativePackets.prepareUsbContext(
                    nativeHandle, ncm.usbFd, ncm.inEndpointAddress, ncm.outEndpointAddress,
                ) == 0) { "VPN native USBFS context initialization failed" }
                val ownerSession = com.shilapi.xcertplay.pioneer.PioneerWiredSession.sessionId
                val snapshot = {
                    synchronized(this) {
                        check(nativeHandle != 0L && com.shilapi.xcertplay.pioneer.PioneerWiredSession.isCurrent(ownerSession)) {
                            "VPN native session already closed"
                        }
                        nativePackets.snapshotJson(nativeHandle)
                    }
                }
                publishActiveSnapshot(snapshot)
                com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.vpnNativeSnapshot = snapshot
                com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.vpnEnableBatching = {
                    synchronized(this) {
                        nativeHandle != 0L && com.shilapi.xcertplay.pioneer.PioneerWiredSession.isCurrent(ownerSession) &&
                            nativePackets.enableBatching(nativeHandle) == 0
                    }
                }
                diagnostic("VPN_DATAPATH native USB receiveRequests=4 sendRequests=2 batchPackets=8; no batch wait")
            }
            ncmToTunThread = Thread({
                if (nativeHandle == 0L) runNcmToTun()
                else runNativeUrbReceiver()
            }, "ncm-ipv6-in").apply { isDaemon = true }
            tunToNcmThread = Thread({
                if (nativeHandle == 0L) runTunToNcm()
                else runNativeUrbSender()
            }, "ncm-ipv6-out").apply { isDaemon = true }
            ncmToTunThread.start()
            tunToNcmThread.start()
        } catch (error: Throwable) {
            runCatching { close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    @Synchronized override fun close() {
        if (!running.getAndSet(false) && nativeHandle == 0L) return
        publishActiveSnapshot(null)
        com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.vpnNativeSnapshot = null
        com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.vpnEnableBatching = null
        if (nativeHandle != 0L) nativePackets.stop(nativeHandle)
        // Native workers must cancel/reap their URBs while the shared USB FD is still open.
        if (nativeHandle != 0L) {
            if (::ncmToTunThread.isInitialized) join(ncmToTunThread)
            if (::tunToNcmThread.isInitialized) join(tunToNcmThread)
            check((!::ncmToTunThread.isInitialized || !ncmToTunThread.isAlive) &&
                (!::tunToNcmThread.isInitialized || !tunToNcmThread.isAlive)) { "VPN URBs did not quiesce; USB context retained" }
        }
        ncm.close()
        if (nativeHandle == 0L) tun.close() // Wake the legacy blocking FileInputStream reader.
        if (nativeHandle == 0L) {
            if (::ncmToTunThread.isInitialized) join(ncmToTunThread)
            if (::tunToNcmThread.isInitialized) join(tunToNcmThread)
        } else {
            nativePackets.close(nativeHandle)
            nativeHandle = 0L
            tun.close()
        }
    }

    private fun runNativeUrbReceiver() {
        try {
            val result = nativePackets.runUsbReceiver(
                nativeHandle,
                ncm.usbFd,
                ncm.inEndpointAddress,
                ncm.outEndpointAddress,
                com.shilapi.xcertplay.pioneer.PioneerVpnIoPolicy.RECEIVE_REQUESTS,
            )
            if (running.get() && result < 0) {
                onError(IOException("VPN_NATIVE_URB_RX_FAILED rc=$result"))
            }
        } catch (error: Exception) { if (running.get()) onError(error) }
    }

    private fun runNativeUrbSender() {
        try {
            // Idempotent promotion only: startup must never overwrite a concurrent
            // first-frame callback by lowering its already-enabled batch limit.
            if (com.shilapi.xcertplay.pioneer.PioneerWiredSession.current?.firstFrame == true)
                check(nativePackets.enableBatching(nativeHandle) == 0)
            val result = nativePackets.runUsbSender(
                nativeHandle,
                ncm.usbFd,
                ncm.outEndpointAddress,
                com.shilapi.xcertplay.pioneer.PioneerVpnIoPolicy.SEND_REQUESTS,
            )
            if (running.get() && result < 0) {
                onError(IOException("VPN_NATIVE_URB_TX_FAILED rc=$result"))
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
        @Synchronized fun prepare() {
            com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("components", "RUNNING")
            com.shilapi.xcertplay.pioneer.PioneerVpnPacketNative.prepare()
            com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("components", "COMPLETE")
        }
        @Volatile
        var activeSnapshot: (() -> org.json.JSONObject)? = null
            private set

        private fun publishActiveSnapshot(snapshot: (() -> org.json.JSONObject)?) {
            activeSnapshot = snapshot
        }

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
