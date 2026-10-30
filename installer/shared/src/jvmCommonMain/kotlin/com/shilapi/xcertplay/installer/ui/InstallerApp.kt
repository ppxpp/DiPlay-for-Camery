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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.installer.ui.adb.AdbTestScreen
import com.shilapi.xcertplay.installer.ui.home.HomeScreen
import com.shilapi.xcertplay.installer.ui.install.InstallFlowScreen
import com.shilapi.xcertplay.installer.ui.restore.RestoreScreen
import com.shilapi.xcertplay.installer.ui.settings.SettingsScreen

private const val SCREEN_HOME = 0
private const val SCREEN_INSTALL = 1
private const val SCREEN_RESTORE = 2
private const val SCREEN_SETTINGS = 3
private const val SCREEN_ADB_TEST = 4

@Composable
fun InstallerApp() {
    var screen by rememberSaveable { mutableIntStateOf(SCREEN_HOME) }
    var adbTestFromSettings by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()

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
                SCREEN_INSTALL -> InstallFlowScreen(onExit = { screen = SCREEN_HOME })
                SCREEN_RESTORE -> RestoreScreen(onExit = { screen = SCREEN_HOME })
                SCREEN_SETTINGS -> SettingsScreen(
                    onExit = { screen = SCREEN_HOME },
                    onAdbTest = {
                        adbTestFromSettings = true
                        screen = SCREEN_ADB_TEST
                    },
                )
                SCREEN_ADB_TEST -> AdbTestScreen(
                    onExit = {
                        screen = if (adbTestFromSettings) SCREEN_SETTINGS else SCREEN_HOME
                        adbTestFromSettings = false
                    },
                )
                else -> HomeScreen(
                    onInstall = { screen = SCREEN_INSTALL },
                    onRestore = { screen = SCREEN_RESTORE },
                    onSettings = { screen = SCREEN_SETTINGS },
                )
            }
        }
    }
}
