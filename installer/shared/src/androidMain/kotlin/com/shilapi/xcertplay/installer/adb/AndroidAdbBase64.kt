package com.shilapi.xcertplay.installer.adb

import android.util.Base64
import com.cgutman.adblib.AdbBase64

class AndroidAdbBase64 : AdbBase64 {
    override fun encodeToString(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP)
}
