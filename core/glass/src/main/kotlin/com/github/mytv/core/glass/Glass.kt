package com.github.mytv.core.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.github.mytv.core.designsystem.FrozenBackdrop
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LocalFrozenBackdrop
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import kotlin.math.roundToInt

enum class GlassTier { T1, T2, T3 }

enum class DeviceTier { Low, Mid, High }

enum class GlassStyle { Chrome, Frame, Panel, Toast, Band }

fun resolveGlassTier(pref: String, deviceTier: DeviceTier, api: Int): GlassTier = when (pref) {
    "T1" -> if (api >= 31 && deviceTier != DeviceTier.Low) GlassTier.T1 else GlassTier.T2
    "T2" -> GlassTier.T2
    "T3" -> GlassTier.T3
    else -> when (deviceTier) {
        DeviceTier.Low -> GlassTier.T3
        DeviceTier.Mid -> GlassTier.T2
        DeviceTier.High -> if (api >= 31) GlassTier.T1 else GlassTier.T2
    }
}

fun blurRegionCount(tier: GlassTier, quickSettingsOpen: Boolean): Int {
    if (tier == GlassTier.T3) return 0
    return 2 + if (quickSettingsOpen) 1 else 0
}

private fun styleFor(
    style: GlassStyle,
    colors: LauncherColors,
    chromeAlpha: Float = 0.50f,
): HazeBlurStyle = when (style) {
    GlassStyle.Chrome -> HazeBlurStyle {
        blurRadius(24.dp)
        noiseFactor(0.12f)
        colorEffects(
            listOf(
                HazeColorEffect.tint(colors.surfaceContainer.copy(alpha = chromeAlpha)),
                HazeColorEffect.tint(colors.primary.copy(alpha = 0.06f)),
            ),
        )
        fallbackColorEffect(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.94f)))
    }
    GlassStyle.Frame -> HazeBlurStyle {
        blurRadius(20.dp)
        noiseFactor(0.10f)
        colorEffects(listOf(HazeColorEffect.tint(colors.surfaceContainer.copy(alpha = 0.45f))))
        fallbackColorEffect(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.94f)))
    }
    GlassStyle.Panel -> HazeBlurStyle {
        blurRadius(36.dp)
        noiseFactor(0.15f)
        colorEffects(listOf(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.62f))))
        fallbackColorEffect(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.94f)))
    }
    GlassStyle.Toast -> HazeBlurStyle {
        blurRadius(24.dp)
        noiseFactor(0.10f)
        colorEffects(listOf(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.70f))))
        fallbackColorEffect(HazeColorEffect.tint(colors.surfaceContainerHigh.copy(alpha = 0.94f)))
    }
    GlassStyle.Band -> HazeBlurStyle {
        blurRadius(16.dp)
        noiseFactor(0f)
    }
}

private fun tintFor(
    style: GlassStyle,
    colors: LauncherColors,
    chromeAlpha: Float = 0.50f,
): Pair<Color, Color> = when (style) {
    GlassStyle.Chrome -> colors.surfaceContainer.copy(alpha = chromeAlpha) to colors.primary.copy(alpha = 0.06f)
    GlassStyle.Frame -> colors.surfaceContainer.copy(alpha = 0.45f) to Color.Transparent
    GlassStyle.Panel -> colors.surfaceContainerHigh.copy(alpha = 0.62f) to Color.Transparent
    GlassStyle.Toast -> colors.surfaceContainerHigh.copy(alpha = 0.70f) to Color.Transparent
    GlassStyle.Band -> Color.Transparent to Color.Transparent
}

@Composable
fun GlassSurface(
    tier: GlassTier,
    colors: LauncherColors,
    modifier: Modifier = Modifier,
    input: HazeInput? = null,
    style: GlassStyle = GlassStyle.Chrome,
    shape: Shape = RoundedCornerShape(23.dp),
    chromeAlpha: Float = 0.50f,
    content: @Composable BoxScope.() -> Unit,
) {
    val frozen by LocalFrozenBackdrop.current
    val tint = remember(colors, style, tier, chromeAlpha) { tintFor(style, colors, chromeAlpha) }
    var position by remember { mutableStateOf(Offset.Zero) }
    // 靠屏幕边缘的大面板：模糊在屏幕边界外取样为透明，边缘一圈会透出没有参与模糊的内容层
    // （顶栏、卡片），先垫一层不透明底色
    val base = if (style == GlassStyle.Panel) Modifier.background(colors.surface) else Modifier
    val surface = when (tier) {
        GlassTier.T1 -> if (input != null) {
            base.hazeBlur(input = input, style = styleFor(style, colors, chromeAlpha))
        } else {
            base.background(tint.first)
        }
        GlassTier.T2 -> base
            .onGloballyPositioned { position = it.positionInRoot() }
            .drawBehind { drawFrozen(frozen, position, tint.first, tint.second) }
        GlassTier.T3 -> Modifier.background(colors.surfaceContainerHigh)
    }
    Box(
        modifier = modifier.clip(shape).then(surface),
        content = content,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrozen(
    frozen: FrozenBackdrop?,
    position: Offset,
    tint: Color,
    tint2: Color,
) {
    if (frozen != null) {
        val image: ImageBitmap = frozen.image
        val scaleX = image.width / frozen.screenWidth.toFloat()
        val scaleY = image.height / frozen.screenHeight.toFloat()
        val sourceX = (position.x * scaleX).roundToInt().coerceIn(0, (image.width - 1).coerceAtLeast(0))
        val sourceY = (position.y * scaleY).roundToInt().coerceIn(0, (image.height - 1).coerceAtLeast(0))
        val sourceW = (size.width * scaleX).roundToInt().coerceIn(1, image.width - sourceX)
        val sourceH = (size.height * scaleY).roundToInt().coerceIn(1, image.height - sourceY)
        drawImage(
            image = image,
            srcOffset = IntOffset(sourceX, sourceY),
            srcSize = IntSize(sourceW, sourceH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
    if (tint.alpha > 0f) drawRect(tint)
    if (tint2.alpha > 0f) drawRect(tint2)
}

@Composable
fun Modifier.hazeBackdrop(): Modifier {
    val hazeState = remember { dev.chrisbanes.haze.HazeState() }
    return this.hazeSource(hazeState)
}
