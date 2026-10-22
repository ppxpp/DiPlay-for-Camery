package com.shilapi.xcertplay.pioneer

/** One RX and one TX caller may run concurrently; join both before [close]. */
object PioneerVpnPacketNative {
    init { System.loadLibrary("pioneer_vpn") }
    fun prepare() = Unit
    /** Duplicates the fd; setting nonblocking also affects the original open file description. */
    external fun create(tunFd: Int, hostMac: ByteArray, address: ByteArray): Long
    external fun prepareUsbContext(handle: Long, usbFd: Int, inEndpoint: Int, outEndpoint: Int): Int
    external fun runUsbReceiver(handle: Long, usbFd: Int, inEndpoint: Int, outEndpoint: Int, slots: Int): Int
    external fun runUsbSender(handle: Long, usbFd: Int, outEndpoint: Int,
                              pipelineDepth: Int): Int
    external fun enableBatching(handle: Long): Int
    external fun stop(handle: Long)
    private external fun snapshot(handle: Long, values: LongArray): Int
    external fun close(handle: Long)

    fun snapshotJson(handle: Long): org.json.JSONObject = org.json.JSONObject().apply {
        val values = LongArray(33)
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
        put("usbRxPolls", values[25])
        put("usbRxPollReady", values[26])
        put("usbRxPollTimeouts", values[27])
        put("usbTxPolls", values[28])
        put("usbTxPollReady", values[29])
        put("usbTxPollTimeouts", values[30])
        put("configuredTxBatchFrames", values[31])
        put("usbRxCompletionOrderRegressions", values[32])
    }
}
