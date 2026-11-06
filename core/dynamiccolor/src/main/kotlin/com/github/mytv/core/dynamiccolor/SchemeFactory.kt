package com.github.mytv.core.dynamiccolor

import androidx.compose.ui.graphics.Color
import com.github.mytv.core.designsystem.LauncherColors
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

enum class SchemeStyle(val key: String) {
    Tonal("tonal"),
    Content("content"),
    Vibrant("vibrant"),
    Expressive("expressive"),
    ;

    companion object {
        fun fromKey(key: String): SchemeStyle = entries.firstOrNull { it.key == key } ?: Tonal
    }
}

object SchemeFactory {
    fun dark(seedArgb: Int, style: SchemeStyle, contrast: Double): LauncherColors {
        val hct = Hct.fromInt(seedArgb)
        val scheme = schemeFor(hct, style, contrast)
        return LauncherColors(
            primary = Color(scheme.primary),
            onPrimary = Color(scheme.onPrimary),
            primaryContainer = Color(scheme.primaryContainer),
            onPrimaryContainer = Color(scheme.onPrimaryContainer),
            secondaryContainer = Color(scheme.secondaryContainer),
            onSecondaryContainer = Color(scheme.onSecondaryContainer),
            surface = Color(scheme.surface),
            onSurface = Color(scheme.onSurface),
            onSurfaceVariant = Color(scheme.onSurfaceVariant),
            surfaceContainer = Color(scheme.surfaceContainer),
            surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
            outline = Color(scheme.outline),
            focusRing = Color(scheme.primaryPalette.tone(90)),
            ambient = Color(scheme.primaryPalette.tone(12)),
            tertiary = Color(scheme.tertiary),
        )
    }

    private fun schemeFor(hct: Hct, style: SchemeStyle, contrast: Double): DynamicScheme = when (style) {
        SchemeStyle.Tonal -> SchemeTonalSpot(hct, true, contrast)
        SchemeStyle.Content -> SchemeContent(hct, true, contrast)
        SchemeStyle.Vibrant -> SchemeVibrant(hct, true, contrast)
        SchemeStyle.Expressive -> SchemeExpressive(hct, true, contrast)
    }
}
