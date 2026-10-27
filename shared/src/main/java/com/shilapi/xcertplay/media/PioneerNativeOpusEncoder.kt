package com.shilapi.xcertplay.media

import android.content.Context
import android.system.Os
import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.PioneerVehicleWireless
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The same ROM codec as the independent worker benchmark; no audio or credentials are retained. */
internal class PioneerNativeOpusEncoder private constructor(context: Context, private val bitrate: Int) : MicrophoneOpusEncoder {
    override val implementation: String get() = if(fallback==null) "pioneer-native" else "software-fallback"
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"pioneer-opus-io").apply { isDaemon=true } }
    private var process: Process?=null
    private var input: DataInputStream?=null
    private var fallback: MicrophoneOpusEncoder?=null
    private var closed=false
    override val available: Boolean get() = !closed && (process!=null || fallback?.available==true)
    init {
        try {
            val systemCodec=File("/system/lib/libopus.so")
            check(systemCodec.canRead()) { "Vehicle Opus codec unavailable" }
            val binary=File(context.codeCacheDir,"pioneer-opus-host")
            val temporary=File(context.codeCacheDir,"opus-${UUID.randomUUID()}")
            context.assets.open("pioneer-opus-host").use { src -> temporary.outputStream().use { src.copyTo(it) } }
            Os.chmod(temporary.path,448)
            check(temporary.renameTo(binary))
            val child=ProcessBuilder(binary.path,bitrate.toString()).start()
            process=child;input=DataInputStream(child.inputStream)
            check(worker.submit<Int> { input!!.readInt() }.get(3,TimeUnit.SECONDS)==0) { "Vehicle Opus initialization failed" }
        } catch(failure: Throwable) { close();throw failure }
    }
    @Synchronized override fun encode(pcm: ByteArray): List<ByteArray> {
        if(closed || pcm.size!=1920)return emptyList()
        fallback?.let { return it.encode(pcm) }
        return try {
            worker.submit<List<ByteArray>> {
                val child=checkNotNull(process)
                child.outputStream.write(pcm);child.outputStream.flush()
                val size=input!!.readInt()
                check(size in 1..1275) { "Vehicle Opus encode failed" }
                listOf(ByteArray(size).also { input!!.readFully(it) })
            }.get(250,TimeUnit.MILLISECONDS)
        } catch(failure: Exception) {
            android.util.Log.w("xcertplay-usb","Vehicle Opus worker failed; using software encoder",failure)
            stopWorker()
            SoftwareOpusEncoder(bitrate).also { fallback=it }.encode(pcm)
        }
    }
    private fun stopWorker() {
        process?.destroy();process=null
        runCatching { input?.close() };input=null
        worker.shutdownNow()
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true;stopWorker();fallback?.close();fallback=null
    }
    companion object {
        @Volatile private var application: Context?=null
        fun configure(context: Context) { application=context.applicationContext }
        fun create(bitrate: Int): MicrophoneOpusEncoder? {
            if(PioneerWiredSession.current?.backend!=PioneerWiredSession.Backend.WIRELESS || !PioneerVehicleWireless.supported())return null
            val context=application ?: return null
            return runCatching { PioneerNativeOpusEncoder(context,bitrate) }.getOrElse {
                android.util.Log.w("xcertplay-usb","Vehicle Opus worker unavailable; using original encoder",it)
                null
            }
        }
    }
}
