package com.shilapi.xcertplay.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.URL
import java.net.URLConnection

/** Tries internet-capable routes even when the head unit's system validation is unavailable. */
internal object FeedbackConnectivity {
    fun select(context: Context): Network? = runCatching {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        select(manager)
    }.getOrNull()

    internal fun select(manager: ConnectivityManager): Network? {
        val active = manager.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            manager.allNetworks.forEach { if (it != active) add(it) }
        }.mapNotNull { network ->
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            capabilities?.takeIf(::canAttemptUpload)?.let { network to it }
        }
        // VALIDATED is a preference, not proof that the feedback endpoint is reachable.
        return (candidates.firstOrNull {
            it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: candidates.firstOrNull())?.first
    }

    private fun canAttemptUpload(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    fun openConnection(context: Context, url: URL): URLConnection =
        select(context)?.openConnection(url) ?: url.openConnection()
}
