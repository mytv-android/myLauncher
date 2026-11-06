package com.github.mytv.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 原型里用到的 24 单位 SVG 路径。填充型用于按钮，描边型用于顶栏与快速设置。 */
object Glyphs {
    // 填充
    const val PLAY = "M8 5.5v13l10.5-6.5z"
    const val PLUS = "M11 5h2v14h-2zM5 11h14v2H5z"
    const val CHECK = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"

    // 描边（stroke-width 2，圆头圆角）
    const val SEARCH = "M11 4.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13zM20 20l-4.2-4.2"
    const val AMBIENT = "M6 5h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3zM6.5 15l3.5-4 3 3 2-2 2.5 3"
    const val GEAR = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM12 2.8v2.4M12 18.8v2.4M4.2 7.5l2.1 1.2M17.7 15.3l2.1 1.2M4.2 16.5l2.1-1.2M17.7 8.7l2.1-1.2"

    // 快速设置磁贴（描边）
    const val WIFI = "M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M9 16a4.5 4.5 0 0 1 6 0M12 19h.01"
    const val BLUETOOTH = "m7 7 10 10-5 4V3l5 4L7 17"
    const val DND = "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM8 12h8"
    const val SCENERY = "M3 18l5-7 4 5 3-3 6 5M16 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"
    const val TRANSFER = "M7 4v13M3 13l4 4 4-4M17 20V7M13 11l4-4 4 4"
    const val INPUT = "M4 5.5h16a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1zM8 20h8M10.5 8.5l3 2.5-3 2.5"
    const val MOON = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
    const val LOCK = "M7.5 11V8a4.5 4.5 0 0 1 9 0v3M6 11h12v9H6zM12 14.5v2"
    const val VOLUME = "M4 9.5h3.5L12 6v12l-4.5-3.5H4zM15.5 9a4 4 0 0 1 0 6"
    const val VOLUME_OFF = "M4 9.5h3.5L12 6v12l-4.5-3.5H4zM16 9.5l5 5M21 9.5l-5 5"
}

/**
 * 画一个 24 单位视框的路径。[stroke] 为空时填充，否则按该线宽（视框单位）描边。
 */
@Composable
fun LauncherGlyph(
    path: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    stroke: Float? = null,
) {
    val parsed: Path = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (stroke == null) {
                drawPath(parsed, color)
            } else {
                drawPath(
                    parsed,
                    color,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
