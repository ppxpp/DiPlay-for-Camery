package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophonePacketBudgetTest {
    @Test fun jumboInterfaceMustNotBeMistakenForEndToEndJumboPath() {
        val limit = MicrophonePacketBudget.selectLimit(9000, true, "usb0")
        assertEquals(1280, limit.mtu)
        assertEquals(9000, limit.interfaceMtu)
        assertEquals("INTERFACE_AND_CONSERVATIVE_PATH", limit.source)
    }
    @Test fun reportedSmallerLimitIsNeverRaisedByTheFallback() {
        assertEquals(1000, MicrophonePacketBudget.selectLimit(1000, true, "usb0").mtu)
        assertEquals(500, MicrophonePacketBudget.selectLimit(500, false, "usb0").mtu)
    }
    @Test fun missingOrInvalidInterfaceHasExplicitConservativeFallback() {
        assertEquals(1280, MicrophonePacketBudget.selectLimit(null, true, null).mtu)
        assertEquals(576, MicrophonePacketBudget.selectLimit(0, false, null).mtu)
    }
    @Test fun socketCreationFailureUsesTheCorrectAddressFamilyFallback() {
        val config = microphoneConfig("::1")
        val result = MicrophonePacketBudget.resolve(config, null) { throw java.net.SocketException("route unavailable") }
        assertEquals(1280, result.mtu)
        assertTrue(result.ipv6)
        assertEquals("CONSERVATIVE_ROUTE_QUERY_FAILED", result.source)
    }
    @Test fun bindFailureClosesTheAlreadyCreatedProbeSocket() {
        val socket = java.net.DatagramSocket()
        val result = MicrophonePacketBudget.resolve(microphoneConfig("127.0.0.1"), null) { socket }
        assertTrue(socket.isClosed)
        assertEquals(576, result.mtu)
        assertEquals("CONSERVATIVE_ROUTE_QUERY_FAILED", result.source)
    }
    @Test fun loopbackProbeIsClosedAndCannotSupplyAPhysicalInterfaceMtu() {
        val socket = java.net.DatagramSocket(null as java.net.SocketAddress?)
        val result = MicrophonePacketBudget.resolve(microphoneConfig("127.0.0.1"), null) { socket }
        assertTrue(socket.isClosed)
        assertEquals(576, result.mtu)
        assertNull(result.interfaceMtu)
        assertEquals("CONSERVATIVE_UNKNOWN_ROUTE", result.source)
    }
    private fun microphoneConfig(host: String) = com.shilapi.xcertplay.airplay.MicrophoneConfig(
        audioType = "telephony", sampleRate = 48_000, channels = 1, payloadType = 100,
        frameMillis = 20, host = java.net.InetAddress.getByName(host), port = 9, key = ByteArray(32),
        bindAddress = java.net.InetAddress.getByName(host))

}
