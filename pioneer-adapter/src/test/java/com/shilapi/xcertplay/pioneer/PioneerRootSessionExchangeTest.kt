package com.shilapi.xcertplay.pioneer

import android.net.Credentials
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE, shadows = [PioneerRootSessionExchangeTest.SocketShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PioneerRootSessionExchangeTest {
    companion object {
        var mode = "eof"
        var peerUid = 0
        var cancelled = CountDownLatch(1)
        val endpoints = CopyOnWriteArrayList<String>()
        private fun status(name: String) = JSONObject().put("socket", name).put("pid", 77).put("token", "owner-token")
        private fun cached(): JSONObject? = PioneerRootSession.javaClass.getDeclaredField("cachedReady")
            .apply { isAccessible = true }.get(PioneerRootSession) as JSONObject?
        private fun cache(value: JSONObject?) {
            PioneerRootSession.javaClass.getDeclaredField("cachedReady").apply { isAccessible = true }.set(PioneerRootSession, value)
        }
    }
    @Implements(LocalSocket::class)
    class SocketShadow {
        private val output = ByteArrayOutputStream()
        private var input: InputStream? = null
        @Implementation fun connect(address: LocalSocketAddress) { endpoints += address.name }
        @Implementation fun setSoTimeout(timeout: Int) = Unit
        @Implementation fun getPeerCredentials(): Credentials = Credentials(77, peerUid, 0)
        @Implementation fun getOutputStream(): OutputStream = output
        @Implementation fun getInputStream(): InputStream {
            input?.let { return it }
            val request = PioneerRootIpc.Reader(PioneerRootIpc.MAX_REQUEST_BYTES).read(ByteArrayInputStream(output.toByteArray()))
            val cancel = request.getString("operation") == "CANCEL"
            if (cancel) {
                assertTrue(request.getJSONObject("payload").getString("requestId").isNotBlank())
                cancelled.countDown()
            }
            if (!cancel && mode == "eof") {
                cache(status("replacement-owner")) // A concurrent restart cannot redirect cancellation.
                return ByteArrayInputStream(ByteArray(0)).also { input = it }
            }
            val response = JSONObject().put("version", 1).put("id", request.getString("id"))
                .put("type", "result").put("ok", cancel || mode == "success")
                .put("completion", if (mode == "unknown") "UNKNOWN" else "NOT_STARTED")
                .put("error", "test failure").put("result", JSONObject().put("cancelled", cancel))
            val bytes = ByteArrayOutputStream()
            PioneerRootIpc.write(bytes, response, PioneerRootIpc.MAX_RESPONSE_BYTES)
            return ByteArrayInputStream(bytes.toByteArray()).also { input = it }
        }
        @Implementation fun close() = Unit
    }
    @Before fun before() { endpoints.clear(); mode = "eof"; peerUid = 0; cancelled = CountDownLatch(1); cache(null) }
    @After fun after() { cache(null) }
    private fun exchange(): JSONObject {
        val method = PioneerRootSession.javaClass.getDeclaredMethod("exchange", JSONObject::class.java,
            String::class.java, JSONObject::class.java, java.lang.Long.TYPE, kotlin.jvm.functions.Function0::class.java)
        method.isAccessible = true
        try { return method.invoke(PioneerRootSession, status("original-owner"), "HOTSPOT", JSONObject(), 1500L, { true }) as JSONObject }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    @Test fun missingAcceptanceIsUnknownAndCancellationUsesOriginalEndpointWithoutBootstrap() {
        assertThrows(PioneerRootIpc.CompletionUnknown::class.java) { exchange() }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("original-owner", "original-owner"), endpoints.toList())
        assertEquals("replacement-owner", cached()?.getString("socket"))
    }
    @Test fun confirmedRejectionDoesNotBecomeUnknownOrCancelAnotherTask() {
        mode = "rejected"
        val error = assertThrows(IllegalStateException::class.java) { exchange() }
        assertTrue(error.message!!.contains("NOT_STARTED"))
        assertEquals(1L, cancelled.count)
        assertEquals(listOf("original-owner"), endpoints.toList())
    }
    @Test fun confirmedUnknownFailureRemainsStructuredAndDoesNotLoseItsMeaning() {
        mode = "unknown"
        assertThrows(PioneerRootIpc.CompletionUnknown::class.java) { exchange() }
        assertEquals(1L, cancelled.count)
    }
    @Test fun rejectsWrongPeerBeforeSendingAnyMutation() {
        peerUid = 10001
        cache(status("original-owner"))
        val error = assertThrows(IllegalStateException::class.java) { exchange() }
        assertTrue(error.message!!.contains("ROOT_IPC_PEER_CHANGED"))
        assertNull(cached())
        assertEquals(1L, cancelled.count)
    }
    @Test fun deadReadyCacheIsRetiredBeforeBusinessSubmission() {
        cache(status("dead-owner").put("state", "READY").put("pid", -1))
        val method = PioneerRootSession.javaClass.getDeclaredMethod("liveCached").apply { isAccessible = true }
        assertNull(method.invoke(PioneerRootSession))
        assertNull(cached())
        assertTrue(endpoints.isEmpty())
    }
    @Test fun bootstrapExpiryDoesNotExtendForLateWaiters() {
        val pending = JSONObject().put("state", "PENDING").put("requestedAtMs", 1000L).put("expiresAt", 61_000L)
        assertTrue(PioneerRootSession.bootstrapPending(pending, 1000))
        assertTrue(PioneerRootSession.bootstrapPending(pending, 60_000))
        assertFalse(PioneerRootSession.bootstrapPending(pending, 61_000))
        assertFalse(PioneerRootSession.bootstrapPending(pending, 62_000))
        assertFalse(PioneerRootSession.bootstrapPending(pending, 0))
        pending.put("expiresAt", 120_000L)
        assertFalse(PioneerRootSession.bootstrapPending(pending, 1000))
    }
    @Test fun confirmedSuccessReturnsWithoutReplay() {
        mode = "success"
        assertFalse(exchange().getBoolean("cancelled"))
        assertEquals(listOf("original-owner"), endpoints.toList())
    }
}
