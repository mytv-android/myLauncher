package com.github.mytv.core.dynamiccolor

import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

object ColorMath {
    fun hex(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    fun deltaE(first: Int, second: Int): Float {
        val a = lab(first)
        val b = lab(second)
        return sqrt(
            (a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2),
        ).toFloat()
    }

    fun contrast(first: Int, second: Int): Double {
        val x = luminance(first)
        val y = luminance(second)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linear(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun lab(argb: Int): DoubleArray {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) t.pow(1.0 / 3.0) else (24389.0 / 27.0 * t + 16.0) / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
