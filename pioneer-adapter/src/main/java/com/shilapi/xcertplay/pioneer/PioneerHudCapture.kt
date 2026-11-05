package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Opt-in local trace. Two 2 MiB files; disk I/O never runs on the phone or Meter thread. */
object PioneerHudCapture {
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(512), { task -> Thread(task, "pioneer-hud-capture").apply { isDaemon = true } })
    private var app: Context? = null
    @Volatile private var enabled = false
    private val dropped = AtomicLong()
    private var sequence = 0L
    private val recent = ArrayDeque<Pair<Long, String>>()
    @Volatile private var lastError: String? = null
    private const val LIMIT = 2 * 1024 * 1024
    fun isEnabled(): Boolean = enabled

    @Synchronized fun attach(context: Context) {
        if (app != null) return
        app = context.applicationContext
        enabled = false // A previous experiment must not silently re-enable collection after restart.
        event("process_open", JSONObject().put("pid", android.os.Process.myPid()))
    }

    @Synchronized fun status(): JSONObject = JSONObject().put("enabled", enabled)
        .put("droppedEvents", dropped.get()).put("error", lastError ?: JSONObject.NULL)
        .put("directory", "pioneer-hud").put("maxBytesPerFile", LIMIT).put("fileCount", 2)
        .put("latestSeq", sequence).put("oldestSeq", recent.firstOrNull()?.first ?: sequence)

    /** A bounded live cursor avoids repeatedly exporting/reading rotating files during a drive. */
    @Synchronized fun read(afterSeq: Long): JSONObject {
        require(afterSeq >= 0)
        val items = JSONArray()
        var last = afterSeq
        var bytes = 0
        for ((seq, line) in recent) {
            if (seq <= afterSeq) continue
            val size = line.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 128 * 1024) break
            items.put(JSONObject(line)); bytes += size; last = seq
        }
        val oldest = recent.firstOrNull()?.first ?: sequence
        return JSONObject().put("events", items).put("lastSeq", last).put("latestSeq", sequence)
            .put("pid", android.os.Process.myPid()).put("lostEvents", afterSeq < oldest - 1)
            .put("capture", status())
    }

    @Synchronized fun command(context: Context, action: String, label: String): JSONObject {
        check(BuildConfig.DEBUG) { "HUD capture is debug-only" }
        attach(context)
        when (action) {
            "START_CAPTURE", "STOP_CAPTURE" -> {
                val next = action == "START_CAPTURE"
                if (next) enabled = true
                event(if (next) "capture_start" else "capture_stop", JSONObject().put("label", label.take(128)))
                enabled = next
            }
            "MARK" -> { check(enabled) { "Start HUD capture first" }; event("mark", JSONObject().put("label", label.take(128))) }
            "STATUS" -> Unit
            else -> error("Unsupported capture action")
        }
        return status()
    }

    @Synchronized fun event(kind: String, fields: JSONObject = JSONObject()) {
        if (!enabled) return
        val context = app ?: return
        val line = JSONObject().put("timeMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime())
            .put("monoNs", SystemClock.elapsedRealtimeNanos()).put("monoClock", "BOOTTIME")
            .put("seq", ++sequence).put("pid", android.os.Process.myPid())
            .put("event", kind).put("data", fields).toString() + "\n"
        recent.addLast(sequence to line)
        while (recent.size > 1024) recent.removeFirst()
        try {
            io.execute {
                runCatching {
                    synchronized(fileLock) {
                        val dir = File(context.filesDir, "pioneer-hud").apply { mkdirs() }
                        val file = File(dir, "current.ndjson")
                        if (file.length() + line.toByteArray(Charsets.UTF_8).size > LIMIT) {
                            val previous = File(dir, "previous.ndjson")
                            check(!previous.exists() || previous.delete()) { "Cannot rotate HUD trace" }
                            check(file.renameTo(previous)) { "Cannot rotate HUD trace" }
                        }
                        file.appendText(line, Charsets.UTF_8)
                    }
                    lastError = null
                }.onFailure { lastError = it.toString(); dropped.incrementAndGet() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private val fileLock = Any()
    fun archive(context: Context): Map<String, ByteArray> {
        val barrier = CountDownLatch(1)
        io.execute { barrier.countDown() }
        check(barrier.await(3, TimeUnit.SECONDS)) { "HUD capture flush pending; retry export" }
        return synchronized(fileLock) {
            listOf("previous.ndjson", "current.ndjson").mapNotNull { name ->
                File(context.filesDir, "pioneer-hud/$name").takeIf { it.isFile }?.let { name to it.readBytes() }
            }.toMap()
        }
    }
}
