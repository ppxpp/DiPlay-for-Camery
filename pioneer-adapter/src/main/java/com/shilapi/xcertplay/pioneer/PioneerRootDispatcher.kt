package com.shilapi.xcertplay.pioneer

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Verified Pioneer service transport. Callers construct fixed-purpose helper commands. */
internal object PioneerRootDispatcher {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun dispatch(command: String) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
            .invoke(null, "pioneer.pfilesdoperator") as? IBinder ?: error("Pioneer root service unavailable")
        val bytes = (command + '\u0000').toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096 && '\u0000' !in command)
        val encoded = ByteBuffer.allocate(((bytes.size + 3) and -4) + 12)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(Process.myPid()).putInt(bytes.size).putInt(0).put(bytes).array()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.unmarshall(encoded, 0, encoded.size); data.setDataPosition(0)
            check(binder.transact(14, data, reply, 0) && reply.dataSize() == 4) { "Pioneer root dispatch rejected" }
            reply.setDataPosition(0)
            check(reply.readInt() == 0) { "Pioneer root dispatch failed" }
        } finally { data.recycle(); reply.recycle() }
    }
}
