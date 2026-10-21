package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateReleaseLookupTest {
    private lateinit var server: HttpServer
    private lateinit var root: String

    @Before fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        root = "http://${server.address.hostString}:${server.address.port}"
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
    }

    @After fun stopServer() = server.stop(0)

    @Test fun missingChannelIsReportedWithoutFallingBackToStable() {
        server.createContext("/beta/latest") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val result = UpdateReleaseLookup.latest(
            installedVersionCode = 1L,
            userAgent = "DiPlay/test",
            channel = UpdateChannel.BETA,
            openConnection = { URL("$root/beta/latest").openConnection() },
        )

        assertEquals(UpdateLookupResult.ChannelUnavailable, result)
    }

    @Test fun newerManifestAndUpToDateAreDistinguished() {
        server.createContext("/stable/latest") { exchange ->
            val body = manifest(versionCode = 100L).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val opener = { _: URL -> URL("$root/stable/latest").openConnection() }

        val available = UpdateReleaseLookup.latest(99L, "DiPlay/test", UpdateChannel.STABLE, opener)
        assertTrue(available is UpdateLookupResult.Available)
        assertEquals(100L, (available as UpdateLookupResult.Available).release.versionCode)

        assertEquals(
            UpdateLookupResult.UpToDate,
            UpdateReleaseLookup.latest(100L, "DiPlay/test", UpdateChannel.STABLE, opener),
        )
    }

    @Test fun malformedManifestFailsInsteadOfClaimingUpToDate() {
        server.createContext("/stable/latest") { exchange ->
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        try {
            UpdateReleaseLookup.latest(1L, "DiPlay/test", UpdateChannel.STABLE,
                openConnection = { URL("$root/stable/latest").openConnection() })
            throw AssertionError("expected a malformed-manifest failure")
        } catch (_: IOException) {
        }
    }

    private fun manifest(versionCode: Long) = """
        {
          "schemaVersion": 1,
          "version": "0.3.0",
          "versionCode": $versionCode,
          "artifacts": [
            {
              "name": "DiPlay-0.3.0.apk",
              "arch": "universal",
              "url": "https://dl.example.com/DiPlay-0.3.0.apk",
              "checksumsUrl": "https://dl.example.com/SHA256SUMS.txt"
            }
          ]
        }
    """.trimIndent()
}
