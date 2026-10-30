package com.shilapi.xcertplay.installer.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.shilapi.xcertplay.installer.platform.DesktopPlatformContext
import com.shilapi.xcertplay.installer.platform.InstallerRoot

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "DiPlay 装车助手",
        state = rememberWindowState(width = 520.dp, height = 900.dp),
    ) {
        val platform = remember { DesktopPlatformContext() }
        InstallerRoot(platform)
    }
}
