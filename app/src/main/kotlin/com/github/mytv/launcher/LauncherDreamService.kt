package com.github.mytv.launcher

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import com.github.mytv.core.ambient.AmbientClock
import com.github.mytv.core.ambient.AmbientPlaybackController
import com.github.mytv.core.ambient.AmbientScreen
import com.github.mytv.core.ambient.AmbientSky
import com.github.mytv.core.ambient.AmbientWeather
import com.github.mytv.core.ambient.FrameGrabGuard
import com.github.mytv.core.designsystem.MyLauncherTheme
import com.github.mytv.data.media.AmbientPlaylist
import com.github.mytv.data.media.AmbientResume
import com.github.mytv.data.media.SharedPreferencesAmbientResumeStore
import com.github.mytv.data.weather.LunarCalendar
import com.github.mytv.data.weather.LunarText
import com.github.mytv.data.weather.Sky
import com.github.mytv.data.weather.WeatherRepository
import com.github.mytv.data.weather.lookOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LauncherDreamService : DreamService() {
    private var controller: AmbientPlaybackController? = null
    private var resume: AmbientResume? = null
    private var weatherRepository: WeatherRepository? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        val resumeStore = AmbientResume(SharedPreferencesAmbientResumeStore(this))
        resume = resumeStore
        val playback = AmbientPlaybackController(
            this,
            FrameGrabGuard(android.os.Build.FINGERPRINT),
            resumeStore.asSink(),
            resumeStore.resumePoint(),
        )
        controller = playback
        playback.setHostActive(true)
        val clips = try {
            AmbientPlaylist.load(assets)
        } catch (_: Exception) {
            emptyList()
        }
        val rotation = resumeStore.rotationFor(clips.map { it.id })
        val selected = clips.filter { it.id in rotation }.ifEmpty { clips }
        playback.setPlaylist(selected)
        playback.start(resumeStore.resumePoint())
        val weather = WeatherRepository(this).also { repo ->
            weatherRepository = repo
            scope.launch { repo.refresh() }
        }
        setContentView(
            ComposeView(this).apply {
                setContent {
                    MyLauncherTheme {
                        val ambient by playback.state.collectAsState()
                        val weatherState by weather.state.collectAsState()
                        val look = weatherState.code?.let { lookOf(it) }
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
                        val lunar = remember { LunarCalendar.lunarFor(java.util.Date()) }
                        val state = ambient.copy(
                            clock = AmbientClock(is24h = true),
                            weather = if (weatherState.visible && weatherState.tempC != null && look != null) {
                                AmbientWeather(weatherState.tempC ?: 0, condition, sky)
                            } else {
                                null
                            },
                            lunarText = lunar?.let { LunarText.format(this@LauncherDreamService, it) },
                        )
                        AmbientScreen(
                            state = state,
                            modifier = Modifier,
                            onSurfaceView = { view ->
                                if (view == null) playback.detachSurface() else playback.attachSurface(view)
                            },
                            onExit = { finish() },
                            onPreviousClip = { playback.previousClip() },
                            onNextClip = { playback.nextClip() },
                        )
                    }
                }
            },
        )
    }

    override fun onDreamingStopped() {
        resume?.let { store ->
            controller?.state?.value?.clip?.let { clip ->
                store.saveOnLeave(clip.id, controller?.state?.value?.positionMs ?: 0L)
            }
        }
        controller?.setHostActive(false)
        controller?.release()
        controller = null
        scope.cancel()
        super.onDreamingStopped()
    }
}
