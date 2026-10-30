package com.shilapi.xcertplay.installer.platform

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)

@Composable
expect fun PlatformSafeArea(content: @Composable () -> Unit)
