package com.shilapi.xcertplay.installer.adb

sealed interface AdbTarget {
    val title: String
    val subtitle: String

    data class Usb(val device: UsbAdbDevice) : AdbTarget {
        override val title: String get() = device.label
        override val subtitle: String get() = "通过 OTG 数据线连接"
    }

    data class Tcp(val host: String, val port: Int) : AdbTarget {
        override val title: String get() = "这台手机"
        override val subtitle: String get() = "无需数据线"
    }
}
