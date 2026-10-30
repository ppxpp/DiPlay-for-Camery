package com.shilapi.xcertplay.installer.adb

import java.io.File
import java.util.concurrent.TimeUnit

internal class AdbExec(
    private val binary: File,
    private val environment: Map<String, String>,
    private val serial: String? = null,
) {
    fun run(vararg args: String, timeoutSeconds: Long = 120): String {
        val command = buildList {
            add(binary.absolutePath)
            serial?.let {
                add("-s")
                add(it)
            }
            addAll(args)
        }
        return execute(command, environment, timeoutSeconds)
    }
}

internal fun execute(
    command: List<String>,
    environment: Map<String, String>,
    timeoutSeconds: Long,
): String {
    val process = try {
        ProcessBuilder(command).apply {
            environment().putAll(environment)
            redirectErrorStream(true)
        }.start()
    } catch (error: Exception) {
        throw IllegalStateException("无法启动 adb：${error.message}", error)
    }

    val output = StringBuilder()
    val reader = Thread {
        runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(output) { output.append(buffer, 0, read) }
                }
            }
        }
    }.apply {
        isDaemon = true
        name = "adb-output-reader"
        start()
    }

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        reader.join(1000)
        throw IllegalStateException("adb 命令超时：${command.joinToString(" ")}")
    }
    reader.join(2000)
    return synchronized(output) { output.toString() }
}

internal object DesktopAdbPaths {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    private val binaryName = if (isWindows) "adb.exe" else "adb"

    fun resolve(): File? {
        val envPath = System.getenv("ADB_PATH")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        if (envPath != null && envPath.isFile) return envPath

        val resourcesDir = System.getProperty("compose.application.resources.dir")
        if (!resourcesDir.isNullOrBlank()) {
            val bundled = File(File(resourcesDir, "platform-tools"), binaryName)
            if (bundled.isFile) return bundled
        }

        val projectRelative = File(
            System.getProperty("user.dir"),
            "installer/packaging/platform-tools/${if (isMac) "macos" else "windows"}/$binaryName",
        )
        if (projectRelative.isFile) return projectRelative

        val path = System.getenv("PATH").orEmpty()
        return path.split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, binaryName) }
            .firstOrNull { it.isFile }
    }
}
