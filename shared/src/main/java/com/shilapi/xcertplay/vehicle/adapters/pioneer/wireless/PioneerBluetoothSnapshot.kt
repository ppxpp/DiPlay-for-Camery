package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.os.Parcel

/** Passive native Binder queries; never loads BluetoothAdapter/BluetoothJni or registers native callbacks. */
internal class PioneerBluetoothSnapshot(private val log: (String) -> Unit = {},
    private val calls: PioneerVendorCalls = PioneerVendorCalls.shared) {
    companion object {
        // Passive queries have their own short-lived budget and cannot poison or occupy the
        // connection/recovery gate. A timed-out diagnostic call is never queued behind again.
        private val diagnosticCalls = PioneerVendorCalls(750)
        @Synchronized fun diagnosticReport(target: String?): String = buildString {
            val reader = PioneerBluetoothSnapshot(calls = diagnosticCalls)
            val deadline = android.os.SystemClock.elapsedRealtime() + 6_000
            fun budget() { check(android.os.SystemClock.elapsedRealtime() < deadline) { "diagnostic_budget" } }
            var seenTarget = false
            try {
                budget()
                val handles = reader.handles(requireResponse = true)
                appendLine("pairedCount=${handles.size} targetConfigured=${!target.isNullOrBlank()}")
                handles.forEachIndexed { index, handle ->
                    budget()
                    val address = checkNotNull(reader.string(74, handle))
                    check(address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
                    budget()
                    val connected = reader.read(65, handle) { reply ->
                        check(reply.dataAvail() == 4)
                        reply.readInt().also { check(it in 0..1) } == 1
                    }
                    budget()
                    val profiles = reader.read(80, handle) { reply -> check(reply.dataAvail() == 4); reply.readInt() }
                    check(connected != null && profiles != null)
                    val selected = PioneerBlueforceNative.sameAddress(address, target.orEmpty())
                    seenTarget = seenTarget || selected
                    appendLine("device=${index + 1} selected=$selected deviceConnected=$connected connectedProfiles=$profiles " +
                        "profileReady=${connected != null && profiles != null && ready(connected, profiles)}")
                }
                appendLine("targetPresent=$seenTarget status=complete")
            } catch (error: Exception) {
                appendLine("status=incomplete error=${error.javaClass.simpleName} targetObserved=$seenTarget")
            }
        }

        internal fun ready(connected: Boolean, connectedProfiles: Int): Boolean =
            connected && connectedProfiles and 0x03 != 0
    }

    private var lastState: String?=null
    private var lastLoggedAt=0L
    private fun record(state: String) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(state!=lastState || now-lastLoggedAt>=5_000) {
            log("Pioneer read-only Bluetooth state $state")
            lastState=state
            lastLoggedAt=now
        }
    }
    internal data class Device(val handle: Int, val address: String)

    // Verified against BpBlueforce: paired list=25, deviceIsConnected=65,
    // deviceGetAddress=74, deviceGetName=75. These replies have no Java exception header.
    private fun <T> read(code: Int, handle: Int?=null, decode: (Parcel) -> T): T? =
        calls.call("bluetooth-snapshot-$code") {
            val service=PioneerBinder.service(PioneerBinder.SERVICE) ?: return@call null
            check(service.interfaceDescriptor==PioneerBinder.SERVICE) { "先锋蓝牙服务协议不匹配" }
            val request=Parcel.obtain()
            val reply=Parcel.obtain()
            try {
                request.writeInterfaceToken(PioneerBinder.SERVICE)
                handle?.let(request::writeInt)
                check(service.transact(code,request,reply,0)) { "先锋蓝牙只读查询未受理" }
                reply.setDataPosition(0)
                check(reply.dataAvail() in 4..1024) { "先锋蓝牙查询回复无效" }
                if(reply.readInt()!=0) null else decode(reply)
            } finally { request.recycle();reply.recycle() }
        }

    private fun string(code: Int, handle: Int): String? = read(code,handle) { reply ->
        // Native String8 is UTF-8, not Parcel.readString()'s UTF-16.
        check(reply.dataAvail()>=4) { "先锋蓝牙字符串回复不完整" }
        val length=reply.readInt()
        check(length in 0..512 && reply.dataAvail()==((length+1+3)/4)*4) {
            "先锋蓝牙字符串格式不匹配"
        }
        val bytes=reply.marshall()
        val start=reply.dataPosition()
        check(bytes[start+length].toInt()==0) { "先锋蓝牙字符串未终止" }
        String(bytes,start,length,Charsets.UTF_8)
    }

    private fun handles(requireResponse: Boolean = false): List<Int> {
        val result = read(25) { reply ->
            check(reply.dataAvail()>=4) { "先锋蓝牙设备列表不完整" }
            val count=reply.readInt()
            check(count in 0..5 && reply.dataAvail()==count*4) { "先锋蓝牙设备列表格式不匹配" }
            List(count) { reply.readInt() }
        }
        if (requireResponse) checkNotNull(result) { "Bluetooth query unavailable" }
        return result.orEmpty().distinct()
    }

    fun devices(): List<Device> {
        return handles().mapNotNull { handle ->
            val address=string(74,handle) ?: return@mapNotNull null
            check(address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) { "先锋蓝牙设备地址无效" }
            Device(handle,address)
        }
    }

    fun phones(): List<PioneerVehicleWireless.Phone> = devices().map {
        PioneerVehicleWireless.Phone(string(75,it.handle).orEmpty().ifBlank { "iPhone" },it.address)
    }

    fun connectedAddress(target: String?): String? {
        // Re-read handles each time: the OEM can recreate them during boot/reconnection.
        val devices=devices()
        val selected=if(target.isNullOrBlank()) devices.singleOrNull() else devices.singleOrNull {
            PioneerBlueforceNative.sameAddress(it.address,target)
        }
        if(selected==null) {
            record("targetAvailable=false pairedCount=${devices.size}")
            return null
        }
        val connected=read(65,selected.handle) { reply ->
            check(reply.dataAvail()==4) { "先锋蓝牙连接状态回复不完整" }
            reply.readInt().also { check(it in 0..1) { "先锋蓝牙连接状态无效" } }==1
        } ?: false
        val connectedProfiles=read(80,selected.handle) { reply ->
            check(reply.dataAvail()==4) { "先锋蓝牙已连接配置回复不完整" }
            reply.readInt()
        } ?: 0
        val autoConnect=read(44) { reply ->
            check(reply.dataAvail()==4) { "先锋蓝牙自动连接状态回复不完整" }
            reply.readInt()
        }
        val connecting=read(55) { reply ->
            check(reply.dataAvail()==4) { "先锋蓝牙连接进行状态回复不完整" }
            reply.readInt()
        }
        record("targetAvailable=true handle=${selected.handle} deviceConnected=$connected connectedProfiles=$connectedProfiles autoConnect=$autoConnect connecting=$connecting")
        // HFP=0x01, A2DP=0x02. Either is sufficient; never force HFP on an
        // audio-only phone, or accept a residual temporary SPP/ACL link as readiness.
        return selected.address.takeIf { ready(connected, connectedProfiles) }
    }
}
