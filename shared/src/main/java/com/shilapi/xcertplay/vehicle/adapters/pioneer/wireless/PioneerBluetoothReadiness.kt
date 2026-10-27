package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure

internal object PioneerBluetoothReadiness {
    fun requireConnected(state: Int, connected: Int) {
        if(state!=connected) throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
            "已选 iPhone 尚未连接车机蓝牙，请在原厂蓝牙界面连接后重试")
    }
}
