package com.github.mytv.core.dynamiccolor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.lerpColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val FIXED_SEED = -0x98af5c

class ThemeController(private val scope: CoroutineScope) {
    private var from = LauncherColors.Default
    private var target = LauncherColors.Default
    private val progress = Animatable(1f)
    private var job: Job? = null

    var seed by mutableIntStateOf(FIXED_SEED)
        private set
    var sourceKey by mutableStateOf("fixed")
        private set
    var styleName by mutableStateOf(SchemeStyle.Tonal.key)
        private set
    var contrast by mutableDoubleStateOf(0.0)
        private set
    var transitioning by mutableStateOf(false)
        private set

    val colors: State<LauncherColors> = derivedStateOf { lerpColors(from, target, progress.value) }
    val transitionState: State<Boolean> = derivedStateOf { transitioning }
    val roleColors: LauncherColors
        get() = target

    var onTransitionStart: (() -> Unit)? = null

    fun apply(
        seedArgb: Int,
        key: String,
        style: SchemeStyle,
        contrastValue: Double,
        animate: Boolean = true,
    ) {
        if (seedArgb == NO_SEED) return
        if (seedArgb == seed && key == sourceKey && style.key == styleName && contrastValue == contrast) return
        val next = SchemeFactory.dark(seedArgb, style, contrastValue)
        val close = ColorMath.deltaE(next.primary.toArgb(), target.primary.toArgb()) < 6f
        seed = seedArgb
        sourceKey = key
        styleName = style.key
        contrast = contrastValue
        job?.cancel()
        job = scope.launch {
            if (!animate || close) {
                from = next
                target = next
                progress.snapTo(1f)
                transitioning = false
            } else {
                from = colors.value
                target = next
                progress.snapTo(0f)
                transitioning = true
                onTransitionStart?.invoke()
                progress.animateTo(1f, tween(durationMillis = 600))
                transitioning = false
            }
        }
    }
}
