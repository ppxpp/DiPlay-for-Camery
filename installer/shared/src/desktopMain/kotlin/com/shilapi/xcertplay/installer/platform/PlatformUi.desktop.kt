package com.shilapi.xcertplay.installer.platform

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
    // Desktop screens keep an explicit back button.
}

@Composable
actual fun PlatformSafeArea(content: @Composable () -> Unit) {
    content()
}
