package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure

/** Read-only readiness gate. Never starts or retries the OEM connection state machine. */
internal object PioneerBluetoothLink {
    fun requireConnected(adapter: PioneerAdapter, device: PairedDevice) {
        if(PioneerBluetoothSnapshot().connectedAddress(device.address)!=null && adapter.isConnected(device))return
        throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
            "蓝牙未连接，请连接后重试")
    }
}
