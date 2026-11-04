package com.github.mytv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mytv.core.ambient.AmbientClock
import com.github.mytv.core.ambient.AmbientScreen
import com.github.mytv.core.ambient.AmbientSky
import com.github.mytv.core.ambient.AmbientWeather
import com.github.mytv.data.weather.Sky
import com.github.mytv.data.weather.lookOf

@Composable
fun AmbientMode(model: LauncherModel) {
    val look = model.weatherCode?.let { lookOf(it) }
    val condition = look?.let { stringResource(it.label) } ?: ""
    val sky = when (look?.sky) {
        Sky.Clear -> AmbientSky.CLEAR
        Sky.Partly -> AmbientSky.PARTLY
        Sky.Overcast -> AmbientSky.OVERCAST
        Sky.Fog -> AmbientSky.FOG
        Sky.Rain -> AmbientSky.RAIN
        Sky.Snow -> AmbientSky.SNOW
        Sky.Thunder -> AmbientSky.THUNDER
        null -> AmbientSky.CLEAR
    }
    val weather = if (model.weatherVisible && model.weatherTempC != null && look != null) {
        AmbientWeather(tempC = model.weatherTempC ?: 0, condition = condition, sky = sky)
    } else {
        null
    }
    val state = model.ambientState.copy(
        clock = AmbientClock(model.clockIs24h),
        weather = weather,
        lunarText = model.lunarText.ifBlank { null },
    )
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Static fallback layer; the controller replaces it with the video surface.
        AndroidView(
            factory = { context -> android.widget.ImageView(context) },
            modifier = Modifier.fillMaxSize(),
        )
        AmbientScreen(
            state = state,
            modifier = Modifier.fillMaxSize(),
            onSurfaceView = model::attachAmbientSurface,
            onExit = { model.exitIdle() },
            onPreviousClip = {},
            onNextClip = {},
        )
    }
}
