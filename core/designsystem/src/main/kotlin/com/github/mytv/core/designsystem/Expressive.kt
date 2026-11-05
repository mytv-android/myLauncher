package com.github.mytv.core.designsystem

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> launcherFastSpatialSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.fastSpatialSpec()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> launcherDefaultSpatialSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.defaultSpatialSpec()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> launcherFastEffectsSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.fastEffectsSpec()

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberCookieMorphShape(progress: State<Float>): Shape {
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    return remember(morph, progress) { CookieMorphShape(morph, progress) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class CookieMorphShape(
    private val morph: Morph,
    private val progress: State<Float>,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.value, Path())
        val matrix = Matrix().apply { scale(size.width, size.height) }
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LauncherWavyProgress(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    LinearWavyProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        // 静止波形：持续位移动画会让桌面永不空闲（无障碍树无法稳定快照，也浪费帧预算）
        waveSpeed = 0.dp,
    )
}
