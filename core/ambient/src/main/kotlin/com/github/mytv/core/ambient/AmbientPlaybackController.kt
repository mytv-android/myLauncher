package com.github.mytv.core.ambient

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.SurfaceView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.random.Random

/**
 * Media3-based ambient playback used by both the in-launcher overlay and the
 * DreamService.
 *
 * Host wiring:
 *  - call [setPlaylist] with clips from :data:media;
 *  - call [attachSurface] with the SurfaceView rendered by [AmbientScreen];
 *  - call [setHostActive] from the DreamService, or rely on the registered
 *    activity callbacks for the in-launcher overlay;
 *  - pass an [AmbientResumeSink] backed by :data:media's AmbientResume.
 *
 * The controller releases the ExoPlayer two and a half seconds after the last
 * host surface leaves the foreground, and restarts from the last resume point.
 */
@UnstableApi
class AmbientPlaybackController(
    context: Context,
    private val guard: FrameGrabGuard,
    private val resumeSink: AmbientResumeSink? = null,
    initialResume: AmbientResumePoint? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val appContext = context.applicationContext
    private val application = appContext as? Application
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(appContext.mainLooper)
    private val pixelCopyThread = HandlerThread("ambient-pixelcopy").apply { start() }
    private val pixelCopyHandler = Handler(pixelCopyThread.looper)
    private val frameExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ambient-frame").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(AmbientState())
    val state: StateFlow<AmbientState> = _state.asStateFlow()

    private var clips: List<AmbientClip> = emptyList()
    private var index: Int = 0
    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var source: AmbientSource = AmbientSource.AERIAL
    private var quality: AmbientQuality = AmbientQuality.H264_1080P
    private var initialResumePoint: AmbientResumePoint? = initialResume
    private var metered: Boolean = false
    private var networkAvailable: Boolean = true
    private var activityCount: Int = 0
    private var explicitHostActive: Boolean = false
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSavedWholeSeconds: Long = -1L
    private var finalRelease: Boolean = false
    private var consecutiveFailures: Int = 0
    private var firstFrameRendered: Boolean = false
    private var releaseJob: Job? = null
    private var tickerJob: Job? = null
    private var firstFrameJob: Job? = null
    private var prefetchJob: Job? = null
    private var ambientEnteredAt: Long = 0L
    private val clockDrift = ClockDrift()
    private var lastDriftMinute: Long = Long.MIN_VALUE

    init {
        registerNetworkCallback()
        registerActivityCallbacks()
    }

    /** Installs the playlist and selects the first clip. */
    fun setPlaylist(clips: List<AmbientClip>, source: AmbientSource = AmbientSource.AERIAL) {
        this.clips = clips
        this.source = source
        val resumeIndex = initialResumePoint?.let { point ->
            clips.indexOfFirst { it.id == point.clipId }.takeIf { it >= 0 }
        }
        index = resumeIndex ?: index.coerceIn(0, (clips.size - 1).coerceAtLeast(0))
        updateState { it.copy(source = source) }
    }

    /** Sets the in-process quality tier; the user setting is not touched. */
    fun setQuality(quality: AmbientQuality) {
        this.quality = quality
    }

    /** Attach the SurfaceView created by [AmbientScreen]. */
    fun attachSurface(view: SurfaceView) {
        surfaceView = view
        player?.setVideoSurfaceView(view)
    }

    fun detachSurface() {
        surfaceView?.let { player?.clearVideoSurfaceView(it) }
        surfaceView = null
    }

    /**
     * DreamService has no Activity lifecycle; it calls this around
     * onDreamingStarted/onDreamingStopped.
     */
    fun setHostActive(active: Boolean) {
        explicitHostActive = active
        if (active) {
            cancelBackgroundRelease()
            maybeResumeOnForeground()
        } else {
            scheduleBackgroundRelease()
        }
    }

    /** Starts or resumes the current clip. */
    fun start(resume: AmbientResumePoint? = initialResumePoint) {
        finalRelease = false
        if (resume != null) {
            val resumeIndex = clips.indexOfFirst { it.id == resume.clipId }
            if (resumeIndex >= 0) index = resumeIndex
        }
        ambientEnteredAt = if (ambientEnteredAt == 0L) SystemClock.elapsedRealtime() else ambientEnteredAt
        startTicker()
        val clip = clips.getOrNull(index)
        if (clip == null) {
            showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
            return
        }
        if (clip.source == AmbientSource.AERIAL && !networkAvailable) {
            showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
            return
        }
        val exoPlayer = ensurePlayer() ?: run {
            showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
            return
        }
        val position = if (resume?.clipId == clip.id) resume.positionMs else 0L
        firstFrameRendered = false
        val uri = clip.urlFor(quality).takeIf { it.isNotBlank() } ?: clip.localFile
        if (uri.isNullOrBlank()) {
            showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
            return
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.volume = 0f
        exoPlayer.prepare()
        if (position > 0) exoPlayer.seekTo(position)
        exoPlayer.playWhenReady = true
        updateState {
            it.copy(
                source = source,
                clip = clip,
                positionMs = position,
                player = AmbientPlayerStatus.PLAYING,
                stillFrame = null,
            )
        }
        startTicker()
        startFirstFrameWatchdog()
    }

    fun pause() {
        player?.pause()
        updateState { it.copy(player = if (player == null) AmbientPlayerStatus.RELEASED else AmbientPlayerStatus.PAUSED) }
    }

    /** Releases the decoder when the host goes to the background; reusable afterwards. */
    fun releaseDecoderForBackground() {
        cancelTimers()
        releasePlayer()
        stopTicker()
    }

    /** Releases the decoder now; used when another app takes the surface. */
    fun release() {
        finalRelease = true
        cancelTimers()
        releasePlayer()
        unregister()
        ambientEnteredAt = 0L
        clockDrift.reset()
        frameExecutor.shutdownNow()
        pixelCopyThread.quitSafely()
        _state.update { it.copy(drift = DriftOffset.Zero, dimmed = false) }
    }

    fun nextClip() {
        if (clips.isEmpty()) return
        index = (index + 1) % clips.size
        restartCurrent()
    }

    fun previousClip() {
        if (clips.isEmpty()) return
        index = (index - 1 + clips.size) % clips.size
        restartCurrent()
    }

    fun currentResumePoint(): AmbientResumePoint? {
        val clip = clips.getOrNull(index) ?: return initialResumePoint
        return AmbientResumePoint(clip.id, _state.value.positionMs, System.currentTimeMillis())
    }

    /**
     * Hands the surface to an in-process DreamService. Reusing the same player
     * keeps clip, position and quality; otherwise the caller restarts after
     * changing quality.
     */
    fun handoffToScreensaver(qualityChanged: Boolean = false): ScreensaverHandoff {
        val playing = _state.value.player == AmbientPlayerStatus.PLAYING
        return if (playing && !qualityChanged) {
            ScreensaverHandoff.REUSE_SAME_PLAYER
        } else {
            ScreensaverHandoff.RESTART
        }
    }

    /** Schedule a prefetch so the next clip's first 6 MB is cached. */
    fun scheduleScreensaverIn(millis: Long) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val wait = millis - PREFETCH_LEAD_MS
            if (wait > 0) delay(wait)
            prefetchNext()
        }
    }

    /**
     * Captures the current frame with PixelCopy on API 24+, falling back to a
     * preset keyframe or a local file frame. The callback runs on the main
     * thread.
     */
    fun captureFrame(callback: (FrameGrabResult?) -> Unit) {
        val view = surfaceView
        val clip = clips.getOrNull(index)
        if (view == null || clip == null || Build.VERSION.SDK_INT < 24 || !guard.canUsePixelCopy) {
            deliverFallbackFrame(clip, callback)
            return
        }
        val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            android.view.PixelCopy.request(
                view,
                bitmap,
                { result ->
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    if (result == android.view.PixelCopy.SUCCESS) {
                        guard.onSuccessfulGrab(elapsed)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val allBlack = FrameLuminance.isAllBlack(pixels, bitmap.width, bitmap.height)
                        guard.onFrameAnalyzed(clip.keyColor, allBlack)
                        val keyColor = if (allBlack) clip.keyColor else averageColor(pixels)
                        val grabbed = FrameGrabResult(bitmap, keyColor, allBlack, elapsed)
                        updateState { it.copy(stillFrame = bitmap.asImageBitmap()) }
                        mainHandler.post { callback(grabbed) }
                    } else {
                        deliverFallbackFrame(clip, callback)
                    }
                },
                pixelCopyHandler,
            )
        }.onFailure {
            deliverFallbackFrame(clip, callback)
        }
    }

    private fun restartCurrent() {
        val clip = clips.getOrNull(index) ?: return
        val position = if (source == AmbientSource.AERIAL) 0L else _state.value.positionMs
        player?.stop()
        initialResumePoint = AmbientResumePoint(clip.id, position, System.currentTimeMillis())
        start(initialResumePoint)
    }

    private fun ensurePlayer(): ExoPlayer? {
        player?.let { return it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = runCatching {
            DefaultMediaSourceFactory(
                CacheDataSource.Factory()
                    .setCache(sharedCache())
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            )
        }.getOrElse {
            DefaultMediaSourceFactory(httpFactory)
        }
        val created = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        created.volume = 0f
        created.addListener(playerListener)
        player = created
        surfaceView?.let { created.setVideoSurfaceView(it) }
        return created
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            consecutiveFailures = 0
            firstFrameJob?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> updateState {
                    if (it.player == AmbientPlayerStatus.NONE) it else {
                        it.copy(player = if (player?.playWhenReady == true) AmbientPlayerStatus.PLAYING else AmbientPlayerStatus.PAUSED)
                    }
                }
                Player.STATE_ENDED -> nextClip()
                Player.STATE_BUFFERING -> Unit
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (MediaPlaybackRules.isDecoderError(error.errorCode)) {
                val lower = quality.lower()
                if (lower != null) {
                    quality = lower
                    restartCurrent()
                    return
                }
            }
            consecutiveFailures++
            if (consecutiveFailures >= MediaPlaybackRules.MAX_CONSECUTIVE_FAILURES) {
                showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
            } else {
                index = (index + 1) % clips.size.coerceAtLeast(1)
                restartCurrent()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val exoPlayer = player
                val position = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
                val minute = System.currentTimeMillis() / 60_000
                val drift = if (minute != lastDriftMinute) {
                    lastDriftMinute = minute
                    clockDrift.next()
                } else {
                    _state.value.drift
                }
                val dimmed = ambientEnteredAt != 0L &&
                    SystemClock.elapsedRealtime() - ambientEnteredAt >= DIM_AFTER_MS
                updateState {
                    it.copy(
                        positionMs = position,
                        drift = drift,
                        dimmed = dimmed,
                        player = exoPlayer?.let {
                            if (it.playWhenReady) AmbientPlayerStatus.PLAYING else AmbientPlayerStatus.PAUSED
                        } ?: it.player,
                    )
                }
                val wholeSeconds = position / 1_000
                if (exoPlayer != null && wholeSeconds > 0 &&
                    wholeSeconds / 10 != lastSavedWholeSeconds / 10
                ) {
                    lastSavedWholeSeconds = wholeSeconds
                    clips.getOrNull(index)?.let { clip -> resumeSink?.save(clip.id, position) }
                }
            }
        }
    }

    private fun startFirstFrameWatchdog() {
        firstFrameJob?.cancel()
        firstFrameJob = scope.launch {
            delay(MediaPlaybackRules.NO_FRAME_TIMEOUT_MS)
            if (!firstFrameRendered) {
                consecutiveFailures++
                if (consecutiveFailures >= MediaPlaybackRules.MAX_CONSECUTIVE_FAILURES) {
                    showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
                } else {
                    index = (index + 1) % clips.size.coerceAtLeast(1)
                    restartCurrent()
                }
            }
        }
    }

    private fun showFallback(action: AmbientFailureAction) {
        firstFrameJob?.cancel()
        releasePlayer()
        startTicker()
        val clip = clips.getOrNull(index)
        val still = clip?.let { loadKeyframeBitmap(it, _state.value.positionMs, allowFileGrab = false) }
        val fallbackSource = when (action) {
            AmbientFailureAction.PHOTOS_OR_WALLPAPER ->
                if (_state.value.source == AmbientSource.PHOTO) AmbientSource.PHOTO else AmbientSource.WALLPAPER
            AmbientFailureAction.NEXT_CLIP -> AmbientSource.WALLPAPER
        }
        updateState {
            it.copy(
                source = fallbackSource,
                player = AmbientPlayerStatus.RELEASED,
                stillFrame = still?.asImageBitmap(),
            )
        }
    }

    private fun deliverFallbackFrame(clip: AmbientClip?, callback: (FrameGrabResult?) -> Unit) {
        if (clip == null) {
            mainHandler.post { callback(null) }
            return
        }
        val immediate = loadKeyframeBitmap(clip, _state.value.positionMs, allowFileGrab = false)
        if (immediate != null) {
            mainHandler.post { callback(toGrabResult(clip, immediate)) }
            return
        }
        if (clip.localFile == null || !guard.canGrabFromFile) {
            mainHandler.post { callback(null) }
            return
        }
        scope.launch(Dispatchers.IO) {
            val bitmap = loadKeyframeBitmap(clip, _state.value.positionMs, allowFileGrab = true)
            val result = bitmap?.let { toGrabResult(clip, it) }
            mainHandler.post { callback(result) }
        }
    }

    private fun toGrabResult(clip: AmbientClip, bitmap: Bitmap): FrameGrabResult {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return FrameGrabResult(
            bitmap = bitmap,
            keyColor = if (clip.keyColor != 0) clip.keyColor else averageColor(pixels),
            allBlack = FrameLuminance.isAllBlack(pixels, bitmap.width, bitmap.height),
            elapsedMs = 0L,
        )
    }

    /** Nearest preset keyframe, then a local-file frame with a 10 s timeout. */
    private fun loadKeyframeBitmap(
        clip: AmbientClip,
        positionMs: Long,
        allowFileGrab: Boolean,
    ): Bitmap? {
        val keyframe = clip.keyframes.minByOrNull { abs(it.positionMs - positionMs) }
        val fromKeyframe = keyframe?.let { decodeImage(it.imageUri) }
        if (fromKeyframe != null) return fromKeyframe
        if (!allowFileGrab) return null
        val localFile = clip.localFile ?: return null
        if (!guard.canGrabFromFile) return null
        val future = frameExecutor.submit<Bitmap?> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(localFile)
                retriever.getFrameAtTime(
                    positionMs * 1_000,
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC,
                )
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        return try {
            future.get(FrameGrabGuard.FILE_GRAB_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            guard.onFileGrabTimeout()
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun decodeImage(uri: String): Bitmap? = runCatching {
        when {
            uri.startsWith("content://") -> appContext.contentResolver.openInputStream(Uri.parse(uri))
                ?.use { BitmapFactory.decodeStream(it) }
            uri.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(uri).path)
            uri.startsWith("http://") || uri.startsWith("https://") -> null
            else -> BitmapFactory.decodeFile(uri)
        }
    }.getOrNull()

    private fun averageColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        val step = (pixels.size / 4_096).coerceAtLeast(1)
        var position = 0
        while (position < pixels.size) {
            val color = pixels[position]
            r += (color ushr 16) and 0xFF
            g += (color ushr 8) and 0xFF
            b += color and 0xFF
            count++
            position += step
        }
        if (count == 0) return 0
        return (0xFF shl 24) or ((r / count).toInt() shl 16) or
            ((g / count).toInt() shl 8) or (b / count).toInt()
    }

    private fun prefetchNext() {
        if (!MediaPlaybackRules.shouldPrefetch(metered, 0L)) return
        if (clips.size < 2) return
        val next = clips.getOrNull((index + 1) % clips.size) ?: return
        val uri = next.urlFor(quality)
        if (uri.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(sharedCache())
                    .setUpstreamDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                            .setReadTimeoutMs(READ_TIMEOUT_MS),
                    )
                    .createDataSource()
                val spec = DataSpec.Builder()
                    .setUri(uri)
                    .setLength(MediaPlaybackRules.PREFETCH_BYTES)
                    .build()
                CacheWriter(cacheDataSource, spec, null, null).cache()
            }
        }
    }

    private fun sharedCache(): SimpleCache = synchronized(cacheLock) {
        cache ?: run {
            val cap = MediaPlaybackRules.cacheCapBytes(appContext.cacheDir.usableSpace)
            val created = SimpleCache(
                File(appContext.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(cap),
            )
            cache = created
            created
        }
    }

    private fun releasePlayer() {
        clips.getOrNull(index)?.let { clip ->
            runCatching { resumeSink?.save(clip.id, _state.value.positionMs) }
        }
        player?.let {
            runCatching { it.clearVideoSurfaceView(surfaceView) }
            runCatching { it.release() }
        }
        player = null
        updateState { it.copy(player = AmbientPlayerStatus.RELEASED) }
    }

    private fun cancelTimers() {
        stopTicker()
        firstFrameJob?.cancel()
        firstFrameJob = null
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun updateState(block: (AmbientState) -> AmbientState) {
        _state.update(block)
    }

    private fun isHostForeground(): Boolean = explicitHostActive || activityCount > 0

    private fun maybeResumeOnForeground() {
        if (finalRelease || clips.isEmpty()) return
        if (!isHostForeground()) return
        if (_state.value.player == AmbientPlayerStatus.RELEASED) {
            start(currentResumePoint() ?: initialResumePoint)
        }
    }

    private fun scheduleBackgroundRelease() {
        if (isHostForeground()) return
        cancelBackgroundRelease()
        releaseJob = scope.launch {
            captureFrame { result ->
                if (result != null) {
                    _state.update { it.copy(stillFrame = result.bitmap.asImageBitmap()) }
                }
            }
            delay(BACKGROUND_RELEASE_MS)
            if (!explicitHostActive && activityCount == 0) {
                releasePlayer()
                stopTicker()
            }
        }
    }

    private fun cancelBackgroundRelease() {
        releaseJob?.cancel()
        releaseJob = null
    }

    private fun registerActivityCallbacks() {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity.packageName != appContext.packageName) return
                activityCount++
                cancelBackgroundRelease()
                maybeResumeOnForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity.packageName != appContext.packageName) return
                activityCount = (activityCount - 1).coerceAtLeast(0)
                if (activityCount == 0) scheduleBackgroundRelease()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application?.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        metered = manager.isActiveNetworkMetered
        networkAvailable = manager.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkAvailable = true
                metered = manager.isActiveNetworkMetered
                if (_state.value.player == AmbientPlayerStatus.RELEASED &&
                    clips.getOrNull(index) != null &&
                    isHostForeground()
                ) {
                    mainHandler.post { start() }
                }
            }

            override fun onLost(network: Network) {
                networkAvailable = manager.activeNetwork != null
                metered = manager.isActiveNetworkMetered
                if (!networkAvailable) {
                    mainHandler.post {
                        showFallback(AmbientFailureAction.PHOTOS_OR_WALLPAPER)
                    }
                }
            }
        }
        networkCallback = callback
        runCatching {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    private fun unregister() {
        lifecycleCallbacks?.let { callbacks ->
            runCatching { application?.unregisterActivityLifecycleCallbacks(callbacks) }
        }
        lifecycleCallbacks = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    /** Random offset of at most 24 dp in both axes, changed once per minute. */
    class ClockDrift(
        private val maxDp: Float = 24f,
        private val random: Random = Random.Default,
    ) {
        fun next(): DriftOffset = DriftOffset(
            xDp = (random.nextFloat() - 0.5f) * 2f * maxDp,
            yDp = (random.nextFloat() - 0.5f) * 2f * maxDp,
        )

        fun reset() = Unit
    }

    private object NetworkCallbackHolder

    companion object {
        const val CAPTURE_WIDTH = 960
        const val CAPTURE_HEIGHT = 540
        const val CACHE_DIR = "ambient_media"
        const val PREFETCH_LEAD_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val BACKGROUND_RELEASE_MS = 2_500L
        const val DIM_AFTER_MS = 30L * 60 * 1_000

        private val cacheLock = Any()

        @Volatile
        private var cache: SimpleCache? = null
    }
}

/** Mirrors the :data:media policy without a core->data dependency. */
object MediaPlaybackRules {
    const val MIN_CACHE_BYTES: Long = 64L * 1024 * 1024
    const val MAX_CACHE_BYTES: Long = 3L * 1024 * 1024 * 1024
    const val PREFETCH_BYTES: Long = 6L * 1024 * 1024
    const val PREFETCH_LEAD_MS: Long = 8_000
    const val NO_FRAME_TIMEOUT_MS: Long = 8_000
    const val MAX_CONSECUTIVE_FAILURES: Int = 3

    fun cacheCapBytes(freeBytes: Long): Long =
        (freeBytes / 4).coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)

    fun shouldPrefetch(networkMetered: Boolean, millisUntilScreensaver: Long): Boolean =
        !networkMetered && millisUntilScreensaver in 0..PREFETCH_LEAD_MS

    fun isDecoderError(code: Int): Boolean = code in 4_001..4_005
}
