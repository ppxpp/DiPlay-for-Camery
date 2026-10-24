package com.shilapi.xcertplay.media

import android.content.Context
import android.net.ConnectivityManager
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Local egress evidence, not a claim that end-to-end PMTU has been discovered. */
internal object MicrophonePacketBudget {
    const val DEFAULT_MAX_SHORTENED_MILLIS = 10
    data class Limit(val mtu: Int, val ipv6: Boolean, val source: String, val interfaceName: String?, val interfaceMtu: Int? = null)

    fun resolve(config: MicrophoneConfig, context: Context?, socketFactory: () -> DatagramSocket = { DatagramSocket(null) }): Limit {
        val ipv6 = config.host !is Inet4Address
        val fallback = if (ipv6) 1280 else 576
        return runCatching {
            // Same binding as the real microphone socket. UDP connect sends no packet.
            socketFactory().use { probe ->
                probe.reuseAddress = true
                val binding = config.nativeBinding
                if (binding != null) {
                    binding.bind(probe)
                    probe.connect(binding.peerEndpoint(config.host, config.port))
                } else {
                    probe.bind(InetSocketAddress(config.bindAddress ?: InetAddress.getByName("::"), 0))
                    probe.connect(InetSocketAddress(config.host, config.port))
                }
                val local = probe.localAddress
                val networkInterface = NetworkInterface.getByInetAddress(local)
                if (local.isAnyLocalAddress || local.isLoopbackAddress || networkInterface == null || networkInterface.isLoopback)
                    return@use Limit(fallback, ipv6, "CONSERVATIVE_UNKNOWN_ROUTE", null)
                if (binding != null && networkInterface.index != binding.interfaceIndex)
                    return@use Limit(fallback, ipv6, "CONSERVATIVE_BINDING_MISMATCH", null)
                val reported = mutableListOf<Int>()
                runCatching { networkInterface.mtu }.getOrNull()?.takeIf { it > 0 }?.let(reported::add)
                // Only consult LinkProperties for the explicitly bound network; default Wi-Fi
                // may have nothing to do with this link-local USB/VPN destination.
                val properties = runCatching {
                    binding?.network?.let { network ->
                        (context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.getLinkProperties(network)
                    }
                }.getOrNull()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    properties?.interfaceName == networkInterface.name && properties.mtu > 0) reported.add(properties.mtu)
                selectLimit(reported.minOrNull(), ipv6, networkInterface.name)
            }
        }.getOrElse { Limit(fallback, ipv6, "CONSERVATIVE_ROUTE_QUERY_FAILED", null) }
    }
    /** A local jumbo MTU is not evidence that the remote path accepts jumbo packets. */
    internal fun selectLimit(interfaceMtu: Int?, ipv6: Boolean, name: String?): Limit {
        val conservativePath = if (ipv6) 1280 else 576
        val actual = interfaceMtu?.takeIf { it > 0 }?.coerceAtMost(65535)
        return if (actual == null) Limit(conservativePath, ipv6, "CONSERVATIVE_UNKNOWN_INTERFACE", name)
        else Limit(minOf(actual, conservativePath), ipv6, "INTERFACE_AND_CONSERVATIVE_PATH", name, actual)
    }

}
