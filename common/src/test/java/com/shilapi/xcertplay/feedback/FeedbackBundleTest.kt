package com.shilapi.xcertplay.feedback

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedbackBundleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "logs").deleteRecursively()
        File(context.filesDir, "logs").mkdirs()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("phone_name", "Alice's iPhone")
            .putString("phone_address", "12:34:56:78:90:AB")
            .commit()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "logs").deleteRecursively()
    }

    @Test
    fun bundleContainsRequiredEntriesAndNoPersistentIdentity() {
        File(context.filesDir, "logs/diplay.log").writeText(
            "peer=12:34:56:78:90:AB\npassword=secret\nnormal line\n",
        )
        File(context.filesDir, "logs/last-crash.txt").writeText("crash atEpochMs=1\nnormal crash line\n")
        val output = File(context.cacheDir, "feedback-test.zip")

        FeedbackBundle.build(context, meta(), output)

        ZipFile(output).use { zip ->
            listOf("feedback.json", "report.txt", "manifest.json", "README.txt", "logs/diplay.log")
                .forEach { path -> assertTrue(path, zip.getEntry(path) != null) }
            val feedback = zip.getInputStream(zip.getEntry("feedback.json")).reader().readText()
            val report = zip.getInputStream(zip.getEntry("report.txt")).reader().readText()
            val log = zip.getInputStream(zip.getEntry("logs/diplay.log")).reader().readText()
            assertFalse(feedback.contains("StableClientId"))
            assertFalse(feedback.contains("Alice's iPhone"))
            assertFalse(report.contains("Alice's iPhone"))
            assertFalse(report.contains("12:34:56:78:90:AB"))
            assertFalse(log.contains("12:34:56"))
            assertFalse(log.contains("secret"))
            assertTrue(log.contains("normal line"))
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
            assertEquals(1, manifest.getInt("schema"))
            assertTrue(manifest.getJSONArray("items").length() >= 5)
        }
        assertTrue(output.length() <= FeedbackBundle.MAX_BYTES)
        output.delete()
    }

    @Test
    fun descriptionIsScrubbedOfConfiguredPhoneIdentity() {
        val output = File(context.cacheDir, "feedback-description.zip")
        FeedbackBundle.build(context, meta(description = "Alice's iPhone cannot connect"), output)
        ZipFile(output).use { zip ->
            val feedback = zip.getInputStream(zip.getEntry("feedback.json")).reader().readText()
            assertFalse(feedback.contains("Alice's iPhone"))
            assertTrue(feedback.contains("[device] cannot connect"))
        }
        output.delete()
    }

    private fun meta(description: String = "connection failed") = FeedbackMeta(
        id = "a".repeat(32),
        category = FeedbackCategory.CONNECTION,
        description = description,
        entry = FeedbackEntry.SETTINGS,
        createdAt = System.currentTimeMillis(),
        appVersionName = "0.4.0-test",
        appVersionCode = 4000,
        updateChannel = "beta",
        deviceManufacturer = "Test",
        deviceModel = "Head Unit",
        deviceBoard = "test-board",
        androidRelease = "11",
        androidApi = 30,
        sessionMode = "wireless",
        sessionState = "failed",
    )
}
