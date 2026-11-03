package com.github.mytv.core.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

data class GlassAppearance(
    val tier: String = "T3",
    val color: Color = Color(0xE61C1B21),
)

@Composable
fun GlassPanel(
    appearance: GlassAppearance,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(appearance.color, shape),
        content = content,
    )
}

@Composable
fun Modifier.hazeBackdrop(): Modifier {
    val hazeState = remember { HazeState() }
    return this.hazeSource(hazeState)
}
