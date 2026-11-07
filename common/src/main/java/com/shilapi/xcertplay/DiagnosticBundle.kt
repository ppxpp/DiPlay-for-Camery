package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.shilapi.xcertplay.pioneer.PioneerDiagnosticReader
import com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.PioneerVehicleWireless
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Generates a private immutable archive. The HTTP service streams this file, not a giant byte array. */
internal object DiagnosticBundle {
    @Synchronized fun collect(context: Context, includeSystemComponents: Boolean = false, report: () -> String): File {
        val app = context.applicationContext
        val directory = File(app.cacheDir, "diagnostic-bundles").apply { check(isDirectory || mkdirs()) }
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.forEachIndexed { index, old -> if (index >= 2 || System.currentTimeMillis() - old.lastModified() > 86_400_000) old.delete() }
        val file = File.createTempFile("diplay-diagnostics-", ".zip", directory)
        try {
            DiagnosticArchive(file).use { archive ->
                archive.text("report.txt") {
                    val flushed = AsyncDiagnosticLog.awaitIdle(2_000)
                    "Log flush complete=$flushed\n" + report()
                }
                archive.text("system/build.txt") {
                    "manufacturer=${Build.MANUFACTURER}\nmodel=${Build.MODEL}\n" +
                        "build=${Build.DISPLAY}\nfingerprint=${Build.FINGERPRINT}\nandroid=${Build.VERSION.RELEASE}\napi=${Build.VERSION.SDK_INT}\nabis=${Build.SUPPORTED_ABIS.joinToString()}\n"
                }
                archive.json("hud/state.json") {
                    val state = com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.hudSnapshot()
                    state.put("snapshotAvailable", state.length() > 0)
                        .put("adaptationMode", com.shilapi.xcertplay.pioneer.CamryAdaptation.mode(app).name)
                        .put("adaptationEnabled", com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled(app))
                        .put("snapshotAgeMs", state.optLong("updatedAtMs").takeIf { it > 0 }?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: JSONObject.NULL)
                        .put("exportedAtMs", System.currentTimeMillis())
                }
                archive.json("hud/settings.json") {
                    val config = com.shilapi.xcertplay.hud.CamryHudSettings.load(app)
                    JSONObject().put("enabled", config.enabled).put("strategy", config.strategy.name)
                        .put("fixedMeters", config.fixedMeters).put("turnMeters", config.turnMeters)
                        .put("complexMeters", config.complexMeters).put("straightMeters", config.straightMeters)
                        .put("advance", config.advance.name)
                }
                archive.jsonLines("hud/recent.ndjson") { com.shilapi.xcertplay.pioneer.PioneerHudDiagnostics.recent() }
                runCatching { PioneerVehicleWireless.diagnosticSnapshot(DiPlayPreferences.phoneAddress(app)) }.fold({ state ->
                    val status = if (state.contains("status=complete")) "success" else "incomplete"
                    archive.text("bluetooth/state.txt", status) { state }
                }, { archive.unavailable("bluetooth/state.txt", "unavailable", it.javaClass.simpleName) })
                runCatching { PioneerDiagnosticReader.collect(app) }.fold({ items ->
                    items.forEach {
                        val path = if (it.name == "hud_firmware") "hud/firmware.json" else "system/${it.name}.txt"
                        if (it.name == "hud_firmware") archive.json(path, it.status) {
                            runCatching { JSONObject(it.text) }.getOrElse { error ->
                                JSONObject().put("status", it.status).put("error", error.toString()).put("detail", it.text)
                            }
                        } else archive.text(path, it.status) { it.text }
                    }
                    if (items.none { it.name == "hud_firmware" })
                        archive.unavailable("hud/firmware.json", "unavailable", "firmware_check_not_available")
                }, {
                    archive.unavailable("system/root", "collection_failed", it.javaClass.simpleName)
                    archive.unavailable("hud/firmware.json", "unavailable", "root_collection_failed")
                })
                if (includeSystemComponents) appendSystemComponents(app, archive)
                else archive.unavailable("components", "skipped", "not_requested_for_standard_export")
                archive.text("README.txt") {
                    "DiPlay diagnostic bundle\nSee manifest.json for collection status, source paths, byte counts and SHA-256.\n" +
                        "Missing/denied/timeout entries are not successful collections. System logcat is a filtered current buffer, not a complete boot history.\n" +
                        "Bluetooth/Blueforce system components are intentionally excluded; bluetooth/state.txt is a read-only state snapshot.\n" +
                        "System APK/JAR/ODEX and native binaries are excluded unless explicitly requested.\n" +
                        "hud/state.json, settings.json and recent.ndjson describe the current process; firmware.json is a read-only export check, not proof startup passed.\n" +
                        "No settings database, pairing keys, hotspot passwords or protocol payloads are intentionally exported.\n"
                }
            }
            // Validate structure and decompress every entry before offering a successful download.
            ZipFile(file).use { zip ->
                check(zip.getEntry("manifest.json") != null && zip.getEntry("README.txt") != null)
                zip.entries().asSequence().forEach { entry -> zip.getInputStream(entry).use { it.copyTo(NullOutput) } }
            }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    /** Explicit engineering opt-in; standard exports never enumerate or copy OS binaries. */
    private fun appendSystemComponents(app: Context, archive: DiagnosticArchive) {
        // Read only OS-shipped code, never Bluetooth databases, hotspot configuration or app data.
        // Bluetooth/Blueforce binaries are intentionally excluded; bluetooth/state.txt keeps the
        // read-only state snapshot without copying the vendor stack or its compiled code.
        val libraries = listOf("/system/lib", "/system/lib64", "/vendor/lib", "/vendor/lib64", "/system/framework")
        val components = libraries.flatMap { directory ->
            File(directory).listFiles()
                ?.filter { file -> file.isFile && DiagnosticComponentPolicy.isSelectedLibrary(file.name) }
                .orEmpty()
        }.toMutableList()
        DiagnosticComponentPolicy.explicitFiles.forEach { components += File(it) }
        @Suppress("DEPRECATION")
        val packages = runCatching { app.packageManager.getInstalledApplications(0) }
        packages.onSuccess { installed ->
            val selected = installed.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                DiagnosticComponentPolicy.isSelectedPackage(it.packageName) }
            archive.text("system/packages.txt") {
                selected.joinToString("\n") { info ->
                    val version = runCatching { app.packageManager.getPackageInfo(info.packageName, 0).versionName }.getOrNull()
                    "package=${info.packageName} version=$version source=${info.sourceDir}"
                }
            }
            selected.forEach { info ->
                    info.sourceDir?.let { components += File(it) }
                    info.splitSourceDirs?.forEach { components += File(it) }
                }
        }.onFailure { archive.unavailable("components/package_discovery", "unavailable", it.javaClass.simpleName) }
        val codeFiles = components.toList()
        codeFiles.filter { it.extension in setOf("jar", "apk") }.forEach { code ->
            listOf("arm", "arm64").forEach { arch ->
                listOf("odex", "vdex").forEach { extension ->
                    val compiled = File(code.parentFile, "oat/$arch/${code.nameWithoutExtension}.$extension")
                    if (compiled.isFile) components += compiled
                }
            }
        }
        val candidates = components.distinctBy { it.absolutePath }.sortedWith(
            compareBy<File> { if (it.extension in setOf("so", "jar")) 1 else 2 }
                .thenBy { it.absolutePath })
        archive.text("system/component_inventory.txt") { candidates.joinToString("\n") { it.absolutePath } }
        candidates.take(60).forEach { archive.component(it) }
        candidates.drop(60).forEach { archive.unavailable("components" + it.absolutePath, "skipped", "component_count_limit") }
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }
}

/** Bounded, streaming archive writer with a truthful per-item manifest. */
internal class DiagnosticArchive(
    file: File,
    private val textLimit: Int = TEXT_LIMIT,
    private val componentLimit: Long = COMPONENT_LIMIT,
    private val totalLimit: Long = TOTAL_LIMIT,
) : java.io.Closeable {
    private val zip = ZipOutputStream(file.outputStream().buffered())
    private val manifest = JSONArray()
    private var written = 0L
    fun unavailable(path: String, status: String, reason: String) {
        manifest.put(JSONObject().put("path", path).put("status", status).put("reason", reason))
    }
    fun text(path: String, status: String = "success", content: () -> String) {
        val value = runCatching(content).getOrElse { unavailable(path, "unavailable", it.javaClass.simpleName); return }
        val safe = value.lineSequence().mapNotNull(DiagnosticRedactor::redact).joinToString("\n")
        val bytes = safe.toByteArray(Charsets.UTF_8)
        val bounded = bytes.copyOf(minOf(bytes.size, textLimit))
        if (written + bounded.size > totalLimit) { unavailable(path, "skipped", "archive_size_limit"); return }
        zip.putNextEntry(ZipEntry(path)); zip.write(bounded); zip.closeEntry()
        written += bounded.size
        manifest.put(JSONObject().put("path", path).put("status", if (bytes.size > textLimit) "truncated" else status)
            .put("bytes", bounded.size).put("sha256", digest(bounded)))
    }
    fun json(path: String, status: String = "success", content: () -> JSONObject) {
        structured(path, status) { HudDiagnosticJson.sanitize(content()).toString() }
    }
    fun jsonLines(path: String, status: String = "success", content: () -> String) {
        structured(path, status) {
            content().lineSequence().filter { it.isNotBlank() }
                .joinToString("\n") { HudDiagnosticJson.sanitize(JSONObject(it)).toString() }
        }
    }
    private fun structured(path: String, status: String, content: () -> String) {
        val bytes = runCatching { content().toByteArray(Charsets.UTF_8) }.getOrElse {
            unavailable(path, "unavailable", it.javaClass.simpleName); return
        }
        // Never cut machine-readable evidence in the middle of JSON or a UTF-8 character.
        if (bytes.size > textLimit) { unavailable(path, "skipped", "structured_entry_size_limit"); return }
        raw(path, bytes, status)
    }
    /** Writes content without line redaction; used for machine-readable metadata and the README. */
    fun raw(path: String, content: ByteArray, status: String = "success") {
        val bounded = content.copyOf(minOf(content.size, textLimit))
        if (written + bounded.size > totalLimit) { unavailable(path, "skipped", "archive_size_limit"); return }
        zip.putNextEntry(ZipEntry(path)); zip.write(bounded); zip.closeEntry()
        written += bounded.size
        manifest.put(JSONObject().put("path", path).put("status", if (content.size > textLimit) "truncated" else status)
            .put("bytes", bounded.size).put("sha256", digest(bounded)))
    }
    fun component(file: File) {
        val path = "components" + file.absolutePath
        if (!file.isFile) { unavailable(path, "missing", "not_present"); return }
        val source = runCatching { file.canonicalFile }.getOrNull()
        if (source == null || listOf("/system/", "/vendor/", "/product/", "/system_ext/").none { source.path.startsWith(it) }) {
            unavailable(path, "skipped", "outside_system_partition"); return
        }
        val length = file.length()
        if (length > componentLimit || written + length > totalLimit) { unavailable(path, "skipped", "size_limit"); return }
        val input = runCatching { file.inputStream() }.getOrElse { unavailable(path, "permission_denied", it.javaClass.simpleName); return }
        input.use {
            val hash = MessageDigest.getInstance("SHA-256")
            zip.putNextEntry(ZipEntry(path))
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            var failed = false
            while (count < length) {
                val n = try { it.read(buffer, 0, minOf(buffer.size.toLong(), length - count).toInt()) }
                    catch (_: java.io.IOException) { failed = true; break }
                if (n < 0) break
                zip.write(buffer, 0, n); hash.update(buffer, 0, n); count += n
            }
            zip.closeEntry(); written += count
            manifest.put(JSONObject().put("path", path).put("source", file.absolutePath).put("bytes", count)
                .put("status", if (failed) "read_failed" else if (count == length && file.length() == length) "success" else "truncated")
                .put("sha256", hex(hash.digest())))
        }
    }
    override fun close() {
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(JSONObject().put("schema", 1).put("createdAt", System.currentTimeMillis()).put("items", manifest)
            .toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry(); zip.close()
    }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    companion object {
        private const val TEXT_LIMIT = 4 * 1024 * 1024
        private const val COMPONENT_LIMIT = 32L * 1024 * 1024
        private const val TOTAL_LIMIT = 96L * 1024 * 1024
    }
}
