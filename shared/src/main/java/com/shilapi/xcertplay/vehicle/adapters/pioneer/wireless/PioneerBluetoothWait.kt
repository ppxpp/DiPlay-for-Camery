package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

/** Broadcasts only wake the waiter: the selected device is always verified through read-only OEM Binder queries. */
internal object PioneerBluetoothWait {
    private val actions=listOf(
        "jp.pioneer.ceam.bluetooth.adapter.action.CONNECTION_STATE_CHANGED",
        "jp.pioneer.ceam.bluetooth.adapter.action.AUTO_CONNECT_SUCCESS",
        "jp.pioneer.ceam.bluetooth.adapter.action.AUTO_CONNECT_FAIL",
        "jp.pioneer.ceam.bluetooth.adapter.action.AUTO_CONNECTION_STATE_CHANGED",
        "jp.pioneer.ceam.bluetooth.telephony.action.TELEPHONY_CONNECTION_CHANGED",
    )
    fun await(context: Context, connected: () -> Boolean, cancelled: () -> Boolean,
        waiting: () -> Unit, log: (String) -> Unit, timeoutMillis: Long=120_000) {
        val signal=Object()
        val notifications=AtomicInteger()
        val receiver=object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if(intent?.action !in actions) return
                log("Vehicle Bluetooth notification action=${intent?.action}; rechecking selected device")
                synchronized(signal) { notifications.incrementAndGet();signal.notifyAll() }
            }
        }
        var registered=false
        try {
            val filter=IntentFilter().apply { actions.forEach(::addAction) }
            runCatching {
                if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
                else { @Suppress("DEPRECATION") context.registerReceiver(receiver,filter) }
                registered=true
            }.onFailure { log("Vehicle Bluetooth receiver unavailable; using bounded state polling") }
            val start=SystemClock.elapsedRealtime()
            val deadline=start+timeoutMillis
            var announced=false
            var polls=0
            while(true) {
                if(cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Vehicle Bluetooth wait cancelled")
                val observed=notifications.get()
                polls++
                if(connected()) {
                    log("Vehicle Bluetooth connected waitMs=${SystemClock.elapsedRealtime()-start} broadcasts=${notifications.get()} polls=$polls")
                    return
                }
                val remaining=deadline-SystemClock.elapsedRealtime()
                if(remaining<=0) throw WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
                    "等待 iPhone 连接车机蓝牙超时，请在原厂蓝牙设置中连接后重试", timeoutMillis)
                if(!announced) { announced=true;waiting();log("Vehicle Bluetooth waiting timeoutMs=$timeoutMillis receiver=$registered") }
                synchronized(signal) {
                    // Do not lose a notification received during the state query.
                    val pollAt=SystemClock.elapsedRealtime()+minOf(1000,remaining)
                    while(notifications.get()==observed && !cancelled()) {
                        val pause=pollAt-SystemClock.elapsedRealtime()
                        if(pause<=0) break
                        signal.wait(minOf(250,pause))
                    }
                }
            }
        } finally {
            if(registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
