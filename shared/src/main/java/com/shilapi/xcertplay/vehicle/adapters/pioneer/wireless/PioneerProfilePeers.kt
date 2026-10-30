package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.SharedPreferences
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure

/** connectProfile clears overlapping HFP/A2DP settings on peers. Only use a disjoint request. */
internal object PioneerProfilePeers {
    fun read(binder: PioneerBinder, target: String): Map<String, Int> =
        PioneerBluetoothSnapshot().devices().filterNot {
            PioneerBlueforceNative.sameAddress(it.address, target)
        }.associate { it.address.uppercase(java.util.Locale.ROOT) to
            (binder.output(81, it.handle) ?: conflict("无法读取其他蓝牙设备配置")) }

    fun compatible(peers: Map<String, Int>, mask: Int): Boolean =
        mask in 0..255 && peers.values.all { it in 0..255 && it and mask == 0 && it and 0xf0 == 0 }

    fun requireCompatible(peers: Map<String, Int>, mask: Int) {
        if(!compatible(peers,mask))conflict("其他蓝牙设备配置冲突，未修改原厂设置")
    }

    fun save(editor: SharedPreferences.Editor, target: String, peers: Map<String, Int>): SharedPreferences.Editor =
        editor.putStringSet("peers:$target", peers.map { "${it.key}=${it.value}" }.toSet())

    fun recordedMatches(journal: SharedPreferences, target: String, peers: Map<String, Int>): Boolean =
        if(journal.contains("peers:$target"))
            journal.getStringSet("peers:$target", emptySet()) == peers.map { "${it.key}=${it.value}" }.toSet()
        else peers.isEmpty() // Older journals were created only with a single paired device.

    fun requireUnchanged(binder: PioneerBinder, target: String, expected: Map<String, Int>) {
        if(read(binder, target) != expected) conflict("其他蓝牙设备配置已变化，已保留恢复记录")
    }

    private fun conflict(message: String): Nothing =
        throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT, message)
}
