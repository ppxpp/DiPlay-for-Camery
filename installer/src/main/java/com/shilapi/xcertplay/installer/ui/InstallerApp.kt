package com.shilapi.xcertplay.installer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.installer.data.InstallKitRepository
import com.shilapi.xcertplay.installer.data.model.InstallKit
import com.shilapi.xcertplay.installer.ui.adb.AdbTestScreen
import com.shilapi.xcertplay.installer.ui.home.HomeScreen
import com.shilapi.xcertplay.installer.ui.install.InstallFlowScreen
import com.shilapi.xcertplay.installer.ui.restore.RestoreScreen

private const val SCREEN_HOME = 0
private const val SCREEN_INSTALL = 1
private const val SCREEN_RESTORE = 2
private const val SCREEN_ADB_TEST = 3

@Composable
fun InstallerApp() {
    var screen by rememberSaveable { mutableIntStateOf(SCREEN_HOME) }
    val stateHolder = rememberSaveableStateHolder()

    var installKit by remember { mutableStateOf<InstallKit?>(null) }
    var kitLoading by remember { mutableStateOf(true) }
    var kitError by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        kitLoading = true
        kitError = null
        InstallKitRepository.load()
            .onSuccess { installKit = it }
            .onFailure { kitError = it.message ?: "无法获取安装包信息" }
        kitLoading = false
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState > initialState
            val enter = slideInHorizontally(
                animationSpec = tween(
                    durationMillis = 320,
                    easing = FastOutSlowInEasing,
                ),
                initialOffsetX = { width -> if (forward) width else -width / 4 },
            ) + fadeIn(animationSpec = tween(durationMillis = 220))
            val exit = slideOutHorizontally(
                animationSpec = tween(
                    durationMillis = 320,
                    easing = FastOutSlowInEasing,
                ),
                targetOffsetX = { width -> if (forward) -width / 4 else width },
            ) + fadeOut(animationSpec = tween(durationMillis = 180))
            enter togetherWith exit
        },
        label = "installer-screen",
    ) { targetScreen ->
        stateHolder.SaveableStateProvider(targetScreen) {
            when (targetScreen) {
                SCREEN_INSTALL -> InstallFlowScreen(
                    installKit = installKit,
                    onExit = { screen = SCREEN_HOME },
                )
                SCREEN_RESTORE -> RestoreScreen(onExit = { screen = SCREEN_HOME })
                SCREEN_ADB_TEST -> AdbTestScreen(onExit = { screen = SCREEN_HOME })
                else -> HomeScreen(
                    installKit = installKit,
                    kitLoading = kitLoading,
                    kitError = kitError,
                    onRetryLoad = { reloadToken++ },
                    onInstall = { screen = SCREEN_INSTALL },
                    onRestore = { screen = SCREEN_RESTORE },
                    onAdbTest = { screen = SCREEN_ADB_TEST },
                )
            }
        }
    }
}
