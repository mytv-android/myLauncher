package com.github.mytv.core.dynamiccolor

import androidx.compose.ui.graphics.toArgb
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastScanTest {
    private val seeds = List(1000) {
        val random = Random(42L + it)
        0xFF000000.toInt() or (random.nextInt() and 0xFFFFFF)
    }

    private val styles = SchemeStyle.entries

    @Test
    fun standardContrast() = scan(0.0)

    @Test
    fun mediumContrast() = scan(0.5)

    @Test
    fun highContrast() = scan(1.0)

    private fun scan(level: Double) {
        for (seed in seeds) {
            for (style in styles) {
                val colors = SchemeFactory.dark(seed, style, level)
                val primary = colors.primary.toArgb()
                val onPrimary = colors.onPrimary.toArgb()
                val surface = colors.surface.toArgb()
                val onSurface = colors.onSurface.toArgb()
                val ring = colors.focusRing.toArgb()
                assertTrue(
                    "onPrimary/primary ${ColorMath.hex(onPrimary)}/${ColorMath.hex(primary)} " +
                        "style=${style.key} contrast=$level",
                    ColorMath.contrast(onPrimary, primary) >= 4.5,
                )
                assertTrue(
                    "onSurface/surface ${ColorMath.hex(onSurface)}/${ColorMath.hex(surface)} " +
                        "style=${style.key} contrast=$level",
                    ColorMath.contrast(onSurface, surface) >= 7.0,
                )
                assertTrue(
                    "focusRing/surface ${ColorMath.hex(ring)}/${ColorMath.hex(surface)} " +
                        "style=${style.key} contrast=$level",
                    ColorMath.contrast(ring, surface) >= 3.0,
                )
            }
        }
    }
}
