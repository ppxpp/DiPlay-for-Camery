package com.shilapi.xcertplay.feedback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.shilapi.xcertplay.update.InternetNetworkSelector
import java.net.URL
import java.net.URLConnection

/** Uses a validated internet route, never a CarPlay-only local Wi-Fi network. */
internal object FeedbackConnectivity {
    fun select(context: Context): Network? {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching { InternetNetworkSelector.select(manager) }.getOrNull()
    }

    fun isOnline(context: Context): Boolean = select(context) != null

    fun openConnection(context: Context, url: URL): URLConnection? =
        select(context)?.openConnection(url)
}
