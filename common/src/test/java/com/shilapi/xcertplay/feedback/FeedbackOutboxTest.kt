package com.shilapi.xcertplay.feedback

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedbackOutboxTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clear()
    }

    @After
    fun tearDown() {
        clear()
    }

    @Test
    fun enqueueRejectsSixthPendingRecord() {
        repeat(FeedbackOutbox.MAX_PENDING) { index ->
            FeedbackOutbox.enqueue(context, meta(index), bundle())
        }
        var rejected = false
        try {
            FeedbackOutbox.enqueue(context, meta(99), bundle())
        } catch (_: FeedbackQueueFullException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(FeedbackOutbox.MAX_PENDING, FeedbackOutbox.pending(context).size)
    }

    @Test
    fun completedUploadMovesRecordToSentHistoryWithoutDescription() {
        val record = FeedbackOutbox.enqueue(context, meta(1, "secret description"), bundle())
        FeedbackOutbox.markSent(context, record.meta.id)

        assertNull(FeedbackOutbox.record(context, record.meta.id))
        val sent = FeedbackOutbox.sentHistory(context)
        assertEquals(1, sent.size)
        assertEquals(record.meta.id, sent.single().id)
        assertEquals(FeedbackCategory.OTHER, sent.single().category)
        val stored = File(context.filesDir, "feedback-sent.json").readText()
        assertFalse(stored.contains("secret description"))
    }

    @Test
    fun permanentFailureDoesNotBlockPendingQuota() {
        val records = (0 until FeedbackOutbox.MAX_PENDING).map { index ->
            FeedbackOutbox.enqueue(context, meta(index), bundle())
        }
        FeedbackOutbox.markPermanent(context, records.first().meta.id, "rejected")

        assertEquals(FeedbackOutbox.MAX_PENDING - 1, FeedbackOutbox.pending(context).size)
        assertEquals(1, FeedbackOutbox.permanentFailures(context).size)
        val extra = FeedbackOutbox.enqueue(context, meta(99), bundle())
        assertEquals(extra.meta.id, FeedbackOutbox.pending(context).last().meta.id)
    }

    @Test
    fun sentHistoryKeepsOnlyTenNewestRecords() {
        repeat(FeedbackOutbox.MAX_SENT + 2) { index ->
            val record = FeedbackOutbox.enqueue(context, meta(index), bundle())
            FeedbackOutbox.markSent(context, record.meta.id)
        }
        assertEquals(FeedbackOutbox.MAX_SENT, FeedbackOutbox.sentHistory(context).size)
    }

    @Test
    fun deleteAndClearSentRecords() {
        val first = FeedbackOutbox.enqueue(context, meta(1), bundle())
        val second = FeedbackOutbox.enqueue(context, meta(2), bundle())
        FeedbackOutbox.markSent(context, first.meta.id)
        FeedbackOutbox.markSent(context, second.meta.id)
        assertEquals(2, FeedbackOutbox.sentHistory(context).size)

        FeedbackOutbox.deleteSent(context, first.meta.id)
        assertEquals(1, FeedbackOutbox.sentHistory(context).size)
        FeedbackOutbox.clearSent(context)
        assertTrue(FeedbackOutbox.sentHistory(context).isEmpty())
    }

    private fun meta(index: Int, description: String = "description") = FeedbackMeta(
        id = index.toString().padStart(32, '0'),
        category = FeedbackCategory.OTHER,
        description = description,
        entry = FeedbackEntry.SETTINGS,
        createdAt = System.currentTimeMillis() + index,
    )

    private fun bundle(): File = File(context.cacheDir, "bundle-${System.nanoTime()}.zip").apply {
        writeText("bundle")
    }

    private fun clear() {
        File(context.filesDir, "feedback-outbox").deleteRecursively()
        File(context.filesDir, "feedback-sent.json").delete()
    }
}
