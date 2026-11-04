package com.github.mytv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

val LocalLauncherColors = staticCompositionLocalOf<State<LauncherColors>> {
    mutableStateOf(LauncherColors.Default)
}

val LocalThemeTransitioning = staticCompositionLocalOf<State<Boolean>> {
    mutableStateOf(false)
}

val LocalHighContrastFocus = staticCompositionLocalOf<State<Boolean>> {
    mutableStateOf(false)
}

val LocalFrozenBackdrop = staticCompositionLocalOf<State<FrozenBackdrop?>> {
    mutableStateOf(null)
}

val LocalRowRecompositionObserver = staticCompositionLocalOf<(() -> Unit)?> { null }

data class FrozenBackdrop(
    val image: androidx.compose.ui.graphics.ImageBitmap,
    val screenWidth: Int,
    val screenHeight: Int,
    val bytes: Long,
)

@Composable
fun defaultLauncherColorsState(): State<LauncherColors> = remember { mutableStateOf(LauncherColors.Default) }
