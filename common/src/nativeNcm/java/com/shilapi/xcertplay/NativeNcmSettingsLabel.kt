package com.shilapi.xcertplay

import android.content.Context

internal object NativeNcmSettingsLabel {
    fun label(context: Context): String = context.getString(com.shilapi.xcertplay.host.R.string.settings_native_ncm)
}
