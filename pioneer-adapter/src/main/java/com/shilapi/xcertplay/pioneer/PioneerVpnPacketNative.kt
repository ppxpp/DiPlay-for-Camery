package com.shilapi.xcertplay.pioneer

import java.nio.ByteBuffer

/** One RX and one TX caller may run concurrently; join both before [close]. */
object PioneerVpnPacketNative {
    init { System.loadLibrary("pioneer_vpn") }
    /** Duplicates the fd; setting nonblocking also affects the original open file description. */
    external fun create(tunFd: Int, hostMac: ByteArray, address: ByteArray): Long
    /** Metadata: reply NTB length, reply frame count, reply Ethernet bytes.
     * Reply capacity must be at least 1024. Batches eight replies; extra NS go to TUN. */
    external fun inputNtb(handle: Long, buffer: ByteBuffer, length: Int, reply: ByteBuffer, metadata: LongArray): Int
    /** Returns transfer bytes, or zero after a timeout. Buffer capacity must be at least 16384. */
    external fun outputNtb(handle: Long, buffer: ByteBuffer, timeoutMs: Int, maxFrames: Int): Int
    external fun close(handle: Long)
}
