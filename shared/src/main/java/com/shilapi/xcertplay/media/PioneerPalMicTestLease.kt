package com.shilapi.xcertplay.media

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import com.shilapi.xcertplay.vehicle.adapters.pioneer.PalSteeringWheelInputAdapter
import java.io.Closeable
import java.util.UUID

/** Debug-only PAL 200/230 comparison, matching the car-specific WeChat mic-focus protocol.
 * The provisioned PAL client omits its public mic wrapper, but retains the same typed AIDL.
 * No borrowed application identity, external APK or proprietary code is copied into the app.
 */
internal class PioneerPalMicTestLease private constructor(
    private val release: () -> Unit,
    private val callback: IBinder,
) : Closeable {
    private var closed = false
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        release()
    }

    companion object {
        private const val DESCRIPTOR = "com.tencent.tai.pal.audio.IAudioFocusDispatcherAidl"
        fun acquire(context: Context, type: Int, report: (String) -> Unit, current: () -> Boolean, onLost: () -> Unit): PioneerPalMicTestLease {
            check(PioneerUsbPolicy.matchesDevice()) { "PAL 麦克风对照仅支持已验证先锋车机" }
            require(type == 200 || type == 230)
            check(current()) { "录音已取消" }
            val (loader, remote) = PalSteeringWheelInputAdapter.audioServiceForTest(context)
            check(current()) { "录音已取消" }
            val id = UUID.randomUUID().toString().replace("-", "") + "-TAG_diplay_mic"
            val callbackLock = Any()
            var callbackActive = true
            val binder = object : Binder() {
                init { attachInterface(null, DESCRIPTOR) }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(DESCRIPTOR)
                    val change = data.readInt()
                    val owner = data.readString()
                    if (owner == id) synchronized(callbackLock) {
                        if (callbackActive) {
                            runCatching { report("MIC_TEST PAL focus change=$change type=$type") }
                            if (change < 0) onLost()
                        }
                    }
                    return true
                }
            }
            val callbackType = loader.loadClass("com.tencent.tai.pal.audio.c")
            val dispatcher = loader.loadClass("com.tencent.tai.pal.audio.c\$a")
                .getMethod("a", IBinder::class.java).invoke(null, binder)
            val api = loader.loadClass("com.tencent.tai.pal.audio.b")
            val abandon = api.getMethod("b", callbackType, String::class.java)
            val release = {
                // Serialize invalidation with callbacks before abandoning; a queued event must
                // never stop a later recording that reuses the recorder's cancellation flag.
                synchronized(callbackLock) { callbackActive = false }
                runCatching { abandon.invoke(remote, dispatcher, id) }
                    .onSuccess { report("MIC_TEST PAL release type=$type result=$it") }
                    .onFailure { report("MIC_TEST PAL release failed=${it.javaClass.simpleName}") }
                Unit
            }
            try {
                check(current()) { "录音已取消" }
                val result = api.getMethod("b", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    callbackType, String::class.java, String::class.java)
                    .invoke(remote, type, 2, dispatcher, id, android.os.Process.myPid().toString())
                report("MIC_TEST PAL acquire type=$type result=$result")
                check(result == 1) { "PAL 麦克风焦点申请失败（$result）" }
                return PioneerPalMicTestLease(release, binder)
            } catch (error: Throwable) {
                release()
                throw error
            }
        }
    }
}
