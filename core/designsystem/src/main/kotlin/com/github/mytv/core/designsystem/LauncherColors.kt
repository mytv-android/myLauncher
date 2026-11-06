package com.github.mytv.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

data class LauncherColors(
    val primary: Color = LauncherPrimary,
    val onPrimary: Color = LauncherOnPrimary,
    val primaryContainer: Color = LauncherPrimaryContainer,
    val onPrimaryContainer: Color = LauncherOnPrimaryContainer,
    val secondaryContainer: Color = LauncherSecondaryContainer,
    val onSecondaryContainer: Color = LauncherOnSecondaryContainer,
    val surface: Color = LauncherSurface,
    val onSurface: Color = LauncherOnSurface,
    val onSurfaceVariant: Color = LauncherOnSurfaceVariant,
    val surfaceContainer: Color = LauncherSurfaceContainer,
    val surfaceContainerHigh: Color = LauncherSurfaceContainerHigh,
    val outline: Color = LauncherOutline,
    val focusRing: Color = LauncherFocusRing,
    /** 主色调色板 tone 12：Hero 与浏览态背景左侧的压暗色（原型的 --amb）。 */
    val ambient: Color = LauncherAmbient,
    val tertiary: Color = LauncherTertiary,
) {
    companion object {
        val Default = LauncherColors()
    }
}

fun lerpColors(start: LauncherColors, stop: LauncherColors, fraction: Float): LauncherColors =
    LauncherColors(
        primary = lerp(start.primary, stop.primary, fraction),
        onPrimary = lerp(start.onPrimary, stop.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, stop.primaryContainer, fraction),
        onPrimaryContainer = lerp(start.onPrimaryContainer, stop.onPrimaryContainer, fraction),
        secondaryContainer = lerp(start.secondaryContainer, stop.secondaryContainer, fraction),
        onSecondaryContainer = lerp(start.onSecondaryContainer, stop.onSecondaryContainer, fraction),
        surface = lerp(start.surface, stop.surface, fraction),
        onSurface = lerp(start.onSurface, stop.onSurface, fraction),
        onSurfaceVariant = lerp(start.onSurfaceVariant, stop.onSurfaceVariant, fraction),
        surfaceContainer = lerp(start.surfaceContainer, stop.surfaceContainer, fraction),
        surfaceContainerHigh = lerp(start.surfaceContainerHigh, stop.surfaceContainerHigh, fraction),
        outline = lerp(start.outline, stop.outline, fraction),
        focusRing = lerp(start.focusRing, stop.focusRing, fraction),
        ambient = lerp(start.ambient, stop.ambient, fraction),
        tertiary = lerp(start.tertiary, stop.tertiary, fraction),
    )
