package com.github.mytv.core.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateContrastTest {
    private val tint = 0xFF211F26.toInt()
    private val text = 0xFFCAC4D0.toInt()

    @Test
    fun whiteBackgroundNeedsStrongestTint() {
        assertEquals(0.80f, plateAlpha(0xFFFFFFFF.toInt(), tint, text), 0.001f)
    }

    @Test
    fun lightGrayBackgroundUsesMiddleTint() {
        assertEquals(0.65f, plateAlpha(0xFFB0B0B0.toInt(), tint, text), 0.001f)
    }

    @Test
    fun darkBackgroundUsesLightestTint() {
        assertEquals(0.50f, plateAlpha(0xFF1E1E1E.toInt(), tint, text), 0.001f)
    }

    @Test
    fun chosenTintReachesFourPointFiveToOne() {
        val backgrounds = listOf(
            0xFFFFFFFF.toInt(),
            0xFFCCCCCC.toInt(),
            0xFFB0B0B0.toInt(),
            0xFF808080.toInt(),
            0xFF303030.toInt(),
            0xFF000000.toInt(),
        )
        for (background in backgrounds) {
            val alpha = plateAlpha(background, tint, text)
            val plate = overArgb(tint, alpha, background)
            assertTrue(
                "background=$background alpha=$alpha contrast=${contrastArgb(text, plate)}",
                contrastArgb(text, plate) >= 4.5,
            )
            assertTrue(PLATE_ALPHAS.any { it == alpha })
        }
    }

    @Test
    fun sRGBCompositeIsNotOklab() {
        assertEquals(0xFF808080.toInt(), overArgb(0xFFFFFFFF.toInt(), 0.5f, 0xFF010101.toInt()))
    }
}
