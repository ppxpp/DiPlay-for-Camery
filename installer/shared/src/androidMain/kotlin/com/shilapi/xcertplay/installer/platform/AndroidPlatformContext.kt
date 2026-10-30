package com.shilapi.xcertplay.installer.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.adb.AndroidAdbController
import com.shilapi.xcertplay.installer.update.AppUpdate
import com.shilapi.xcertplay.installer.update.UpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPlatformContext(
    private val context: Context,
) : PlatformContext {
    private val applicationContext = context.applicationContext

    override val isDesktop: Boolean = false
    override val isDebuggable: Boolean =
        (applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    override val appVersionName: String
    override val appVersionCode: Long

    init {
        val info = runCatching {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        }.getOrNull()
        appVersionName = info?.versionName.orEmpty()
        appVersionCode = if (info == null) {
            0L
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    override val deviceId: String =
        Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
    override val updatePlatform: String = "android"

    override val filesDir: File = applicationContext.filesDir
    override val cacheDir: File = applicationContext.cacheDir
    override val payloadDir: File = File(filesDir, "payload").apply { mkdirs() }
    override val defaultBackupDir: File = File(filesDir, "backups").apply { mkdirs() }
    override val settingsFile: File = File(filesDir, "settings.json")
    override val updatesDir: File = File(cacheDir, "updates").apply { mkdirs() }

    override val supportsBackupDirectoryPicker: Boolean = false
    override val supportsUsbDriverHelp: Boolean = false

    private val updateClient by lazy { UpdateClient(this) }

    override fun createAdbController(): AdbController =
        AndroidAdbController(applicationContext)

    override fun openUsbDriverHelp(): Boolean = false

    override suspend fun checkUpdate(currentVersionCode: Long): AppUpdate? =
        updateClient.check(currentVersionCode)

    override suspend fun downloadUpdate(
        update: AppUpdate,
        onProgress: (Int) -> Unit,
    ): File? = updateClient.download(update, onProgress)

    override suspend fun installUpdate(file: File): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            applicationContext.startActivity(intent)
        }
    }

    override fun pickDirectory(initial: File?): File? = null

    override fun openDirectory(dir: File): Boolean = false
}
