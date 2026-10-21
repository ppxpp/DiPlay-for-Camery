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
    /** Packed frame count, reply length and reply frame count for the normal Android request path. */
    external fun inputNtbPacked(handle: Long, buffer: ByteBuffer, length: Int, reply: ByteBuffer): Long
    /** Returns transfer bytes, or zero after a timeout. Buffer capacity must be at least 16384. */
    external fun outputNtb(handle: Long, buffer: ByteBuffer, timeoutMs: Int, maxFrames: Int): Int
    external fun prepareUsbContext(handle: Long, usbFd: Int, inEndpoint: Int, outEndpoint: Int): Int
    external fun runUsbReceiver(handle: Long, usbFd: Int, inEndpoint: Int, outEndpoint: Int, slots: Int): Int
    external fun runUsbSender(handle: Long, usbFd: Int, outEndpoint: Int,
                              pipelineDepth: Int, maxFrames: Int, batchWindowUs: Int): Int
    external fun configure(handle: Long, maxFrames: Int, batchWindowUs: Int): Int
    external fun capabilities(usbFd: Int, values: LongArray): Int
    external fun stop(handle: Long)
    private external fun snapshot(handle: Long, values: LongArray): Int
    external fun close(handle: Long)

    fun snapshotJson(handle: Long): org.json.JSONObject = org.json.JSONObject().apply {
        val values = LongArray(32)
        check(snapshot(handle, values) == 0) { "VPN metric snapshot failed" }
        put("rxChunks", values[0])
        put("rxDirectBlocks", values[1])
        put("rxStreamCopies", values[2])
        put("rxCopiedBytes", values[3])
        put("rxFrames", values[4])
        put("rxTunWrites", values[5])
        put("rxNdpReplies", values[6])
        put("rxParseErrors", values[7])
        put("txPolls", values[8])
        put("txPackets", values[9])
        put("txBatches", values[10])
        put("txBatchFull", values[11])
        put("txSingleFrameBatches", values[12])
        put("txFourFrameBatches", values[13])
        put("txEightFrameBatches", values[14])
        put("txSixteenFrameBatches", values[15])
        put("txTunReads", values[16])
        put("txTunReadyDrains", values[17])
        put("txBuildNs", values[18])
        put("usbRxSubmits", values[19])
        put("usbRxReaps", values[20])
        put("usbRxErrors", values[21])
        put("usbTxSubmits", values[22])
        put("usbTxReaps", values[23])
        put("usbTxErrors", values[24])
        put("rxAllocations", 0)
        put("txAllocations", 0)
        put("javaDirectBufferAllocations", 2)
    }
}
