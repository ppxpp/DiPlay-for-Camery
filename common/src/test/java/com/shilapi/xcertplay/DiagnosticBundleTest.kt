package com.shilapi.xcertplay

import com.shilapi.xcertplay.pioneer.CamryAdaptation
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import com.shilapi.xcertplay.update.StableClientId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiagnosticBundleTest {
    @Test fun archivePreservesFailuresRedactsSecretsAndKeepsUpdateClientId() {
        val file = Files.createTempFile("diagnostic", ".zip").toFile()
        try {
            DiagnosticArchive(file).use { archive ->
                archive.text("report.txt") { "Update client ID: AbCd12_3-x\npeer=12:34:56:78:90:AB\npassword=secret" }
                archive.text("failed.txt") { error("password=private") }
                archive.text("system/root.txt", "permission_denied") { "uid=10000" }
                archive.component(java.io.File("/system/missing-diplay.so"))
            }
            ZipFile(file).use { zip ->
                val text = zip.getInputStream(zip.getEntry("report.txt")).reader().readText()
                assertTrue(text.contains("AbCd12_3-x"))
                assertFalse(text.contains("secret")); assertFalse(text.contains("12:34:56"))
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText())
                val items = manifest.getJSONArray("items")
                assertEquals(4, items.length())
                assertEquals("success", items.getJSONObject(0).getString("status"))
                assertEquals(64, items.getJSONObject(0).getString("sha256").length)
                assertEquals("unavailable", items.getJSONObject(1).getString("status"))
                assertFalse(manifest.toString().contains("private"))
                assertEquals("permission_denied", items.getJSONObject(2).getString("status"))
                assertEquals("missing", items.getJSONObject(3).getString("status"))
            }
        } finally { file.delete() }
    }

    @Test fun missingRootServiceIsReportedAndRawFilesAreRemoved() {
        val app = RuntimeEnvironment.getApplication()
        val previous = CamryAdaptation.mode(app)
        try {
            CamryAdaptation.save(app, CamryAdaptation.Mode.ON)
            val items = PioneerDiagnosticReader.collect(app)
            assertTrue(items.isNotEmpty())
            assertTrue(items.all { it.status == "unavailable" })
            assertFalse(app.filesDir.listFiles().orEmpty().any { it.name.startsWith("diagnostic-root-") })
        } finally { CamryAdaptation.save(app, previous) }
    }

    @Test fun structuredHudEvidenceSurvivesExportAndRedactsValuesWithoutBreakingJson() {
        val file = Files.createTempFile("hud-evidence", ".zip").toFile()
        val hash = "a".repeat(64)
        val state = JSONObject().put("passed", true).put("status", "pass")
            .put("expectedSha256", hash).put("actualSha256", hash)
            .put("accessToken", "private-token").put("road", "private-road")
            .put("exceptionChain", ("at Method.requirePassed()\n").repeat(50))
            .put("error", "peer=192.168.1.2 password=secret")
        try {
            DiagnosticArchive(file).use { archive ->
                archive.json("hud/state.json") { state }
                archive.jsonLines("hud/recent.ndjson") { state.toString() + "\n" + state.toString() }
            }
            ZipFile(file).use { zip ->
                val exported = JSONObject(zip.getInputStream(zip.getEntry("hud/state.json")).reader().readText())
                assertEquals(hash, exported.getString("actualSha256"))
                assertEquals(hash, exported.getString("expectedSha256"))
                assertTrue(exported.getBoolean("passed")); assertEquals("pass", exported.getString("status"))
                assertFalse(exported.has("accessToken")); assertFalse(exported.has("road"))
                assertTrue(exported.getString("exceptionChain").length > 700)
                assertFalse(exported.toString().contains("password=secret"))
                val lines = zip.getInputStream(zip.getEntry("hud/recent.ndjson")).reader().readLines()
                assertEquals(2, lines.size)
                lines.forEach { assertEquals(hash, JSONObject(it).getString("actualSha256")) }
            }
        } finally { file.delete() }
    }

    @Test fun oversizedStructuredEntryIsExplicitlySkippedInsteadOfCuttingJson() {
        val file = Files.createTempFile("hud-bounds", ".zip").toFile()
        try {
            DiagnosticArchive(file, textLimit = 64).use { archive ->
                archive.json("large.json") { JSONObject().put("reason", "x".repeat(1000)) }
            }
            ZipFile(file).use { zip ->
                assertNull(zip.getEntry("large.json"))
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                assertTrue(manifest.contains("structured_entry_size_limit"))
            }
        } finally { file.delete() }
    }

    @Test fun nonCamryDeviceStillGetsAnArchiveWithExplicitRootLimitations() {
        val app = RuntimeEnvironment.getApplication()
        val previous = CamryAdaptation.mode(app)
        CamryAdaptation.save(app, CamryAdaptation.Mode.OFF)
        val id = StableClientId.get(app)
        val file = DiagnosticBundle.collect(app) { "Update client ID: $id\nOwn app logs" }
        try {
            ZipFile(file).use { zip ->
                assertNotNull(zip.getEntry("README.txt"))
                assertNotNull(zip.getEntry("system/build.txt"))
                assertNotNull(zip.getEntry("hud/state.json"))
                assertNotNull(zip.getEntry("hud/settings.json"))
                assertNotNull(zip.getEntry("hud/recent.ndjson"))
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("components/") })
                assertNull(zip.getEntry("system/component_inventory.txt"))
                assertTrue(zip.getInputStream(zip.getEntry("report.txt")).reader().readText().contains(id!!))
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                assertTrue(manifest.contains("unavailable"))
                assertTrue(manifest.contains("not_requested_for_standard_export"))
            }
        } finally { file.delete(); CamryAdaptation.save(app, previous) }
    }
}
