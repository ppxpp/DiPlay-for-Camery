package com.shilapi.xcertplay.vehicle.adapters.pioneer

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import com.shilapi.xcertplay.vehicle.api.SteeringWheelInputSource
import java.io.File
import java.lang.reflect.Proxy

/** One PAL registration per process. A stopped session leaves an inert callback. */
internal object PalSteeringWheelInputAdapter : SteeringWheelInputSource {
    private var appContext: Context? = null
    @Volatile private var consumer: ((MediaCommand) -> Unit)? = null
    @Volatile private var report: (String) -> Unit = {}
    private val sdkLoadLock = Any()
    @Volatile private var cachedLoader: ClassLoader? = null
    private var initialized = false
    private var initializing = false
    private var registeredManager: Any? = null
    private var serviceCallback: Any? = null
    private var keyCallback: Any? = null
    private var directEvents = 0L
    private val recentEvents = LinkedHashMap<String, Long>()

    fun configure(context: Context) { appContext = context.applicationContext }

    @Synchronized
    override fun start(onCommand: (MediaCommand) -> Unit, report: (String) -> Unit) {
        consumer = onCommand
        this.report = report
        publishInputState("starting")
        if (initialized || initializing) return
        val context = appContext ?: return
        initializing = true
        // Keep vendor class initialization and service binding off startup callbacks.
        // SDK bytecode is part of the APK and is optimized by Android during installation.
        Thread({ initializeSdk(context) }, "pioneer-pal-init").apply { isDaemon = true; start() }
    }

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
                    "onConnected" -> synchronized(this) { register(loader, manager) }
                    "onDisconnected" -> synchronized(this) {
                        registeredManager = null
                        publishInputState("disconnected")
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

    @Synchronized
    override fun stop() { consumer = null; publishInputState("inactive") }

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

    private fun register(loader: ClassLoader, managerClass: Class<*>) {
        try {
            val cabinType = loader.loadClass("com.tencent.tai.pal.client.c")
            val cabin = managerClass.getMethod("a", Class::class.java).invoke(null, cabinType) ?: return
            if (registeredManager === cabin) return
            val listenerType = loader.loadClass("com.tencent.tai.pal.client.c\$a")
            keyCallback = proxy(loader, listenerType, "DiPlayPalKeys") { name, args ->
                when (name) {
                    "i" -> {
                        val filterType = loader.loadClass("com.tencent.tai.pal.input.KeyFilter")
                        filterType.getConstructor().newInstance().also { filter ->
                            val add = filterType.getMethod("addFilter", Int::class.javaPrimitiveType)
                            add.invoke(filter, 8)
                            add.invoke(filter, 9)
                        }
                    }
                    "b" -> { args?.firstOrNull()?.let(::onEvent); null }
                    else -> null // Long presses and volume stay system-owned.
                }
            }
            cabinType.getMethod("a", listenerType).invoke(cabin, keyCallback)
            registeredManager = cabin
            publishInputState("registered")
            report("Vehicle: PAL previous/next listener registered source=PAL_DIRECT")
        } catch (error: Exception) {
            report("Vehicle: PAL registration failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    @Synchronized
    private fun onEvent(event: Any) {
        val target = consumer ?: return
        runCatching {
            val type = event.javaClass
            val key = type.getMethod("getKeyCode").invoke(event) as Int
            val action = type.getMethod("getAction").invoke(event) as Int
            report("Vehicle: PAL key=$key action=$action")
            publishInputState("event", key, action)
            if (action != 1) return
            val command = when (key) { 8 -> MediaCommand.PREVIOUS; 9 -> MediaCommand.NEXT; else -> return }
            val signature = "$key:${type.getMethod("getSource").invoke(event)}:" +
                "${type.getMethod("getDownTime").invoke(event)}:${type.getMethod("getEventTime").invoke(event)}"
            if (acceptDirectEvent(signature)) {
                directEvents++
                publishInputState("dispatched", key, action)
                report("Vehicle: wheel command=$command source=PAL_DIRECT")
                target(command)
            }
        }.onFailure { report("Vehicle: PAL event rejected ${it.javaClass.simpleName}") }
    }

    /** Deduplicate repeated deliveries from our own PAL subscription only. */
    @Synchronized
    internal fun acceptDirectEvent(signature: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        recentEvents.entries.removeAll { now - it.value > 2_000 }
        if (recentEvents.containsKey(signature)) return false
        if (recentEvents.size >= 64) recentEvents.remove(recentEvents.keys.first())
        recentEvents[signature] = now
        return true
    }

    private fun publishInputState(state: String, key: Int? = null, action: Int? = null) {
        com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.steeringWheelState = org.json.JSONObject()
            .put("policy", "PAL_DIRECT").put("state", state).put("registered", registeredManager != null)
            .put("accepting", consumer != null).put("directEvents", directEvents)
            .put("lastKey", key ?: org.json.JSONObject.NULL).put("lastAction", action ?: org.json.JSONObject.NULL)
            .put("updatedAtMs", System.currentTimeMillis()).toString()
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
