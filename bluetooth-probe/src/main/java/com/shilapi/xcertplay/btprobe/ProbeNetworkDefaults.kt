package com.shilapi.xcertplay.btprobe

import android.content.Context
import java.util.Properties

/** Local deployment defaults are packaged from ignored assets, never exported to reports. */
internal object ProbeNetworkDefaults {
    fun read(context: Context): CarPlayProbeSettings {
        val values = Properties()
        runCatching { context.assets.open("probe-network.properties").use { values.load(it) } }
        return CarPlayProbeSettings(values.getProperty("ssid", "Xiao"), values.getProperty("password", ""))
    }
}
