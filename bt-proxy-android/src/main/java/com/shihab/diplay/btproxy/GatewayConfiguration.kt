package com.shihab.diplay.btproxy

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import java.io.File
import com.shilapi.xcertplay.transport.RemoteRfcommProtocol
import org.json.JSONObject

object GatewayConfiguration {
    fun validAddress(value: String?) = value != null &&
        value.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
        value != "02:00:00:00:00:00" && value != "00:00:00:00:00:00"

    fun ensure(context: Context): String {
        val file = File(context.filesDir, "gateway-bluetooth-mac.txt")
        val saved = runCatching { file.readText().trim() }.getOrNull()
        val address = saved?.takeIf(::validAddress)
            ?: runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }
                .getOrNull()?.takeIf(::validAddress)
            ?: runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }
                .getOrNull()?.takeIf(::validAddress)
            ?: error("无法读取本机蓝牙地址，请点击“配对信息”完成首次配置")
        file.writeText(address)
        val token = File(context.filesDir, "gateway-token.txt")
        token.writeText(RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
        return address
    }

    fun pairingInformation(context: Context): String {
        val address = ensure(context)
        return JSONObject().put("gatewayBluetoothMac", address)
            .put("token", File(context.filesDir, "gateway-token.txt").readText().trim()).toString()
    }
}
