package com.github.mytv.launcher

import android.app.ActivityManager
import android.app.WallpaperManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.github.mytv.core.ambient.AmbientPlaybackController
import com.github.mytv.core.ambient.AmbientPlayerStatus
import com.github.mytv.core.ambient.AmbientState
import com.github.mytv.core.ambient.BackdropOverride
import com.github.mytv.core.ambient.BackdropSource
import com.github.mytv.core.ambient.FrameGrabGuard
import com.github.mytv.core.ambient.FrozenBlurGenerator
import com.github.mytv.core.designsystem.ArtworkLoader
import com.github.mytv.core.designsystem.FrozenBackdrop
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.dynamiccolor.FIXED_SEED
import com.github.mytv.core.dynamiccolor.RoomSeedCache
import com.github.mytv.core.dynamiccolor.SchemeStyle
import com.github.mytv.core.dynamiccolor.SeedExtractor
import com.github.mytv.core.dynamiccolor.SeedResult
import com.github.mytv.core.dynamiccolor.SourceKey
import com.github.mytv.core.dynamiccolor.ThemeController
import com.github.mytv.core.glass.DeviceTier
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.glass.blurRegionCount
import com.github.mytv.core.glass.resolveGlassTier
import com.github.mytv.core.tvfocus.FocusEntry
import com.github.mytv.core.tvfocus.TvFocusState
import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.AppPrefsRepository
import com.github.mytv.data.apps.AppsRepository
import com.github.mytv.data.apps.Folder
import com.github.mytv.data.channels.ChannelApproval
import com.github.mytv.data.channels.ChannelEntry
import com.github.mytv.data.channels.ChannelProgram
import com.github.mytv.data.channels.ChannelRepository
import com.github.mytv.data.channels.WatchNextEntry
import com.github.mytv.data.channels.WatchNextRepository
import com.github.mytv.data.channels.WatchlistRepository
import com.github.mytv.data.media.AmbientPlaylist
import com.github.mytv.data.media.AmbientResume
import com.github.mytv.data.media.SharedPreferencesAmbientResumeStore
import com.github.mytv.data.restricted.PinVerifyResult
import com.github.mytv.data.restricted.RestrictedMode
import com.github.mytv.data.restricted.RestrictedRepository
import com.github.mytv.data.series.SeriesRepository
import com.github.mytv.data.widgets.WidgetBinding
import com.github.mytv.data.widgets.WidgetState
import com.github.mytv.data.widgets.WidgetsRepository
import com.github.mytv.data.weather.LunarCalendar
import com.github.mytv.data.weather.LunarText
import com.github.mytv.data.weather.WeatherRepository
import com.github.mytv.data.weather.WeatherState
import com.github.mytv.data.weather.is24h
import com.github.mytv.feature.apps.AppsGridItem
import com.github.mytv.feature.organizer.OrganizerState
import com.github.mytv.feature.organizer.PhoneOrganizerServer
import com.github.mytv.feature.widgets.WidgetsUiState
import com.github.mytv.feature.home.HomeItem
import com.github.mytv.feature.home.HomeRowData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WEATHER_VISIBLE_MS = 3L * 60 * 60 * 1000

private val SYSTEM_PACKAGES = setOf(
    "com.android.systemui",
    "com.android.tv.settings",
    "com.android.settings",
    "com.google.android.tv.settings",
    "com.android.providers.tv",
    "android",
)

data class MenuRequest(
    val app: AppEntry,
    val folderId: String?,
)

data class RowSnapshot(
    val id: String,
    val items: List<String>,
)

data class FolderSnapshot(
    val id: String,
    val name: String,
    val apps: List<String>,
)

data class FocusSnapshot(
    val tag: String,
    val rowId: String?,
    val index: Int?,
)

data class ThemeSnapshot(
    val seed: Int,
    val sourceKey: String,
    val style: String,
    val contrast: Double,
    val transitioning: Boolean,
    val roles: LauncherColors,
)

data class LauncherSnapshot(
    val page: String,
    val overlay: String?,
    val mode: String,
    val focus: FocusSnapshot?,
    val heroVisible: Boolean,
    val heroIndex: Int?,
    val heroKey: String?,
    val rows: List<RowSnapshot>,
    val approvals: List<ChannelApproval> = emptyList(),
    val watchlist: List<String>,
    val hiddenApps: List<String>,
    val folders: List<FolderSnapshot>,
    val clockIs24h: Boolean,
    val dateText: String = "",
    val lunarText: String = "",
    val weatherVisible: Boolean = false,
    val weatherTempC: Int? = null,
    val weatherCode: Int? = null,
    val weatherCity: String? = null,
    val restrictedMode: String = "off",
    val restrictedRemaining: Long = 0,
    val restrictedHasPin: Boolean = false,
    val restrictedPinLockedUntil: Long = 0,
    val restrictedUnlockedUntil: Long = 0,
    val organizerToken: String = "",
    val widgetsRevision: Int = 0,
    val theme: ThemeSnapshot = ThemeSnapshot(
        seed = FIXED_SEED,
        sourceKey = "fixed",
        style = "tonal",
        contrast = 0.0,
        transitioning = false,
        roles = LauncherColors.Default,
    ),
    val glassTier: String = "T3",
    val blurRegions: Int = 0,
    val deviceTier: String = "mid",
    val frozenBlurBytes: Long = 0,
) {
    companion object {
        val EMPTY = LauncherSnapshot(
            page = "home",
            overlay = null,
            mode = "browse",
            focus = null,
            heroVisible = false,
            heroIndex = null,
            heroKey = null,
            rows = emptyList(),
            watchlist = emptyList(),
            hiddenApps = emptyList(),
            folders = emptyList(),
            clockIs24h = true,
        )
    }
}

class LauncherModel(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.CurrentThread)
    private val appsRepo = AppsRepository(appContext)
    private val channelsRepo = WatchNextRepository(appContext)
    private val seriesRepo = SeriesRepository(appContext)
    val channelRepo = ChannelRepository(appContext, seriesRepo)
    private val watchlistRepo = WatchlistRepository(appContext)
    private val prefsRepo = AppPrefsRepository(appContext)
    private val settings = SettingsStore(appContext)
    private val seedExtractor = SeedExtractor(RoomSeedCache(appContext))
    private val artworkLoader = ArtworkLoader(appContext)
    private val frozenGenerator = FrozenBlurGenerator(appContext)
    private val weatherRepo = WeatherRepository(appContext)
    private val restrictedRepo = RestrictedRepository(appContext)
    private val organizerServer = PhoneOrganizerServer(appContext)
    val widgetsRepository: WidgetsRepository = WidgetsHolder.get(appContext)
    private val ambientResume = AmbientResume(SharedPreferencesAmbientResumeStore(appContext))
    private val ambientController = AmbientPlaybackController(
        appContext,
        FrameGrabGuard(Build.FINGERPRINT),
        ambientResume.asSink(),
        ambientResume.resumePoint(),
    )
    var ambientState by mutableStateOf(ambientController.state.value)
        private set

    val focus = TvFocusState()
    val theme = ThemeController(scope)

    var settingsValues by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var deviceTier by mutableStateOf(DeviceTier.Mid)
        private set
    var glassTier by mutableStateOf(GlassTier.T3)
        private set
    var highContrastFocus by mutableStateOf(false)
        private set
    var homeReset by mutableStateOf(false)
    var homeRequestVersion by mutableIntStateOf(0)
        private set

    /** HOME 请求后短暂抑制 Tab 的停留自动切换，避免焦点过渡期间把页面切走。 */
    var tabSwitchSuppressedUntil = 0L
        private set
    var idleMode by mutableStateOf<String?>(null)
        private set
    var channelEntries by mutableStateOf<List<ChannelEntry>>(emptyList())
        private set
    var channelPrograms by mutableStateOf<Map<String, List<ChannelProgram>>>(emptyMap())
        private set
    var channelApprovals by mutableStateOf<List<ChannelApproval>>(emptyList())
        private set
    var programMenu by mutableStateOf<ChannelProgram?>(null)
        private set
    var backdropSource by mutableStateOf<BackdropSource>(BackdropSource.None)
        private set
    var frozenBackdrop by mutableStateOf<FrozenBackdrop?>(null)
        private set

    private val seedMemory = HashMap<String, SeedResult>()
    private var themeFocusJob: Job? = null
    private var backdropJob: Job? = null
    private var frozenJob: Job? = null
    private var settingsReady = false
    private var wallpaperSeed: Int? = null
    private var runtimeGlassCap: GlassTier? = null

    var page by mutableStateOf("home")
        private set
    var overlay by mutableStateOf<String?>(null)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var continueItems by mutableStateOf<List<WatchNextEntry>>(emptyList())
        private set
    var watchlistEntries by mutableStateOf<List<WatchNextEntry>>(emptyList())
        private set
    var hiddenApps by mutableStateOf<Set<String>>(emptySet())
        private set
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var widgetBindings by mutableStateOf<List<WidgetBinding>>(emptyList())
        private set
    var menuRequest by mutableStateOf<MenuRequest?>(null)
        private set
    var folderPickStage by mutableStateOf("folders")
        private set
    var folderOpenId by mutableStateOf<String?>(null)
        private set
    var settingsHiddenExpanded by mutableStateOf(false)
        private set
    var clockText by mutableStateOf("")
        private set
    var clockIs24h by mutableStateOf(true)
        private set
    var dateText by mutableStateOf("")
        private set
    var lunarText by mutableStateOf("")
        private set
    var weatherVisible by mutableStateOf(false)
        private set
    var weatherTempC by mutableStateOf<Int?>(null)
        private set
    var weatherCode by mutableStateOf<Int?>(null)
        private set
    var weatherIsDay by mutableStateOf(true)
        private set
    var weatherCity by mutableStateOf<String?>(null)
        private set
    private var weatherUpdatedAt = 0L
    var restrictedMode by mutableStateOf("off")
        private set
    var restrictedAllowed by mutableStateOf<Set<String>>(emptySet())
        private set
    var restrictedRemaining by mutableStateOf(0L)
        private set
    var restrictedHasPin by mutableStateOf(false)
        private set
    var restrictedPinLockedUntil by mutableStateOf(0L)
        private set
    var restrictedUnlockedUntil by mutableStateOf(0L)
        private set
    var pinInput by mutableStateOf("")
        private set
    var pinWrong by mutableStateOf(false)
        private set
    var tvInputs by mutableStateOf<List<android.media.tv.TvInputInfo>>(emptyList())
        private set
    var organizerState by mutableStateOf(organizerServer.state.value)
        private set
    var widgetsUiState by mutableStateOf(WidgetsUiState())
        private set
    private var widgetColorJob: Job? = null
    private var organizerOrigin = "settings"
    var restored by mutableStateOf(false)
        private set
    var uiReady by mutableStateOf(false)
        private set
    var initialFocusApplied by mutableStateOf(false)
        private set
    var appsLoaded by mutableStateOf(false)
        private set
    var channelsLoaded by mutableStateOf(false)
        private set
    var userActionVersion by mutableIntStateOf(0)
        private set
    var restoreFocusPending by mutableStateOf(false)
        private set

    private var savedFocus: String? = null
    private var restoredPage: String? = null
    private var lastInteraction = System.currentTimeMillis()
    private var kidsLockRaised = false
    private var homeRequested = false
    private var usedSavedFocus = false
    private var homeFocusPending = true
    private var overlayReturnTag: String? = null
    private var lastStableTag: String? = null
    private var persistJob: Job? = null

    val hero: WatchNextEntry?
        get() = if (restrictedMode == "kids") null else continueItems.firstOrNull()

    val heroInWatchlist: Boolean
        get() {
            val item = hero ?: return false
            return watchlistEntries.any { it.watchlistKey == item.watchlistKey }
        }

    init {
        com.github.mytv.launcher.config.RestrictedConfigEvents.onPinCleared = {
            refreshRestricted(resetQuota = false)
        }
        maybeLaunchBootTarget()
        scope.launch {
            appsRepo.apps.collect {
                apps = it
            }
        }
        scope.launch {
            appsRepo.loaded.collect { appsLoaded = it }
        }
        scope.launch {
            channelsRepo.items.collect { continueItems = it }
        }
        scope.launch {
            channelRepo.channels.collect { channelEntries = it }
        }
        scope.launch {
            channelRepo.programs.collect { channelPrograms = it }
        }
        scope.launch {
            channelRepo.approvals.collect { channelApprovals = it }
        }
        scope.launch {
            channelsRepo.loaded.collect { channelsLoaded = it }
        }
        scope.launch {
            watchlistRepo.entries.collect { watchlistEntries = it }
        }
        scope.launch {
            prefsRepo.hiddenApps.collect { hiddenApps = it }
        }
        scope.launch {
            prefsRepo.folders.collect { folders = it }
        }
        scope.launch {
            widgetsRepository.bindings.collect { widgetBindings = it }
        }
        scope.launch {
            val (persistedPage, tag) = prefsRepo.readPageFocus()
            if (!homeRequested && persistedPage != null && persistedPage in setOf("home", "apps", "widgets", "settings")) {
                page = persistedPage
            }
            restoredPage = page
            savedFocus = tag
            restoreFocusPending = tag != null
            restored = true
            uiReady = true
        }
        theme.onTransitionStart = { StateHub.incrementThemeTransition() }
        scope.launch {
            settings.values.collect { values ->
                val previousSource = settingsValues["color_source"]
                val previousStyle = settingsValues["scheme_style"]
                val previousContrast = settingsValues["contrast"]
                val previousGlass = settingsValues["glass_tier"]
                val previousCity = settingsValues["weather_city"]
                val previousWeatherBase = settingsValues["weather_base_url"]
                val previousRestrictedMode = settingsValues["restricted_mode"]
                val previousRestrictedAllowed = settingsValues["restricted_allowed"]
                val previousQuota = settingsValues["kids_quota_s"]
                val previousPin = settingsValues["pin"]
                settingsValues = values
                highContrastFocus = values["high_contrast_focus"] == "true"
                deviceTier = when (values["device_tier"]) {
                    "low" -> DeviceTier.Low
                    "mid" -> DeviceTier.Mid
                    "high" -> DeviceTier.High
                    else -> detectDeviceTier(appContext)
                }
                if (previousGlass != values["glass_tier"]) runtimeGlassCap = null
                val resolvedTier = resolveGlassTier(values["glass_tier"] ?: "auto", deviceTier, Build.VERSION.SDK_INT)
                val nextTier = if (runtimeGlassCap == GlassTier.T2 && resolvedTier == GlassTier.T1) GlassTier.T2 else resolvedTier
                val tierChanged = nextTier != glassTier
                glassTier = nextTier
                if (tierChanged) refreshFrozen()
                if (settingsReady &&
                    (previousSource != values["color_source"] ||
                        previousStyle != values["scheme_style"] ||
                        previousContrast != values["contrast"])
                ) {
                    applyCurrentTheme(animate = false)
                }
                if (settingsReady &&
                    (previousCity != values["weather_city"] || previousWeatherBase != values["weather_base_url"])
                ) {
                    scope.launch { weatherRepo.refresh() }
                }
                if (settingsReady) {
                    refreshRestricted(
                        resetQuota = previousRestrictedMode != values["restricted_mode"] ||
                            previousQuota != values["kids_quota_s"],
                    )
                } else {
                    refreshRestricted(resetQuota = true)
                }
                settingsReady = true
            }
        }
        scope.launch {
            BackdropOverride.state.collect { override ->
                if (override != null) {
                    backdropSource = override
                    refreshFrozen()
                } else {
                    updateBackdropForTag(focus.current?.tag, force = true)
                }
            }
        }
        scope.launch { clockLoop() }
        scope.launch {
            weatherRepo.state.collect { applyWeather(it) }
        }
        scope.launch { weatherLoop() }
        scope.launch { idleLoop() }
        scope.launch { restrictedLoop() }
        scope.launch {
            ambientController.state.collect { ambientState = it }
        }
        scope.launch {
            organizerServer.state.collect { state ->
                organizerState = state
                if (page == "organizer" && state.pendingConnection != null && overlay != "confirm_phone") {
                    overlayReturnTag = focus.current?.tag
                    overlay = "confirm_phone"
                    focusLater("confirm_phone:allow")
                } else if (overlay == "confirm_phone" && state.pendingConnection == null) {
                    overlay = null
                    val back = overlayReturnTag
                    overlayReturnTag = null
                    focusLater(back ?: "organizer:target")
                }
            }
        }
        StateHub.organizerProvider = { organizerServer.organizerJson() }
        StateHub.storageProvider = { organizerServer.storageJson() }
        StateHub.widgetsProvider = {
            org.json.JSONArray().apply {
                for (binding in widgetsRepository.bindings.value) put(binding.toJson())
            }
        }
        StateHub.ambientProvider = {
            val ambient = ambientController.state.value
            org.json.JSONObject()
                .put("source", ambient.source.wireName)
                .put("clip", ambient.clip?.id ?: org.json.JSONObject.NULL)
                .put("positionMs", ambient.positionMs)
                .put("player", ambient.player.wireName)
        }
    }

    private fun refreshRestricted(resetQuota: Boolean) {
        restrictedRepo.importLegacyPlainPin()
        val snapshot = restrictedRepo.snapshot()
        val mode = snapshot.mode.wire
        if (restrictedMode == "kids" && mode != "kids" && page == "kids_lock") {
            page = "home"
            overlay = null
        }
        restrictedMode = mode
        restrictedAllowed = snapshot.allowedPackages
        restrictedHasPin = snapshot.hasPin
        restrictedPinLockedUntil = snapshot.pinLockedUntil
        restrictedUnlockedUntil = snapshot.unlockedUntil
        var remaining = snapshot.remainingSeconds
        if (mode == "kids" && resetQuota && remaining <= 0L && snapshot.kidsQuotaSeconds > 0L) {
            remaining = snapshot.kidsQuotaSeconds
            restrictedRepo.setRemainingSeconds(remaining)
        }
        restrictedRemaining = remaining
        if (remaining > 0L || mode != "kids") kidsLockRaised = false
        if (mode != "off") {
            lastInteraction = System.currentTimeMillis()
        }
    }

    private suspend fun restrictedLoop() {
        while (true) {
            delay(1_000L)
            if (restrictedMode != "kids" || !restored) continue
            val foreground = foregroundPackage() ?: continue
            if (foreground == appContext.packageName) continue
            if (foreground in SYSTEM_PACKAGES) continue
            if (foreground in restrictedAllowed) {
                if (restrictedRemaining > 0L) {
                    restrictedRemaining -= 1L
                    restrictedRepo.setRemainingSeconds(restrictedRemaining)
                }
                if (restrictedRemaining <= 0L && !kidsLockRaised) {
                    kidsLockRaised = true
                    openKidsLock(background = true)
                }
            } else {
                launchLauncher()
            }
        }
    }

    private var lastForegroundPackage: String? = null

    @Suppress("DEPRECATION")
    private fun foregroundPackage(): String? {
        return try {
            val manager = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return lastForegroundPackage
            val now = System.currentTimeMillis()
            val events = manager.queryEvents(now - 120_000L, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastForegroundPackage = event.packageName
                }
            }
            lastForegroundPackage
        } catch (_: Exception) {
            lastForegroundPackage
        }
    }

    private fun launchLauncher(kidsLock: Boolean = false) {
        try {
            val intent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (kidsLock) intent.putExtra("kids_lock", true)
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun openKidsLock(background: Boolean) {
        if (page != "kids_lock") {
            page = "kids_lock"
            overlay = null
            focus.request("kids_lock:extend15")
        }
        if (background) launchLauncher(true)
    }

    fun showKidsLock() {
        openKidsLock(background = false)
    }

    fun openParentPin() {
        overlayReturnTag = focus.current?.tag
        pinInput = ""
        pinWrong = false
        overlay = "pin"
    }

    fun onPinDigit(digit: Char) {
        if (overlay != "pin") return
        if (pinInput.length >= 4) return
        pinInput += digit
        if (pinInput.length == 4) verifyPin()
    }

    fun onPinBackspace() {
        if (overlay != "pin") return
        pinWrong = false
        pinInput = pinInput.dropLast(1)
    }

    private fun verifyPin() {
        when (val result = restrictedRepo.parentPin.verify(pinInput)) {
            is PinVerifyResult.Success -> {
                restrictedRemaining += 15 * 60L
                kidsLockRaised = false
                restrictedRepo.setRemainingSeconds(restrictedRemaining)
                restrictedUnlockedUntil = result.unlockedUntil
                pinInput = ""
                pinWrong = false
                overlay = null
                if (page == "kids_lock") {
                    page = "home"
                    focus.request(defaultHomeFocus() ?: "nav:home")
                }
            }
            else -> {
                pinInput = ""
                pinWrong = true
                refreshRestricted(resetQuota = false)
            }
        }
    }

    private suspend fun idleLoop() {
        while (true) {
            delay(1_000L)
            if (!restored || page != "home" || overlay != null || idleMode != null) {
                if (page != "home" || overlay != null) lastInteraction = System.currentTimeMillis()
                continue
            }
            val idle = System.currentTimeMillis() - lastInteraction
            val ambientSeconds = settingsValues["ambient_idle_s"]?.toIntOrNull() ?: 0
            val saverSeconds = settingsValues["saver_idle_s"]?.toIntOrNull()?.takeIf { it > 0 } ?: 600
            if (ambientSeconds > 0 && idle >= ambientSeconds * 1000L) {
                enterIdle("ambient")
            } else if (idle >= saverSeconds * 1000L) {
                enterIdle("saver")
            }
        }
    }

    private fun enterIdle(mode: String) {
        idleMode = mode
        userActionVersion++
        startAmbientPlayback(mode == "ambient")
    }

    private fun startAmbientPlayback(video: Boolean) {
        val clips = try {
            AmbientPlaylist.load(appContext.assets)
        } catch (_: Exception) {
            emptyList()
        }
        if (clips.isEmpty()) return
        val rotation = ambientResume.rotationFor(clips.map { it.id })
        val selected = clips.filter { it.id in rotation }.ifEmpty { clips }
        ambientController.setPlaylist(selected)
        if (video) {
            ambientController.start(ambientResume.resumePoint())
        }
    }

    fun attachAmbientSurface(view: android.view.SurfaceView?) {
        if (view == null) ambientController.detachSurface() else ambientController.attachSurface(view)
    }

    fun isIdleMode(): Boolean = idleMode != null

    fun onHostStopped() {
        if (idleMode == null) return
        idleMode = null
        ambientState.clip?.let { clip -> ambientResume.saveOnLeave(clip.id, ambientState.positionMs) }
        ambientController.releaseDecoderForBackground()
        lastInteraction = System.currentTimeMillis()
        userActionVersion++
    }

    fun exitIdle(): Boolean {
        if (idleMode == null) return false
        idleMode = null
        ambientState.clip?.let { clip -> ambientResume.saveOnLeave(clip.id, ambientState.positionMs) }
        ambientController.pause()
        lastInteraction = System.currentTimeMillis()
        userActionVersion++
        return true
    }

    fun onScreenOff() {
        BootTargetState.launchedThisProcess = false
        restrictedRepo.onScreenOff()
        restrictedUnlockedUntil = restrictedRepo.snapshot().unlockedUntil
    }

    fun startSaver() {
        overlay = null
        enterIdle("saver")
    }

    fun startAmbient() {
        overlay = null
        enterIdle("ambient")
    }

    fun openWifiSettings() {
        overlay = null
        val intents = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    fun openBluetoothSettings() {
        overlay = null
        val intents = listOf(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    fun toggleDnd() {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val next = if (audio.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) {
            android.media.AudioManager.RINGER_MODE_NORMAL
        } else {
            android.media.AudioManager.RINGER_MODE_SILENT
        }
        try {
            audio.ringerMode = next
        } catch (_: Exception) {
        }
    }

    fun adjustVolume(delta: Int) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return
        audio.adjustStreamVolume(
            android.media.AudioManager.STREAM_MUSIC,
            if (delta > 0) android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI,
        )
    }

    fun openInputs() {
        overlayReturnTag = focus.current?.tag
        overlay = "inputs"
        tvInputs = try {
            val manager = appContext.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager
            manager?.tvInputList.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        tvInputs.firstOrNull()?.let { focusLater("input:${it.id}") } ?: focusLater("inputs")
    }

    fun launchInput(input: android.media.tv.TvInputInfo) {
        overlay = null
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setData(android.media.tv.TvContract.buildChannelUriForPassthroughInput(input.id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }

    fun openTransfer() {
        openOrganizerFrom("quick_settings")
    }

    fun openOrganizer() {
        openOrganizerFrom("settings")
    }

    private fun openOrganizerFrom(origin: String) {
        organizerOrigin = origin
        overlay = null
        page = "organizer"
        organizerServer.onPageOpened()
        focusLater("organizer:target")
    }

    fun decideOrganizerConnection(allow: Boolean) {
        organizerServer.decideConnection(allow)
    }

    fun disconnectOrganizerSession(id: String) {
        organizerServer.disconnectSession(id)
    }

    fun cancelOrganizerTransfer(id: String) {
        organizerServer.cancelTransfer(id)
    }

    fun removeOrganizerTransfer(id: String) {
        organizerServer.removeTransfer(id)
    }

    fun deleteOrganizerTransfer(id: String) {
        organizerServer.deleteTransfer(id)
    }

    fun openOrganizerTransfer(id: String) {
        organizerServer.openTransfer(id)
    }

    fun setOrganizerTarget(root: String, path: String) {
        organizerServer.setTarget(root, path)
    }

    fun openOrganizerStorageSettings() {
        try {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(android.net.Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            try {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun closeOrganizer() {
        if (page == "organizer") {
            organizerServer.onPageClosed()
        }
    }

    private fun maybeLaunchBootTarget() {
        if (BootTargetState.launchedThisProcess) return
        val prefs = appContext.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        val freshBoot = if (Build.VERSION.SDK_INT >= 24) {
            // 进程在开机很久之后被回收重启时不能再按“新开机”打开目标应用
            if (android.os.SystemClock.elapsedRealtime() > 120_000L) {
                false
            } else {
                val count = try {
                    Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
                } catch (_: Exception) {
                    -1
                }
                val seen = prefs.getInt("boot_count_seen", -1)
                if (count >= 0) prefs.edit().putInt("boot_count_seen", count).commit()
                count >= 0 && count != seen
            }
        } else {
            android.os.SystemClock.elapsedRealtime() < 120_000L
        }
        if (!freshBoot) return
        val target = prefs.getString("boot_target", "home") ?: "home"
        if (!target.startsWith("app:")) return
        val component = android.content.ComponentName.unflattenFromString(target.removePrefix("app:"))
            ?: return
        BootTargetState.launchedThisProcess = true
        prefs.edit().putLong("boot_elapsed_ms", android.os.SystemClock.elapsedRealtime()).commit()
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        } catch (_: Exception) {
        }
    }

    fun onScreenOn() {
        if (BootTargetState.launchedThisProcess) return
        val prefs = appContext.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        val bootElapsed = prefs.getLong("boot_elapsed_ms", 0L)
        if (bootElapsed > 0L && android.os.SystemClock.elapsedRealtime() - bootElapsed < 25_000L) return
        val target = prefs.getString("boot_target", "home") ?: "home"
        if (!target.startsWith("app:")) return
        val component = android.content.ComponentName.unflattenFromString(target.removePrefix("app:"))
            ?: return
        BootTargetState.launchedThisProcess = true
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        } catch (_: Exception) {
        }
    }

    private fun applyWeather(state: WeatherState) {
        weatherUpdatedAt = state.updatedAtMillis
        weatherTempC = state.tempC
        weatherCode = state.code
        weatherIsDay = state.isDay
        weatherCity = state.city
        weatherVisible = state.visible && state.tempC != null &&
            System.currentTimeMillis() - state.updatedAtMillis < WEATHER_VISIBLE_MS
    }

    private suspend fun weatherLoop() {
        while (true) {
            try {
                weatherRepo.refresh()
            } catch (_: Exception) {
                // The policy keeps the previous reading and backoff.
            }
            delay(10 * 60_000L)
        }
    }

    fun onFocusSettled(tag: String?) {
        if (tag.isNullOrBlank()) return
        themeFocusJob?.cancel()
        themeFocusJob = scope.launch {
            delay(600)
            if (focus.current?.tag != tag) return@launch
            applyThemeForTag(tag, animate = true)
        }
        backdropJob?.cancel()
        backdropJob = scope.launch {
            delay(260)
            if (focus.current?.tag != tag) return@launch
            updateBackdropForTag(tag)
        }
        widgetColorJob?.cancel()
        widgetColorJob = scope.launch {
            delay(6_000L)
            if (focus.current?.tag != tag) return@launch
            applyWidgetColors()
        }
    }

    private fun applyWidgetColors() {
        val colors = theme.colors.value
        val map = mapOf(
            android.R.color.system_accent1_100 to colors.primary.toArgb(),
            android.R.color.system_accent1_500 to colors.primary.toArgb(),
            android.R.color.system_accent2_100 to colors.secondaryContainer.toArgb(),
            android.R.color.system_neutral1_100 to colors.surface.toArgb(),
            android.R.color.system_neutral2_100 to colors.surfaceContainer.toArgb(),
        )
        if (widgetsRepository.applyColors(map, force = true) > 0) {
            for (binding in widgetsRepository.bindings.value) {
                StateHub.incrementWidgetReload(binding.appWidgetId)
            }
        }
    }

    fun onWidgetsUiState(state: WidgetsUiState) {
        widgetsUiState = state
    }

    fun setHighContrast(value: Boolean) {
        settings.set("high_contrast_focus", if (value) "true" else "false")
    }

    fun onFrameBudgetExceeded(): Boolean {
        if ((settingsValues["glass_tier"] ?: "auto") != "auto") return false
        if (glassTier != GlassTier.T1) return false
        runtimeGlassCap = GlassTier.T2
        glassTier = GlassTier.T2
        refreshFrozen()
        return true
    }

    fun adjustSetting(key: String, delta: Int) {
        val current = settingsValues[key]
        val next = when (key) {
            "color_source" -> cycle(current ?: "content", listOf("content", "icon", "wallpaper", "fixed"), delta)
            "glass_tier" -> cycle(current ?: "auto", listOf("auto", "T1", "T2", "T3"), delta)
            "scheme_style" -> cycle(current ?: "tonal", listOf("tonal", "content", "vibrant", "expressive"), delta)
            "contrast" -> cycle(current ?: "0", listOf("0", "0.5", "1"), delta)
            else -> return
        }
        settingsValues = settingsValues + (key to next)
        if (key == "glass_tier") {
            if (next != "auto") runtimeGlassCap = null
            val resolved = resolveGlassTier(next, deviceTier, Build.VERSION.SDK_INT)
            val tier = if (runtimeGlassCap == GlassTier.T2 && resolved == GlassTier.T1) GlassTier.T2 else resolved
            if (tier != glassTier) {
                glassTier = tier
                refreshFrozen()
            }
        } else {
            applyCurrentTheme(animate = false)
        }
        settings.set(key, next)
    }

    private fun cycle(current: String, values: List<String>, delta: Int): String {
        val index = values.indexOf(current).takeIf { it >= 0 } ?: 0
        return values[(index + delta).mod(values.size)]
    }

    private fun applyCurrentTheme(animate: Boolean) {
        val tag = focus.current?.tag ?: return
        scope.launch { applyThemeForTag(tag, animate) }
    }

    private suspend fun applyThemeForTag(tag: String, animate: Boolean) {
        val style = SchemeStyle.fromKey(settingsValues["scheme_style"] ?: "tonal")
        val contrast = (settingsValues["contrast"] ?: "0").toDoubleOrNull() ?: 0.0
        when (settingsValues["color_source"] ?: "content") {
            "fixed" -> theme.apply(FIXED_SEED, "fixed", style, contrast, animate = false)
            "wallpaper" -> {
                val seed = wallpaperSeed()
                if (seed != null) theme.apply(seed, "wallpaper", style, contrast, animate)
            }
            "icon" -> {
                val seed = appSeedForTag(tag)
                if (seed != null) theme.apply(seed.first, seed.second, style, contrast, animate)
            }
            else -> {
                val seed = contentSeedForTag(tag)
                if (seed != null) theme.apply(seed.first, seed.second, style, contrast, animate)
            }
        }
    }

    private suspend fun contentSeedForTag(tag: String): Pair<Int, String>? = when {
        tag.startsWith("app:") || tag.startsWith("folder:") -> appSeedForTag(tag)
        tag.startsWith("wn:") || tag.startsWith("wl:") -> {
            val entry = continueItems.firstOrNull { it.key == tag || it.watchlistKey == tag }
                ?: watchlistEntries.firstOrNull { it.watchlistKey == tag }
            entry?.let { artSeed(it) }
        }
        tag.startsWith("hero:") -> hero?.let { artSeed(it) }
        else -> null
    }

    private suspend fun appSeedForTag(tag: String): Pair<Int, String>? {
        val app = if (tag.startsWith("folder:")) {
            val folderId = tag.removePrefix("folder:")
            folderApps(folderId)?.second?.firstOrNull()
        } else {
            apps.firstOrNull { it.tag == tag }
        } ?: return null
        val key = "app:${app.packageName}:${app.versionCode}:${app.lastUpdateTime}"
        val existing = seedMemory[key]
        val result = existing ?: seedExtractor.seedFor(SourceKey(key)) { appIconPixels(app) }
            .also { seedMemory[key] = it }
        return (result as? SeedResult.Seed)?.let { it.argb to key }
    }

    private suspend fun artSeed(entry: WatchNextEntry): Pair<Int, String>? {
        val uri = entry.posterArtUri ?: return null
        val key = "art:$uri"
        val existing = seedMemory[key]
        val result = existing ?: seedExtractor.seedFor(SourceKey(key)) {
            artworkLoader.pixels(uri, 48, 48)
        }.also { seedMemory[key] = it }
        return (result as? SeedResult.Seed)?.let { it.argb to key }
    }

    private fun appIconPixels(app: AppEntry): IntArray? {
        val icon = app.icon ?: return null
        val scaled = Bitmap.createScaledBitmap(icon, 48, 48, true)
        val pixels = IntArray(48 * 48)
        scaled.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (scaled !== icon) scaled.recycle()
        return pixels
    }

    private suspend fun wallpaperSeed(): Int? {
        wallpaperSeed?.let { return it }
        val bitmap = try {
            WallpaperManager.getInstance(appContext).drawable?.let {
                Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { target ->
                    val canvas = android.graphics.Canvas(target)
                    it.setBounds(0, 0, 48, 48)
                    it.draw(canvas)
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val pixels = IntArray(48 * 48)
        bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        bitmap.recycle()
        val result = seedExtractor.seedFor(SourceKey("wallpaper")) { pixels }
        return (result as? SeedResult.Seed)?.argb?.also { wallpaperSeed = it }
    }

    private fun updateBackdropForTag(tag: String?, force: Boolean = false) {
        if (BackdropOverride.state.value != null) return
        if (tag == null) return
        val uri = when {
            tag.startsWith("hero:") -> hero?.posterArtUri
            tag.startsWith("wn:") || tag.startsWith("wl:") ->
                (continueItems.firstOrNull { it.key == tag || it.watchlistKey == tag }
                    ?: watchlistEntries.firstOrNull { it.watchlistKey == tag })?.posterArtUri
            else -> null
        } ?: return
        val source = BackdropSource.Artwork(uri)
        if (force || source != backdropSource) {
            backdropSource = source
            refreshFrozen()
        }
    }

    private fun refreshFrozen() {
        frozenJob?.cancel()
        val tier = glassTier
        if (tier != GlassTier.T2) {
            frozenBackdrop = null
            return
        }
        val source = backdropSource
        val metrics = appContext.resources.displayMetrics
        frozenJob = scope.launch {
            frozenBackdrop = frozenGenerator.generate(source, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun detectDeviceTier(context: Context): DeviceTier {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val lowMemory = manager?.isLowRamDevice == true || info.totalMem <= 1_600_000_000L
        return when {
            lowMemory -> DeviceTier.Low
            Build.VERSION.SDK_INT < 31 -> DeviceTier.Mid
            else -> DeviceTier.High
        }
    }

    fun refresh() {
        appsRepo.refresh()
        channelsRepo.refresh()
    }

    fun ensureFocus() {
        if (!restored || overlay != null) return
        if (focus.pendingTag != null) return
        if (restoreFocusPending) {
            val saved = savedFocus
            if (saved != null && page == restoredPage && isAvailable(saved)) {
                if (focus.current?.tag != saved) applyFocus(saved)
                if (appsLoaded && channelsLoaded && focus.current?.tag == saved) {
                    restoreFocusPending = false
                }
                return
            }
            if (appsLoaded && channelsLoaded) restoreFocusPending = false
        }
        if (!dataReadyForPage()) return
        if (page == "home" && homeFocusPending) {
            if (!usedSavedFocus) {
                usedSavedFocus = true
                val saved = savedFocus
                if (saved != null && isAvailable(saved)) {
                    applyFocus(saved)
                    homeFocusPending = false
                    return
                }
            }
            val target = defaultHomeFocus()
            if (target != null) {
                applyFocus(target)
                homeFocusPending = false
            }
            return
        }
        if (!usedSavedFocus) {
            usedSavedFocus = true
            val saved = savedFocus
            if (saved != null && isAvailable(saved)) {
                applyFocus(saved)
                return
            }
        }
        if (focus.current != null) return
        applyFocus(defaultFocusForPage())
    }

    private fun applyFocus(tag: String?) {
        if (tag.isNullOrEmpty()) return
        initialFocusApplied = true
        focus.request(tag)
    }

    fun userInteracted() {
        homeFocusPending = false
        homeReset = false
        initialFocusApplied = true
        restoreFocusPending = false
        lastInteraction = System.currentTimeMillis()
        userActionVersion++
    }

    fun noteFocus(tag: String?) {
        if (tag.isNullOrBlank()) return
        if (tag.startsWith("menu:") || tag.startsWith("folder_pick:") ||
            tag.startsWith("folder_name:") || tag.startsWith("qs") ||
            tag.startsWith("picker:") || tag == "folder_open"
        ) {
            return
        }
        lastStableTag = tag
        if (tag.startsWith("hero:") || tag.startsWith("nav:")) homeReset = false
        persistSoon()
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(100)
            prefsRepo.savePageFocus(page, lastStableTag)
        }
    }

    fun switchPage(newPage: String) {
        if (page == newPage) return
        closeOrganizer()
        homeReset = false
        page = newPage
        overlay = null
        folderOpenId = null
        menuRequest = null
        settingsHiddenExpanded = false
        persistSoon()
        val tag = lastStableTag
        scope.launch { prefsRepo.savePageFocus(newPage, tag) }
    }

    fun onHomeRequested() {
        exitIdle()
        lastInteraction = System.currentTimeMillis()
        closeOrganizer()
        if (restrictedMode == "kids" && restrictedRemaining <= 0L) {
            overlay = null
            page = "kids_lock"
            focus.request("kids_lock:extend15")
            return
        }
        restoreFocusPending = false
        overlay = null
        folderOpenId = null
        menuRequest = null
        folderPickStage = "folders"
        settingsHiddenExpanded = false
        page = "home"
        homeRequestVersion++
        homeRequested = true
        homeFocusPending = true
        tabSwitchSuppressedUntil = System.currentTimeMillis() + 1500
        homeReset = true
        initialFocusApplied = true
        refresh()
        val target = defaultHomeFocus()
        if (target != null) {
            focus.request(target)
            lastStableTag = target
        }
        persistSoon()
    }

    fun onBack() {
        if (overlay != null) {
            closeOverlay()
            return
        }
        if (page == "kids_lock") {
            return
        }
        homeReset = false
        if (page == "organizer") {
            closeOrganizer()
            page = organizerOrigin
            focus.request("nav:home")
            persistSoon()
            return
        }
        if (page == "settings" || page == "apps" || page == "widgets" || page == "channels") {
            val target = if (page == "channels") "manage_channels" else "nav:home"
            page = "home"
            focus.request(target)
            persistSoon()
            return
        }
        val entry = focus.current
        when {
            entry == null -> focus.request("nav:home")
            entry.tag.startsWith("hero:") -> focus.request("nav:home")
            entry.rowId != null ->
                focus.request(if (hero != null && restrictedMode != "elders") "hero:play" else "nav:home")
            entry.tag.startsWith("nav:") -> Unit
            else -> focus.request("nav:home")
        }
    }

    fun openQuickSettings() {
        homeReset = false
        overlayReturnTag = focus.current?.tag
        overlay = "quick_settings"
        focus.request("qs:source")
    }

    fun openSettings() {
        homeReset = false
        overlay = null
        folderOpenId = null
        menuRequest = null
        settingsHiddenExpanded = false
        page = "settings"
        focus.request("settings:hidden_apps")
        persistSoon()
    }

    fun toggleHiddenList() {
        settingsHiddenExpanded = !settingsHiddenExpanded
    }

    fun openMenu(app: AppEntry, folderId: String? = null) {
        menuRequest = MenuRequest(app, folderId)
        overlayReturnTag = focus.current?.tag
        overlay = "menu"
        focus.request(if (folderId == null) "menu:move" else "menu:unfolder")
    }

    fun onMenuAction(tag: String) {
        if (tag == "menu:remove") {
            programMenu?.let(::removeProgram)
            overlay = null
            menuRequest = null
            return
        }
        val request = menuRequest
        when (tag) {
            "menu:move" -> closeMenu()
            "menu:folder" -> {
                if (request != null) {
                    folderPickStage = "folders"
                    overlay = "folder_pick"
                    focus.request("folder_pick:new")
                }
            }
            "menu:hide" -> if (request != null) hideApp(request) else closeMenu()
            "menu:uninstall" -> {
                val app = request?.app
                closeMenu()
                if (app != null) {
                    try {
                        val intent = Intent(Intent.ACTION_DELETE)
                            .setData(android.net.Uri.parse("package:${app.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            }
            "menu:unfolder" -> if (request != null) unfolder(request) else closeMenu()
        }
    }

    private fun closeMenu() {
        menuRequest = null
        if (folderOpenId != null) {
            overlay = "folder_open"
            focusLater(firstFolderAppTag())
        } else {
            overlay = null
            restoreFocusOrFallback(overlayReturnTag)
        }
    }

    fun closeOverlay() {
        when (overlay) {
            "folder_pick" -> {
                overlay = null
                menuRequest = null
                folderPickStage = "folders"
                restoreFocusOrFallback(overlayReturnTag)
            }
            "folder_open" -> {
                overlay = null
                folderOpenId = null
                restoreFocusOrFallback(overlayReturnTag)
            }
            "menu" -> {
                menuRequest = null
                overlay = null
                restoreFocusOrFallback(overlayReturnTag)
            }
            else -> {
                overlay = null
                restoreFocusOrFallback(overlayReturnTag)
            }
        }
    }

    private fun focusLater(tag: String?) {
        if (tag.isNullOrEmpty()) return
        scope.launch {
            delay(40)
            focus.request(tag)
        }
    }

    private fun restoreFocusOrFallback(tag: String?) {
        val target = if (tag != null && isAvailable(tag)) tag else defaultFocusForPage()
        focusLater(target)
    }

    fun openPicker() {
        overlayReturnTag = focus.current?.tag
        overlay = "picker"
        focus.request("picker:builtin:clock")
    }

    fun pickBuiltin() {
        overlay = null
        restoreFocusOrFallback(overlayReturnTag)
    }

    fun openFolder(folder: Folder, appsInFolder: List<AppEntry>) {
        folderOpenId = folder.id
        overlayReturnTag = focus.current?.tag
        overlay = "folder_open"
        focus.request(appsInFolder.firstOrNull()?.tag)
    }

    fun pickExistingFolder(folder: Folder) {
        val request = menuRequest
        if (request == null) {
            closeOverlay()
            return
        }
        val tag = request.app.tag
        val updated = folders.map { current ->
            if (current.id == folder.id) {
                if (current.apps.contains(tag)) current else current.copy(apps = current.apps + tag)
            } else {
                current.copy(apps = current.apps - tag)
            }
        }
        folders = updated
        scope.launch { prefsRepo.setFolders(updated) }
        overlay = null
        menuRequest = null
        folderPickStage = "folders"
        focusLater(defaultHomeFocus() ?: "nav:home")
    }

    fun showFolderNames() {
        folderPickStage = "names"
        focus.request("folder_name:av")
    }

    fun chooseFolderName(name: String) {
        val request = menuRequest ?: return
        val id = nextFolderId()
        val updated = folders.map { it.copy(apps = it.apps - request.app.tag) } +
            Folder(id, name, listOf(request.app.tag))
        folders = updated
        scope.launch { prefsRepo.setFolders(updated) }
        overlay = null
        menuRequest = null
        folderPickStage = "folders"
        focusLater(defaultHomeFocus() ?: "nav:home")
    }

    private fun nextFolderId(): String {
        val max = folders.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0
        return "f${max + 1}"
    }

    private fun hideApp(request: MenuRequest) {
        val app = request.app
        val nextHidden = hiddenApps + app.tag
        hiddenApps = nextHidden
        scope.launch { prefsRepo.setHidden(nextHidden) }
        val nextFolders = folders.map { it.copy(apps = it.apps - app.tag) }
        folders = nextFolders
        scope.launch { prefsRepo.setFolders(nextFolders) }
        menuRequest = null
        folderOpenId = null
        overlay = null
        focusLater(defaultHomeFocus() ?: "nav:home")
        persistSoon()
    }

    fun restoreHidden(app: AppEntry) {
        val next = hiddenApps - app.tag
        hiddenApps = next
        scope.launch { prefsRepo.setHidden(next) }
        focus.request("settings:hidden_apps")
    }

    private fun unfolder(request: MenuRequest) {
        val app = request.app
        val folderId = request.folderId ?: return
        val next = folders.mapNotNull { folder ->
            if (folder.id != folderId) {
                folder
            } else {
                val apps = folder.apps - app.tag
                if (apps.isEmpty()) null else folder.copy(apps = apps)
            }
        }
        folders = next
        scope.launch { prefsRepo.setFolders(next) }
        menuRequest = null
        val stillOpen = next.any { it.id == folderId }
        if (stillOpen) {
            overlay = "folder_open"
            focusLater(firstFolderAppTag())
        } else {
            folderOpenId = null
            overlay = null
            focusLater(defaultHomeFocus() ?: "nav:home")
        }
        persistSoon()
    }

    fun toggleHeroWatchlist() {
        val item = hero ?: return
        scope.launch { watchlistRepo.toggle(item) }
    }

    fun playHero() {
        hero?.let(::launchWatchNext)
    }

    fun heroDetails() {
        hero?.let(::launchWatchNext)
    }

    fun launchWatchNext(entry: WatchNextEntry) {
        val raw = entry.intentUri ?: return
        val intent = try {
            Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            return
        }
        val target = intent.component?.packageName ?: intent.`package`
        if (target != entry.packageName) return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun launchApp(app: AppEntry) {
        if (restrictedMode != "off" && app.packageName !in restrictedAllowed) return
        appsRepo.launch(app)
    }

    fun homeRows(): List<HomeRowData> {
        val kids = restrictedMode == "kids"
        val result = ArrayList<HomeRowData>()
        if (!kids && continueItems.isNotEmpty()) {
            result += HomeRowData(
                id = "continue",
                title = "",
                items = continueItems.map { HomeItem.Next(it) },
            )
        }
        if (!kids && watchlistEntries.isNotEmpty()) {
            result += HomeRowData(
                id = "watchlist",
                title = "",
                items = watchlistEntries.map { HomeItem.Next(it, inWatchlist = true) },
            )
        }
        val appItems = appRowItems()
        if (appItems.isNotEmpty()) {
            result += HomeRowData(id = "apps", title = "", items = appItems)
        }
        if (!kids) {
            for (channel in channelEntries.filter { it.approved }) {
                val programs = channelPrograms[channel.rowId].orEmpty()
                if (programs.isEmpty()) continue
                result += HomeRowData(
                    id = channel.rowId,
                    title = channel.title,
                    items = programs.map { HomeItem.Program(it) },
                )
            }
            val glance = widgetBindings.filter { it.onHome && it.state == WidgetState.OK }.take(4)
            if (glance.isNotEmpty()) {
                result += HomeRowData(
                    id = "widgets",
                    title = "",
                    items = glance.map { HomeItem.Widget(it.appWidgetId) },
                )
            }
            if (channelEntries.isNotEmpty()) {
                result += HomeRowData(
                    id = "manage",
                    title = "",
                    items = listOf(HomeItem.ManageChannels),
                )
            }
        }
        return result
    }

    fun manageChannels(): List<ChannelEntry> = channelEntries

    fun toggleChannel(pkg: String, key: String) {
        val current = channelApprovals.firstOrNull {
            it.packageName == pkg && it.channelKey == key
        }?.state
        channelRepo.setApproval(pkg, key, if (current == "approved") "denied" else "approved")
    }

    fun openManageChannels() {
        overlay = null
        page = "channels"
        homeReset = false
        val first = channelEntries.firstOrNull()?.let { "channels:${it.packageName}:${it.key}" }
        focus.request(first ?: "nav:home")
        persistSoon()
    }

    fun openProgramMenu(program: ChannelProgram) {
        programMenu = program
        overlayReturnTag = focus.current?.tag
        overlay = "menu"
        focus.request("menu:remove")
    }

    fun removeProgram(program: ChannelProgram) {
        programMenu = null
        overlay = null
        channelRepo.removeProgram(program.packageName, program.internalId)
        focus.request(program.tag)
    }

    fun launchProgram(program: ChannelProgram) {
        val raw = program.intentUri ?: return
        val intent = try {
            Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            return
        }
        val target = intent.component?.packageName ?: intent.`package`
        if (target != program.packageName) return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun appRowItems(): List<HomeItem> {
        return buildAppItems(cap = 10)
    }

    private fun visibleApps(): List<AppEntry> {
        val restricted = restrictedMode != "off"
        return apps.filter {
            it.tag !in hiddenApps && (!restricted || it.packageName in restrictedAllowed)
        }
    }

    fun appsGridItems(): List<AppsGridItem> {
        val valid = visibleApps()
        val foldersValid = if (restrictedMode != "off") emptyList() else folders.mapNotNull { folder ->
            val members = folder.apps.mapNotNull { tag -> valid.firstOrNull { it.tag == tag } }
            if (members.isEmpty()) null else folder to members
        }
        val memberTags = foldersValid.flatMap { it.second }.map { it.tag }.toSet()
        val inserted = HashSet<String>()
        val result = ArrayList<AppsGridItem>()
        for (app in valid) {
            val folderPair = foldersValid.firstOrNull { it.second.any { member -> member.tag == app.tag } }
            if (folderPair != null) {
                if (inserted.add(folderPair.first.id)) {
                    result += AppsGridItem.FolderTile(folderPair.first, folderPair.second)
                }
                continue
            }
            if (app.tag !in memberTags) result += AppsGridItem.App(app)
        }
        return result
    }

    fun folderApps(folderId: String): Pair<Folder, List<AppEntry>>? {
        val folder = folders.firstOrNull { it.id == folderId } ?: return null
        val apps = folder.apps.mapNotNull { tag -> apps.firstOrNull { it.tag == tag && it.tag !in hiddenApps } }
        if (apps.isEmpty()) return null
        return folder to apps
    }

    fun hiddenAppEntries(): List<AppEntry> =
        apps.filter { it.tag in hiddenApps }

    fun currentRows(): List<RowSnapshot> {
        if (page != "home") return emptyList()
        return homeRows().map { RowSnapshot(it.id, it.items.map { item -> item.tag }) }
    }

    fun snapshot(): LauncherSnapshot {
        val entry: FocusEntry? = focus.current
        val heroItem = if (page == "home") hero else null
        return LauncherSnapshot(
            page = page,
            mode = idleMode ?: if (entry?.tag?.startsWith("hero:") == true) "hero" else "browse",
            focus = entry?.let {
                FocusSnapshot(
                    tag = it.tag,
                    rowId = it.rowId,
                    index = it.index.takeIf { _ -> it.rowId != null },
                )
            },
            heroVisible = heroItem != null && restrictedMode != "kids" && restrictedMode != "elders",
            heroIndex = if (heroItem != null) 0 else null,
            heroKey = heroItem?.key,
            rows = currentRows(),
            approvals = channelApprovals,
            watchlist = watchlistEntries.map { it.watchlistKey },
            hiddenApps = hiddenApps.sorted(),
            folders = folders.map { FolderSnapshot(it.id, it.name, it.apps) },
            clockIs24h = clockIs24h,
            dateText = dateText,
            lunarText = lunarText,
            weatherVisible = weatherVisible,
            weatherTempC = if (weatherVisible) weatherTempC else null,
            weatherCode = if (weatherVisible) weatherCode else null,
            weatherCity = if (weatherVisible) weatherCity else null,
            restrictedMode = restrictedMode,
            restrictedRemaining = restrictedRemaining,
            restrictedHasPin = restrictedHasPin,
            restrictedPinLockedUntil = restrictedPinLockedUntil,
            restrictedUnlockedUntil = restrictedUnlockedUntil,
            organizerToken = organizerState.token + "|" + organizerState.transfers.size + "|" +
                (organizerState.pendingConnection?.id ?: "") + "|" + organizerState.sessions.size,
            widgetsRevision = widgetsRepository.bindings.value.size * 1000 +
                widgetsRepository.bindings.value.sumOf { it.appWidgetId },
            overlay = overlay ?: if (page == "widgets") widgetsUiState.overlayWire else null,
            theme = ThemeSnapshot(
                seed = theme.seed,
                sourceKey = theme.sourceKey,
                style = theme.styleName,
                contrast = theme.contrast,
                transitioning = theme.transitioning,
                roles = theme.roleColors,
            ),
            glassTier = glassTier.name,
            blurRegions = blurRegionCount(glassTier, overlay == "quick_settings"),
            deviceTier = when (deviceTier) {
                DeviceTier.Low -> "low"
                DeviceTier.Mid -> "mid"
                DeviceTier.High -> "high"
            },
            frozenBlurBytes = frozenBackdrop?.bytes ?: 0L,
        )
    }

    private fun buildAppItems(cap: Int): List<HomeItem> {
        val valid = visibleApps()
        val foldersValid = if (restrictedMode != "off") emptyList() else folders.mapNotNull { folder ->
            val members = folder.apps.mapNotNull { tag -> valid.firstOrNull { it.tag == tag } }
            if (members.isEmpty()) null else folder to members
        }
        val memberTags = foldersValid.flatMap { it.second }.map { it.tag }.toSet()
        val inserted = HashSet<String>()
        val result = ArrayList<HomeItem>()
        for (app in valid) {
            if (result.size >= cap) break
            val folderPair = foldersValid.firstOrNull { it.second.any { member -> member.tag == app.tag } }
            if (folderPair != null) {
                if (inserted.add(folderPair.first.id)) {
                    result += HomeItem.FolderTile(folderPair.first, folderPair.second)
                }
                continue
            }
            if (app.tag !in memberTags) result += HomeItem.App(app)
        }
        return result
    }

    private fun dataReadyForPage(): Boolean = when (page) {
        "home" -> appsLoaded && channelsLoaded
        "kids_lock" -> true
        "apps" -> appsLoaded && apps.isNotEmpty()
        "settings" -> appsLoaded
        "channels" -> true
        else -> true
    }

    private fun firstFolderAppTag(): String? {
        val folderId = folderOpenId ?: return null
        return folderApps(folderId)?.second?.firstOrNull()?.tag
    }

    private fun defaultHomeFocus(): String? {
        if (restrictedMode == "elders") return appRowItems().firstOrNull()?.tag
        if (hero != null) return "hero:play"
        return appRowItems().firstOrNull()?.tag
    }

    private fun defaultFocusForPage(): String? = when (page) {
        "home" -> defaultHomeFocus()
        "apps" -> appsGridItems().firstOrNull()?.tag ?: "nav:apps"
        "widgets" -> "widget:builtin:clock"
        "kids_lock" -> "kids_lock:extend15"
        "settings" -> if (settingsHiddenExpanded) {
            hiddenAppEntries().firstOrNull()?.let { "hidden:${it.tag}" } ?: "settings:hidden_apps"
        } else {
            "settings:hidden_apps"
        }
        else -> "nav:home"
    }

    private fun isAvailable(tag: String): Boolean {
        if (tag.startsWith("nav:")) return true
        return when (page) {
            "home" -> when {
                tag == "hero:play" || tag == "hero:watchlist" || tag == "hero:info" -> hero != null
                tag.startsWith("wn:") -> continueItems.any { it.key == tag }
                tag.startsWith("wl:") -> watchlistEntries.any { it.watchlistKey == tag }
                tag.startsWith("folder:") -> folders.any { "folder:${it.id}" == tag }
                tag.startsWith("app:") -> appRowItems().any { it.tag == tag }
                tag.startsWith("prog:") -> channelPrograms.values.any { list -> list.any { it.tag == tag } }
                tag == "manage_channels" -> channelEntries.isNotEmpty()
                else -> false
            }
            "channels" -> tag.startsWith("channels:") || tag == "nav:home"
            "apps" -> when {
                tag.startsWith("app:") -> appsGridItems().any { it.tag == tag }
                tag.startsWith("folder:") -> appsGridItems().any { it.tag == tag }
                else -> false
            }
            "widgets" -> tag == "widget:builtin:clock" || tag == "widget:add"
            "kids_lock" -> tag == "kids_lock:extend15"
            "settings" -> when {
                tag == "settings" || tag == "settings:hidden_apps" -> true
                tag.startsWith("hidden:") -> settingsHiddenExpanded &&
                    hiddenAppEntries().any { "hidden:${it.tag}" == tag }
                else -> false
            }
            else -> false
        }
    }

    private suspend fun clockLoop() {
        while (true) {
            updateClock()
            val wait = 60_000L - (System.currentTimeMillis() % 60_000L) + 200L
            delay(wait.coerceAtLeast(1_000L))
        }
    }

    private fun updateClock() {
        val value = try {
            Settings.System.getString(appContext.contentResolver, Settings.System.TIME_12_24)
        } catch (_: Exception) {
            null
        }
        val is24 = is24h(value)
        val pattern = if (is24) "HH:mm" else "a hh:mm"
        val now = Date()
        val text = try {
            SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).format(now)
        } catch (_: Exception) {
            DateFormat.format(pattern, System.currentTimeMillis()).toString()
        }
        clockIs24h = is24
        if (clockText != text) clockText = text
        val datePattern = DateFormat.getBestDateTimePattern(Locale.SIMPLIFIED_CHINESE, "MMMd")
        val datePart = SimpleDateFormat(datePattern, Locale.SIMPLIFIED_CHINESE).format(now)
        val weekPart = SimpleDateFormat("EEE", Locale.SIMPLIFIED_CHINESE).format(now)
        val nextDate = "$datePart $weekPart"
        if (dateText != nextDate) dateText = nextDate
        val lunar = LunarCalendar.lunarFor(now)
        val nextLunar = if (lunar != null) LunarText.format(appContext, lunar) else ""
        if (lunarText != nextLunar) lunarText = nextLunar
        if (weatherUpdatedAt > 0L) {
            weatherVisible = weatherTempC != null &&
                System.currentTimeMillis() - weatherUpdatedAt < WEATHER_VISIBLE_MS
        }
    }
}
