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
                assertTrue(zip.getInputStream(zip.getEntry("report.txt")).reader().readText().contains(id!!))
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                assertTrue(manifest.contains("unavailable"))
            }
        } finally { file.delete(); CamryAdaptation.save(app, previous) }
    }
}
