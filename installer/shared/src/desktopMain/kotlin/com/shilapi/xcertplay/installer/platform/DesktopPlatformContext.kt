package com.shilapi.xcertplay.installer.platform

import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.adb.DesktopAdbController
import com.shilapi.xcertplay.installer.update.AppUpdate
import java.awt.Desktop
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser

class DesktopPlatformContext : PlatformContext {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    private val isMac = osName.contains("mac")
    private val isWindows = osName.contains("win")

    private val appDir: File by lazy { resolveAppDir() }
    private val dataDir: File by lazy { resolveDataDir() }

    override val isDesktop: Boolean = true
    override val isDebuggable: Boolean = false
    override val appVersionName: String = INSTALLER_VERSION_NAME
    override val appVersionCode: Long = INSTALLER_VERSION_CODE
    override val deviceId: String by lazy { resolveDeviceId() }
    override val updatePlatform: String = if (isMac) "macos" else "windows"

    override val filesDir: File = dataDir
    override val cacheDir: File = File(dataDir, "cache").apply { mkdirs() }
    override val payloadDir: File = File(dataDir, "payload").apply { mkdirs() }
    override val defaultBackupDir: File = resolveDefaultBackupDir()
    override val settingsFile: File = File(dataDir, "settings.json")
    override val updatesDir: File = File(cacheDir, "updates").apply { mkdirs() }

    override val supportsBackupDirectoryPicker: Boolean = true
    override val supportsUsbDriverHelp: Boolean = isWindows

    override fun createAdbController(): AdbController =
        DesktopAdbController(this)

    override fun openUsbDriverHelp(): Boolean {
        if (!isWindows) return false
        return runCatching {
            ProcessBuilder("cmd", "/c", "start", "", "devmgmt.msc").start()
            true
        }.getOrDefault(false)
    }

    override suspend fun checkUpdate(currentVersionCode: Long): AppUpdate? = null

    override suspend fun downloadUpdate(
        update: AppUpdate,
        onProgress: (Int) -> Unit,
    ): File? = null

    override suspend fun installUpdate(file: File): Result<Unit> =
        Result.failure(UnsupportedOperationException("桌面版不提供自动更新"))

    override fun pickDirectory(initial: File?): File? {
        val chooser = JFileChooser(initial?.takeIf { it.isDirectory } ?: defaultBackupDir).apply {
            dialogTitle = "选择备份目录"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }

    override fun openDirectory(dir: File): Boolean = runCatching {
        if (!dir.exists()) dir.mkdirs()
        Desktop.getDesktop().open(dir)
        true
    }.getOrDefault(false)

    private fun resolveDefaultBackupDir(): File {
        val home = File(System.getProperty("user.home"))
        val desktop = when {
            isMac -> File(home, "Desktop")
            isWindows -> {
                val oneDriveDesktop = home.listFiles()
                    ?.asSequence()
                    ?.filter { it.isDirectory && it.name.startsWith("OneDrive", ignoreCase = true) }
                    ?.map { File(it, "Desktop") }
                    ?.firstOrNull { it.isDirectory }
                oneDriveDesktop ?: File(home, "Desktop")
            }
            else -> File(home, "Desktop")
        }
        return File(desktop, BACKUP_FOLDER_NAME).apply { mkdirs() }
    }

    private fun resolveAppDir(): File {
        val command = ProcessHandle.current().info().command().orElse(null)
        return command?.let { File(it).parentFile } ?: File(System.getProperty("user.dir"))
    }

    private fun resolveDataDir(): File {
        val portable = System.getProperty("diplay.portable") == "true" ||
            File(appDir, "portable").exists()
        return if (portable) {
            File(appDir, "data").apply { mkdirs() }
        } else if (isWindows) {
            val base = System.getenv("LOCALAPPDATA")
                ?: File(System.getProperty("user.home"), "AppData/Local").absolutePath
            File(base, "DiPlayInstaller").apply { mkdirs() }
        } else if (isMac) {
            File(System.getProperty("user.home"), "Library/Application Support/DiPlayInstaller")
                .apply { mkdirs() }
        } else {
            File(System.getProperty("user.home"), ".diplay-installer").apply { mkdirs() }
        }
    }

    private fun resolveDeviceId(): String {
        val file = File(dataDir, "device-id")
        if (file.isFile) {
            val existing = runCatching { file.readText().trim() }.getOrDefault("")
            if (existing.isNotBlank()) return existing
        }
        val id = UUID.randomUUID().toString()
        runCatching { file.writeText(id) }
        return id
    }
}

private const val INSTALLER_VERSION_NAME = "0.2.0"
private const val INSTALLER_VERSION_CODE = 2L
private const val BACKUP_FOLDER_NAME = "DiPlay 备份"
