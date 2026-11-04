package com.github.mytv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.round

@Composable
fun Modifier.launcherFocusRing(focused: Boolean, shape: Shape): Modifier {
    if (!focused) return this
    val colors = LocalLauncherColors.current
    val highContrast = LocalHighContrastFocus.current
    return drawWithContent {
        drawContent()
        if (highContrast.value) {
            // 高对比焦点框用像素对齐的圆角矩形，保证 4dp 白框与 2dp 黑描边边界清晰
            drawSnappedRing(Color.Black, 2.dp, (-2).dp)
            drawSnappedRing(Color.White, 4.dp, 0.dp)
        } else {
            drawShapeRing(shape, colors.value.focusRing, 3.dp, 1.5.dp)
        }
    }
}

private fun DrawScope.drawSnappedRing(color: Color, width: Dp, inset: Dp) {
    val w = round(width.toPx())
    val insetPx = round(inset.toPx())
    val radius = round(18.dp.toPx()).coerceAtMost((size.width - w) / 2)
    val left = insetPx + w / 2
    val top = insetPx + w / 2
    val right = size.width - insetPx - w / 2
    val bottom = size.height - insetPx - w / 2
    if (right <= left || bottom <= top) return
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = w),
    )
}

private fun DrawScope.drawShapeRing(shape: Shape, color: Color, width: Dp, inset: Dp) {
    val insetPx = inset.toPx()
    val widthPx = (size.width - insetPx * 2).coerceAtLeast(1f)
    val heightPx = (size.height - insetPx * 2).coerceAtLeast(1f)
    val outline = shape.createOutline(Size(widthPx, heightPx), layoutDirection, this)
    translate(insetPx, insetPx) {
        drawOutline(outline, color, style = Stroke(width = width.toPx()))
    }
}
