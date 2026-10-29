package com.shilapi.xcertplay.analytics

import android.content.Context
import com.shilapi.xcertplay.Disclaimer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fake SDK boundary: never loads or initializes Umeng inside Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ConnectionAnalyticsIntegrationTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val events = CopyOnWriteArrayList<ConnectionEvent>()
    private val fake = object : AnalyticsTracker {
        override val isEnabled = true
        override fun start(context: Context) = Unit
        override fun event(name: String, attributes: Map<String, String>) { events += ConnectionEvent(name, attributes) }
        override fun metric(name: String, value: Long, attributes: Map<String, String>) { events += ConnectionEvent(name, attributes, value) }
    }
    private val worker get() = ConnectionAnalytics::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(ConnectionAnalytics) as ThreadPoolExecutor
    private fun tracker(value: AnalyticsTracker) {
        Analytics::class.java.getDeclaredField("tracker").apply { isAccessible = true }.set(Analytics, value)
    }
    private fun drain() { worker.submit {}.get(5, TimeUnit.SECONDS) }
    private fun enableFake() {
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().putBoolean("analytics_disabled", false).commit()
        tracker(fake)
        ConnectionAnalytics.initialize(app)
        drain()
    }
    @Before fun setup() {
        Disclaimer.accept(app)
        app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE).edit().clear().commit()
        enableFake()
    }
    @After fun tearDown() { Analytics.setEnabled(app, false); drain(); tracker(NoopAnalyticsTracker) }

    @Test fun numericValuesReachTheTrackerAsNumbersIncludingZero() {
        Analytics.metric("cp_conn_retry_count", 0, mapOf("result" to "success"))
        assertEquals(0L, events.single().value)
    }

    @Test fun optOutDropsQueuedStartAndNeverBackfillsItAfterReenable() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            ConnectionAnalytics.accept(app, false, "manual")
            Analytics.setEnabled(app, false)
        } finally { release.countDown() }
        drain()
        assertTrue(events.isEmpty())
        assertFalse(app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE).contains("pending"))
        enableFake()
        assertTrue(events.isEmpty())
    }

    @Test fun pendingRequestIsRecoveredOnceAsUnknownRatherThanTimeout() {
        val pendingJson = """{"schema":1,"localId":"local-only","attributes":{"schema_version":"1","trigger_source":"manual","transport":"wired"},"attempts":1,"timeouts":0,"elapsed":100,"attemptElapsed":80,"stage":"device"}"""
        ConnectionAnalytics.disable(app); drain()
        app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE).edit().putString("pending", org.json.JSONObject(pendingJson).put("privacy_epoch",
            app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE).getLong("privacy_epoch", 0)).toString()).commit()
        enableFake()
        ConnectionAnalytics.initialize(app); drain()
        val result = events.single { it.name == "cp_conn_result" }
        assertEquals("interrupted_unknown", result.attributes["result"])
        assertEquals(100L, result.value)
        assertTrue(events.none { it.name == "cp_conn_timeout" })
        assertFalse(app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE).contains("pending"))
        assertTrue(events.none { it.attributes.values.any { value -> value.contains("local-only") } })
    }
    @Test fun optOutInvalidatesTheJournalEvenWhenTheWorkerQueueIsFull() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val prefs = app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE)
        prefs.edit().putString("pending", "old-record").commit()
        try {
            repeat(worker.queue.remainingCapacity()) { worker.queue.add(Runnable {}) }
            Analytics.setEnabled(app, false)
            assertFalse(prefs.contains("pending"))
            assertTrue(prefs.getLong("privacy_epoch", 0) > 0)
        } finally { worker.queue.clear(); release.countDown() }
        drain()
        enableFake()
        assertTrue(events.isEmpty())
    }

    @Test fun aCheckpointRacingOptOutCannotBeRecoveredAfterReenable() {
        val prefs = app.getSharedPreferences("connection_analytics_v1", Context.MODE_PRIVATE)
        val oldEpoch = prefs.getLong("privacy_epoch", 0)
        Analytics.setEnabled(app, false); drain()
        val raced = org.json.JSONObject().put("schema", 1).put("privacy_epoch", oldEpoch)
            .put("localId", "local-only").put("attributes", org.json.JSONObject()
                .put("schema_version", "1").put("trigger_source", "manual"))
            .put("attempts", 1).put("timeouts", 0).put("elapsed", 10).put("attemptElapsed", 10).put("stage", "device")
        prefs.edit().putString("pending", raced.toString()).commit()
        enableFake()
        assertTrue(events.isEmpty())
        assertFalse(prefs.contains("pending"))
    }
}
