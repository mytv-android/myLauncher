package com.github.mytv.core.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Opaque tints tried by the contrast guard, from supplement A2. */
val PLATE_ALPHAS = floatArrayOf(0.50f, 0.65f, 0.80f)

/** sRGB over-compositing, matching the compositor (not an Oklab lerp). */
fun overArgb(top: Int, alpha: Float, bottom: Int): Int {
    fun channel(shift: Int): Int {
        val t = (top ushr shift) and 0xFF
        val b = (bottom ushr shift) and 0xFF
        return (t * alpha + b * (1f - alpha)).toInt().coerceIn(0, 255)
    }
    val a = 0xFF
    return (a shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** WCAG relative luminance. */
fun relativeLuminance(argb: Int): Double {
    fun linear(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val r = linear((argb ushr 16) and 0xFF)
    val g = linear((argb ushr 8) and 0xFF)
    val b = linear(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

fun contrastArgb(first: Int, second: Int): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Picks the first tint opacity whose composite keeps the plate's weakest text
 * (onSurfaceVariant) at 4.5:1 over the brightest cell underneath.
 */
fun plateAlpha(brightestArgb: Int, tintArgb: Int, textArgb: Int): Float =
    PLATE_ALPHAS.firstOrNull { alpha ->
        contrastArgb(textArgb, overArgb(tintArgb, alpha, brightestArgb)) >= 4.5
    } ?: PLATE_ALPHAS.last()

fun plateAlpha(brightest: Color, tint: Color, text: Color): Float =
    plateAlpha(brightest.toArgb(), tint.toArgb(), text.toArgb())
