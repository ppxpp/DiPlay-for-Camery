package com.shilapi.xcertplay.feedback

import android.content.Context
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedbackUploaderTest {
    private var server: HttpServer? = null
    private lateinit var context: Context

    @After
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun sendsMultipartAndAcceptsCreated() {
        context = RuntimeEnvironment.getApplication()
        val received = AtomicReference<ByteArray>()
        val endpoint = start(201, """{"ok":true,"id":"${"a".repeat(32)}"}""") {
            received.set(it)
        }

        val result = upload(endpoint)

        assertTrue(result is FeedbackUploader.Result.Sent)
        assertEquals("a".repeat(32), (result as FeedbackUploader.Result.Sent).id)
        val body = String(received.get(), Charsets.UTF_8)
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body.contains("name=\"bundle\""))
        assertTrue(body.contains("filename=\"diplay-feedback-${meta().id}.zip\""))
        assertTrue(body.contains("connection failed"))
    }

    @Test
    fun triesDefaultRouteEvenWhenAndroidReportsNoNetworks() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).clearAllNetworks()
        org.robolectric.Shadows.shadowOf(manager).setActiveNetworkInfo(null)
        val endpoint = start(201, """{"ok":true}""") { }
        assertTrue(upload(endpoint, useFeedbackRoute = true) is FeedbackUploader.Result.Sent)
    }

    @Test
    fun connectionFailurePreservesActualFailureType() {
        context = RuntimeEnvironment.getApplication()
        val bundle = File(context.cacheDir, "failed-open.zip").apply { writeText("zip") }
        try {
            val result = FeedbackUploader.upload(context, meta(), bundle,
                openConnection = { throw java.net.UnknownHostException("test") })
            assertEquals(FeedbackUploader.Result.Retryable("UnknownHostException"), result)
        } finally { bundle.delete() }
    }

    @Test
    fun treatsRateLimitAsRetryable() {
        context = RuntimeEnvironment.getApplication()
        val endpoint = start(429, """{"error":"too_many"}""") { }
        assertTrue(upload(endpoint) is FeedbackUploader.Result.Retryable)
    }

    @Test
    fun treatsOtherClientErrorsAsPermanent() {
        context = RuntimeEnvironment.getApplication()
        val endpoint = start(400, """{"error":"invalid_request"}""") { }
        assertTrue(upload(endpoint) is FeedbackUploader.Result.Permanent)
    }

    private fun upload(endpoint: URL, useFeedbackRoute: Boolean = false): FeedbackUploader.Result {
        val bundle = File(context.cacheDir, "upload-${System.nanoTime()}.zip").apply { writeText("zip bytes") }
        return try {
            FeedbackUploader.upload(
                context = context,
                meta = meta(),
                bundle = bundle,
                endpoint = endpoint,
                openConnection = { if (useFeedbackRoute) FeedbackConnectivity.openConnection(context, it) else it.openConnection() },
            )
        } finally {
            bundle.delete()
        }
    }

    private fun start(status: Int, response: String, onBody: (ByteArray) -> Unit): URL {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/feedback") { exchange ->
            val body = exchange.requestBody.readBytes()
            onBody(body)
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return URL("http://127.0.0.1:${http.address.port}/feedback")
    }

    private fun meta() = FeedbackMeta(
        id = "b".repeat(32),
        category = FeedbackCategory.CONNECTION,
        description = "connection failed",
        entry = FeedbackEntry.SETTINGS,
        createdAt = System.currentTimeMillis(),
        appVersionName = "0.4.0-test",
        appVersionCode = 4000,
        updateChannel = "beta",
    )
}
