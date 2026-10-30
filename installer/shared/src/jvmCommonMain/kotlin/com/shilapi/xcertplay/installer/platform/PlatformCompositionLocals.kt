package com.shilapi.xcertplay.installer.platform

import androidx.compose.runtime.staticCompositionLocalOf
import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.settings.SettingsStore
import com.shilapi.xcertplay.installer.update.UpdateManager

val LocalPlatform = staticCompositionLocalOf<PlatformContext> {
    error("PlatformContext is not provided")
}

val LocalAdbController = staticCompositionLocalOf<AdbController> {
    error("AdbController is not provided")
}

val LocalSettings = staticCompositionLocalOf<SettingsStore> {
    error("SettingsStore is not provided")
}

val LocalUpdateManager = staticCompositionLocalOf<UpdateManager> {
    error("UpdateManager is not provided")
}
