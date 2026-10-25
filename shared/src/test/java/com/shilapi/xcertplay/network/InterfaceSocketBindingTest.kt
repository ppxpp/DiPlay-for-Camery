package com.shilapi.xcertplay.network

import java.net.*
import org.junit.Assert.*
import org.junit.Test

class InterfaceSocketBindingTest {
    private fun address(text: String, scope: Int = 9) = Inet6Address.getByAddress(null, InetAddress.getByName(text).address, scope)
    private val binding get() = InterfaceSocketBinding(address("fe80::2"), 9)
    private class Accepted(private val local: InetAddress, private val peer: InetAddress) : Socket() {
        var released = false
        override fun getLocalAddress() = local
        override fun getInetAddress() = peer
        override fun close() { released = true }
    }
    @Test fun scopesUnscopedSourcesAndDestinations() {
        val native = InterfaceSocketBinding(address("fe80::2", 0), 9)
        assertEquals(9, native.localAddress.scopeId)
        assertEquals(9, (native.peerEndpoint(address("fe80::1", 0), 7000).address as Inet6Address).scopeId)
    }
    @Test fun routeProbeUsesTheReferenceAddressAndNeverProbesItself() {
        val ordinary = InterfaceSocketBinding.routeProbe(address("fe80::2"))
        assertArrayEquals(InetAddress.getByName("fe80::1").address, ordinary.address)
        assertEquals(9, ordinary.scopeId)
        val collision = InterfaceSocketBinding.routeProbe(address("fe80::1"))
        assertArrayEquals(InetAddress.getByName("fe80::2").address, collision.address)
        assertEquals(9, collision.scopeId)
    }
    @Test fun rejectsWrongLocalScopeAndNonLinkLocalSource() {
        assertTrue(runCatching { InterfaceSocketBinding(address("fe80::2", 10), 9) }.isFailure)
        assertTrue(runCatching { InterfaceSocketBinding(address("2001:db8::2"), 9) }.isFailure)
        assertTrue(runCatching { InterfaceSocketBinding(address("fe80::2", 0), 0) }.isFailure)
    }
    @Test fun acceptedSocketMustUseBothCorrectLocalInterfaceAndControlPeer() {
        val valid = Accepted(address("fe80::2", 0), address("fe80::1", 0))
        binding.validateAccepted(valid, address("fe80::1")); assertFalse(valid.released)
        for (invalid in listOf(
            Accepted(address("fe80::3"), address("fe80::1")),
            Accepted(address("fe80::2", 10), address("fe80::1")),
            Accepted(address("fe80::2"), address("fe80::3")),
            Accepted(address("fe80::2"), InetAddress.getByName("127.0.0.1"))
        )) {
            assertTrue(runCatching { binding.validateAccepted(invalid, address("fe80::1")) }.isFailure)
            assertTrue(invalid.released)
        }
    }
    @Test fun datagramsMustMatchControlPeerAndInterface() {
        assertEquals(9, binding.validatePeer(address("fe80::1", 0), address("fe80::1")).scopeId)
        assertTrue(runCatching { binding.validatePeer(address("fe80::3"), address("fe80::1")) }.isFailure)
        assertTrue(runCatching { binding.validatePeer(address("fe80::1", 10), address("fe80::1")) }.isFailure)
    }
    @Test fun bindingFailureClosesTheCreatedListener() {
        val server = object : ServerSocket() {
            var released = false
            override fun bind(endpoint: SocketAddress?) { throw BindException("test failure") }
            override fun close() { released = true }
        }
        assertTrue(runCatching { binding.bind(server) }.isFailure)
        assertTrue(server.released)
    }
}
