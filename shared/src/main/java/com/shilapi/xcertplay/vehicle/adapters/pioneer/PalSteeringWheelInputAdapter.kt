package com.shilapi.xcertplay.vehicle.adapters.pioneer

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.lang.reflect.Proxy

/** PAL audio diagnostics and the legacy companion event deduplicator; never subscribes to wheel keys. */
internal object PalSteeringWheelInputAdapter {
    @Volatile private var report: (String) -> Unit = {}
    private val sdkLoadLock = Any()
    @Volatile private var cachedLoader: ClassLoader? = null
    private var initialized = false
    private var initializing = false
    private var serviceCallback: Any? = null
    private val recentEvents = LinkedHashMap<String, Long>()

    private fun initializeSdk(context: Context) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        report("Vehicle: PAL SDK initialization starting asynchronously")
        try {
            val loader = loadSdk(context)
            // Fail here rather than crashing the SDK's asynchronous worker thread.
            loader.loadClass("com.google.gson.Gson")
            val manager = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager")
            val callbackType = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager\$d")
            serviceCallback = proxy(loader, callbackType, "DiPlayPalService") { name, _ ->
                when (name) {
                    "onConnected" -> this.report("Vehicle: PAL audio service connected")
                    "onDisconnected" -> synchronized(this) {
                        this.report("Vehicle: PAL disconnected")
                    }
                    "onError" -> this.report("Vehicle: PAL service error")
                }
                null
            }
            // Preserve the SDK's main-thread invocation: it may create a Handler during initialization.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    manager.getMethod("a", Context::class.java, callbackType).invoke(null, context, serviceCallback)
                    synchronized(this) { initialized = true }
                    report("Vehicle: PAL initialization requested")
                } catch (error: Exception) {
                    report("Vehicle: PAL unavailable ${error.javaClass.simpleName}: ${error.message}")
                } finally {
                    synchronized(this) { initializing = false }
                }
            }
        } catch (error: Exception) {
            synchronized(this) { initialized = false }
            report("Vehicle: PAL unavailable ${error.javaClass.simpleName}: ${error.message}")
            synchronized(this) { initializing = false }
        }
    }

    /** Diagnostic microphone access shares the already provisioned SDK and connection. */
    internal fun audioServiceForTest(context: Context): Pair<ClassLoader, Any> {
        val launch = synchronized(this) {
            if (!initialized && !initializing) { initializing = true; true } else false
        }
        if (launch) initializeSdk(context)
        val loader = loadSdk(context)
        val service = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager")
        val audioType = loader.loadClass("com.tencent.tai.pal.client.a")
        val remoteType = loader.loadClass("com.tencent.tai.pal.audio.b")
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        do {
            val remote = runCatching {
                val client = service.getMethod("a", Class::class.java).invoke(null, audioType) ?: return@runCatching null
                client.javaClass.declaredFields.firstOrNull { it.type == remoteType }?.let {
                    it.isAccessible = true
                    it.get(client)
                }
            }.getOrNull()
            if (remote != null) return loader to remote
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("PAL 音频服务未就绪或不支持麦克风焦点")
    }

    private fun loadSdk(context: Context): ClassLoader = synchronized(sdkLoadLock) {
        cachedLoader?.let { return@synchronized it }
        val loader = context.classLoader
        // Reflection keeps builds without the locally provisioned vehicle SDK usable on other cars.
        loader.loadClass("com.tencent.tai.pal.client.PALServiceManager")
        cachedLoader = loader
        clearLegacySdkCache(context)
        loader
    }

    /** One background migration of our obsolete extracted SDK and optimized output. */
    private fun clearLegacySdkCache(context: Context) {
        for (directory in listOf(File(context.filesDir, "vehicle-pal-sdk"),
                File(context.codeCacheDir, "vehicle-pal-opt"), File(context.noBackupFilesDir, "vehicle-pal-opt"))) {
            clearLegacySdkCacheDirectory(directory)
        }
    }

    internal fun clearLegacySdkCacheDirectory(directory: File) {
        val artifact = Regex("""pal-[0-9a-f]{64}\.dex(?:\.flock)?""")
        runCatching {
            // Do not follow a substituted directory outside the app-owned cache location.
            if (directory.canonicalFile != File(directory.parentFile.canonicalFile, directory.name)) return@runCatching
            for (file in directory.listFiles().orEmpty()) {
                if ((artifact.matches(file.name) || file.name == "pal.tmp") && file.isFile) file.delete()
            }
            if (directory.listFiles()?.isEmpty() == true) directory.delete()
        }
    }

    /** Match v0.4.0: acknowledge repeated companion deliveries without sending the command twice. */
    @Synchronized
    internal fun acceptBridgeEvent(signature: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        recentEvents.entries.removeAll { now - it.value > 2_000 }
        if (recentEvents.containsKey(signature)) return false
        if (recentEvents.size >= 64) recentEvents.remove(recentEvents.keys.first())
        recentEvents[signature] = now
        return true
    }

    private fun proxy(loader: ClassLoader, type: Class<*>, label: String,
                      callback: (String, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "toString" -> label
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> callback(method.name, args)
            }
        }
}
