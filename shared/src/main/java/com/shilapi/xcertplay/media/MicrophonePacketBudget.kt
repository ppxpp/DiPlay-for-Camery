package com.shilapi.xcertplay.media

import android.content.Context
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
                probe.bind(InetSocketAddress(config.bindAddress ?: InetAddress.getByName("::"), 0))
                probe.connect(InetSocketAddress(config.host, config.port))
                val local = probe.localAddress
                val networkInterface = NetworkInterface.getByInetAddress(local)
                if (local.isAnyLocalAddress || local.isLoopbackAddress || networkInterface == null || networkInterface.isLoopback)
                    return@use Limit(fallback, ipv6, "CONSERVATIVE_UNKNOWN_ROUTE", null)
                val reported = mutableListOf<Int>()
                runCatching { networkInterface.mtu }.getOrNull()?.takeIf { it > 0 }?.let(reported::add)
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
