package com.github.mytv.core.designsystem

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LauncherPrimary = Color(0xFFD0BCFF)
val LauncherOnPrimary = Color(0xFF381E72)
val LauncherPrimaryContainer = Color(0xFF4F378B)
val LauncherOnPrimaryContainer = Color(0xFFEADDFF)
val LauncherSecondaryContainer = Color(0xFF4A4458)
val LauncherOnSecondaryContainer = Color(0xFFE8DEF8)
val LauncherSurface = Color(0xFF141218)
val LauncherOnSurface = Color(0xFFE6E0E9)
val LauncherOnSurfaceVariant = Color(0xFFCAC4D0)
val LauncherSurfaceContainer = Color(0xFF211F26)
val LauncherSurfaceContainerHigh = Color(0xFF2B2930)
val LauncherOutline = Color(0xFF938F99)
val LauncherFocusRing = Color(0xFFFFFFFF)
val LauncherScrim = Color(0xB3000000)
val LauncherCard = Color(0xFF2A2730)
val LauncherCardFocused = Color(0xFF3A3641)

val RowTop = 236.dp
val RowLeft = 48.dp
val RowStride = 206.dp
val RowHeight = 190.dp
val ContinueCardWidth = 200.dp
val ContinueCardHeight = 112.dp
val AppIconSize = 70.dp

@Composable
fun MyLauncherTheme(
    colors: State<LauncherColors> = defaultLauncherColorsState(),
    transitioning: State<Boolean> = remember { mutableStateOf(false) },
    highContrastFocus: State<Boolean> = remember { mutableStateOf(false) },
    frozenBackdrop: State<FrozenBackdrop?> = remember { mutableStateOf(null) },
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = darkColorScheme(
            primary = LauncherPrimary,
            onPrimary = LauncherOnPrimary,
            primaryContainer = LauncherPrimaryContainer,
            onPrimaryContainer = LauncherOnPrimaryContainer,
            secondaryContainer = LauncherSecondaryContainer,
            onSecondaryContainer = LauncherOnSecondaryContainer,
            surface = LauncherSurface,
            onSurface = LauncherOnSurface,
            surfaceContainer = LauncherSurfaceContainer,
            surfaceContainerHigh = LauncherSurfaceContainerHigh,
            outline = LauncherOutline,
        ),
    ) {
        CompositionLocalProvider(
            LocalLauncherColors provides colors,
            LocalThemeTransitioning provides transitioning,
            LocalHighContrastFocus provides highContrastFocus,
            LocalFrozenBackdrop provides frozenBackdrop,
            content = content,
        )
    }
}
