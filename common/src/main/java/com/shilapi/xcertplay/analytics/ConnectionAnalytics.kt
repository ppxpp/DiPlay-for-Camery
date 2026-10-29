package com.shilapi.xcertplay.analytics

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.host.BuildConfig
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.progress.*
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import org.json.JSONObject
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Application-only boundary. Protocol callbacks enqueue immutable facts, never SDK/disk work. */
object ConnectionAnalytics {
    const val SOURCE_EXTRA = "diplay_connection_source"
    private const val PREFS = "connection_analytics_v1"
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2048),
        { task -> Thread(task, "connection-analytics").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardPolicy())
    private val ids = AtomicLong()
    private data class Scope(val app: Context, val engine: ConnectionTelemetry,
        var lastWritten: PendingConnection? = null, var lastWriteAt: Long = 0, var privacyEpoch: Long = 0)
    private data class Binding(val scope: Scope, val token: Long)
    @Volatile private var scope: Scope? = null
    private val bindings = WeakHashMap<ConnectionProgress, Binding>()

    @Synchronized fun initialize(context: Context) {
        if (!Analytics.isReporting || !Analytics.isAllowed(context) || scope != null) return
        val app = context.applicationContext
        lateinit var owned: Scope
        val engine = ConnectionTelemetry(SystemClock::elapsedRealtime, { event ->
            if (scope === owned && allowed(app)) {
                if (event.value == null) Analytics.event(event.name, event.attributes)
                else Analytics.metric(event.name, event.value, event.attributes)
            }
        }, { pending ->
            if (scope === owned && allowed(app)) {
                val last = owned.lastWritten
                val now = SystemClock.elapsedRealtime()
                if (pending != null && last != null && pending.localId == last.localId &&
                    pending.stage == last.stage && pending.attempts == last.attempts && pending.timeouts == last.timeouts &&
                    now - owned.lastWriteAt < 1000) return@ConnectionTelemetry
                owned.lastWritten = pending
                owned.lastWriteAt = now
                val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                if (pending == null) editor.remove("pending").commit()
                else editor.putString("pending", encode(pending, owned.privacyEpoch)).commit()
            }
        })
        owned = Scope(app, engine)
        scope = owned
        enqueue(owned) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            owned.privacyEpoch = prefs.getLong("privacy_epoch", 0)
            val raw = prefs.getString("pending", null)
            if (raw != null) {
                val pending = runCatching { decode(raw, owned.privacyEpoch) }.getOrNull()
                if (pending != null) engine.recover(pending) else prefs.edit().remove("pending").commit()
            }
        }
    }

    fun accept(context: Context, wireless: Boolean, source: String, replace: Boolean = false, predecessor: ConnectionProgress? = null) = safe {
        val owned = owned(context) ?: return@safe
        val attrs = initialAttributes(owned.app, wireless)
        val at = SystemClock.elapsedRealtime()
        val previous = synchronized(bindings) { bindings[predecessor]?.takeIf { it.scope === owned }?.token }
        enqueue(owned) { owned.engine.accept(attrs, source, replace, at, previous) }
    }

    fun attach(context: Context, config: CarPlayRuntimeConfig, progress: ConnectionProgress, source: String) = safe {
        val owned = owned(context) ?: return@safe
        val token = ids.incrementAndGet()
        val binding = Binding(owned, token)
        synchronized(bindings) {
            if (bindings.containsKey(progress)) return@safe
            bindings[progress] = binding
        }
        val attrs = initialAttributes(owned.app, config.transport == CarPlayTransport.WIRELESS).toMutableMap().apply {
            put("conn_backend", when (progress.channel) {
                ConnectionChannel.LWIP -> if (config.optimizedLwipEnabled) "lwip_optimized" else "lwip"
                else -> progress.channel.name.lowercase()
            })
            put("mfi_provider", config.mfiTarget.name.lowercase())
            put("hotspot_mode", if (config.transport == CarPlayTransport.WIRELESS) config.wirelessHotspotMode.name.lowercase() else "not_applicable")
        }
        val initial = progress.snapshot()
        val at = SystemClock.elapsedRealtime()
        enqueue(owned) {
            if (owned.engine.nextSource == "external_entry") owned.engine.nextSource = source
            owned.engine.begin(token, attrs, at - initial.elapsedMillis)
        }
        progress.observeTelemetry { snapshot ->
            val observedAt = SystemClock.elapsedRealtime()
            enqueue(owned) { owned.engine.observe(token, snapshot, observedAt) }
        }
    }

    fun retry(progress: ConnectionProgress?) { val at = SystemClock.elapsedRealtime(); binding(progress) { it.scope.engine.retry(it.token, at) } }
    fun failed(progress: ConnectionProgress?, code: String? = null) = safe {
        val at = SystemClock.elapsedRealtime()
        val binding = synchronized(bindings) { bindings[progress] }
        if (binding != null) enqueue(binding.scope) { binding.scope.engine.fail(binding.token, code, at) }
        else if (progress == null) scope?.let { owned -> enqueue(owned) { owned.engine.fail(null, code, at) } }
    }

    fun cancel(progress: ConnectionProgress?, code: String = "user_cancel") = safe {
        val at = SystemClock.elapsedRealtime()
        val binding = synchronized(bindings) { bindings[progress] }
        if (binding != null) {
            val snapshot = progress?.snapshot()
            enqueue(binding.scope) {
                if (snapshot != null) binding.scope.engine.observe(binding.token, snapshot, at)
                binding.scope.engine.cancelOwned(binding.token, code, at)
            }
        }
        else if (progress == null) scope?.let { owned -> enqueue(owned) { owned.engine.cancelOwned(null, code, at) } }
    }

    fun restart(context: Context, wireless: Boolean, progress: ConnectionProgress?, automatic: Boolean, source: String) {
        if (automatic) retry(progress) else accept(context, wireless, source, replace = true, predecessor = progress)
    }

    /** Opt-out invalidates already queued work, drops pending recovery, and retains no old observer owner. */
    @Synchronized fun disable(context: Context) {
        scope = null
        synchronized(bindings) { bindings.clear() }
        val app = context.applicationContext
        // A privacy choice must not depend on queue capacity. Epoch also rejects an old
        // checkpoint whose write raced opt-out; no previously queued request can be recovered.
        runCatching {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().remove("pending").putLong("privacy_epoch", prefs.getLong("privacy_epoch", 0) + 1).commit()
        }
    }

    private fun binding(progress: ConnectionProgress?, action: (Binding) -> Unit) = safe {
        val binding = synchronized(bindings) { bindings[progress] } ?: return@safe
        enqueue(binding.scope) { action(binding) }
    }
    private fun owned(context: Context): Scope? {
        if (!allowed(context)) return null
        initialize(context)
        return scope
    }
    private fun allowed(context: Context) = Analytics.isReporting && Analytics.isAllowed(context)
    private fun enqueue(owned: Scope, action: () -> Unit) {
        worker.execute { if (scope === owned && allowed(owned.app)) runCatching(action) }
    }
    private inline fun safe(action: () -> Unit) { runCatching(action) }

    private fun initialAttributes(app: Context, wireless: Boolean): Map<String, String> {
        val vehicle = PioneerWiredBackend.usesVehicleAdaptation(app)
        val mode = AirPlayPersistence.loadWirelessHotspotMode(app)
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val version = info?.versionName.orEmpty()
        val build = info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) }?.toString() ?: "unknown"
        return mapOf("transport" to if (wireless) "wireless" else "wired",
            "origin_build" to build,
            "requested_backend" to if (wireless) mode.name.lowercase() else PioneerWiredBackend.mode(app).name.lowercase(),
            "conn_backend" to "pending",
            "hotspot_mode" to if (wireless) mode.name.lowercase() else "not_applicable",
            "vehicle_adaptation" to vehicle.toString(),
            "mfi_provider" to AirPlayPersistence.loadMfiTarget(app).name.lowercase(),
            "release_track" to if (BuildConfig.DEBUG) "internal" else if (version.endsWith("-beta")) "beta" else "stable",
            "hotspot_state" to if (wireless) "unknown" else "not_applicable",
            "bt_initial_state" to if (wireless) "unknown" else "not_applicable")
    }

    private fun encode(record: PendingConnection, privacyEpoch: Long): String = JSONObject().apply {
        put("schema", 1); put("privacy_epoch", privacyEpoch); put("localId", record.localId); put("attributes", JSONObject(record.attributes))
        put("attempts", record.attempts); put("timeouts", record.timeouts)
        put("elapsed", record.elapsedMillis); put("attemptElapsed", record.attemptMillis); put("stage", record.stage)
    }.toString()

    private fun decode(raw: String, privacyEpoch: Long): PendingConnection {
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1 && json.optLong("privacy_epoch", 0) == privacyEpoch)
        val values = json.getJSONObject("attributes")
        val allowedKeys = setOf("schema_version", "trigger_source", "process_state", "transport", "requested_backend",
            "conn_backend", "hotspot_mode", "vehicle_adaptation", "mfi_provider", "release_track", "hotspot_state", "bt_initial_state", "wifi_band", "origin_build")
        val attrs = values.keys().asSequence().filter { it in allowedKeys }.associateWith { values.getString(it) }
        require(attrs.values.all { it.length <= 64 && it.matches(Regex("[a-z0-9_]+")) })
        require(attrs["schema_version"] == "1" && attrs["trigger_source"] in ConnectionTelemetry.SOURCES)
        val stage = json.getString("stage")
        require(stage in ConnectionStep.entries.map { it.key })
        return PendingConnection(json.getString("localId"), attrs,
            json.getInt("attempts").also { require(it in 0..10000) },
            json.getInt("timeouts").also { require(it in 0..10000) },
            json.getLong("elapsed").also { require(it >= 0) },
            json.getLong("attemptElapsed").also { require(it >= 0) }, stage)
    }
}
