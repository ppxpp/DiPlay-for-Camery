package com.shilapi.xcertplay.installer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.shilapi.xcertplay.installer.settings.SettingsStore
import com.shilapi.xcertplay.installer.ui.InstallerApp
import com.shilapi.xcertplay.installer.ui.theme.DiPlayInstallerTheme
import com.shilapi.xcertplay.installer.update.UpdateManager

@Composable
fun InstallerRoot(platform: PlatformContext) {
    val settings = remember(platform) {
        SettingsStore(platform.settingsFile, platform.defaultBackupDir)
    }
    val updateManager = remember(platform, settings) {
        UpdateManager(platform, settings)
    }
    val adbController = remember(platform) {
        platform.createAdbController()
    }

    DisposableEffect(adbController) {
        adbController.register()
        onDispose { adbController.unregister() }
    }
    LaunchedEffect(adbController) {
        adbController.refresh()
    }

    CompositionLocalProvider(
        LocalPlatform provides platform,
        LocalSettings provides settings,
        LocalUpdateManager provides updateManager,
        LocalAdbController provides adbController,
    ) {
        DiPlayInstallerTheme {
            PlatformSafeArea {
                InstallerApp()
            }
        }
    }
}
