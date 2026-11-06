package com.github.mytv.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.SoundEffectConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.github.mytv.core.ambient.AmbientBackdrop
import com.github.mytv.core.designsystem.LocalRowRecompositionObserver
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.feature.apps.AppsPage
import com.github.mytv.feature.home.FolderOpenOverlay
import com.github.mytv.feature.home.FolderPickOverlay
import com.github.mytv.feature.home.HomeScreen
import com.github.mytv.feature.home.MenuAction
import com.github.mytv.feature.home.MenuOverlay
import com.github.mytv.feature.home.PickerOverlay
import com.github.mytv.feature.organizer.ConfirmPhoneOverlay
import com.github.mytv.feature.organizer.PhoneOrganizerScreen
import com.github.mytv.feature.quicksettings.QuickSettingsOverlay
import com.github.mytv.feature.quicksettings.QuickSettingsValues
import com.github.mytv.feature.quicksettings.quickSettingsRows
import com.github.mytv.feature.settings.SettingsPage
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun LauncherRoot(model: LauncherModel) {
    val focus = model.focus
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val audio = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val page = model.page
    val overlay = model.overlay
    val tier = model.glassTier
    val hazeState = rememberHazeState()
    val widgetsHost = com.github.mytv.feature.widgets.rememberWidgetsHost(model.widgetsRepository)
    val hazeInput = remember(hazeState, tier) {
        if (tier == GlassTier.T1) {
            HazeInput.Sources(
                hazeState,
                HazeSourceSelection.Behind.where { it.key == "backdrop" },
            )
        } else {
            null
        }
    }

    SideEffect {
        focus.onUserAction = { model.userInteracted() }
        focus.onKeySound = { kind ->
            if (kind == "click") {
                audio?.playSoundEffect(AudioManager.FX_KEY_CLICK)
                StateHub.incrementSound("click")
            }
        }
    }
    BackHandler {
        if (Build.VERSION.SDK_INT >= 33) {
            audio?.playSoundEffect(AudioManager.FX_BACK)
            StateHub.incrementSound("back")
        }
        model.userInteracted()
        model.onBack()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { model.snapshot() }.collect { StateHub.update(it) }
    }
    LaunchedEffect(
        model.restored,
        model.appsLoaded,
        model.channelsLoaded,
        model.channelRowsLoaded,
        model.apps,
        model.continueItems,
        model.heroItems.isEmpty(),
        model.folders,
        model.watchlistEntries,
        model.page,
    ) {
        if (model.restored) {
            model.ensureFocus()
            delay(80)
            model.ensureFocus()
        }
    }
    LaunchedEffect(focus.current?.tag, model.restored, model.page, model.overlay) {
        if (model.restored && model.overlay == null) {
            if (focus.current == null) delay(50)
            model.ensureFocus()
        }
    }
    LaunchedEffect(focus.current?.tag) {
        model.noteFocus(focus.current?.tag)
        model.onFocusSettled(focus.current?.tag)
    }
    LaunchedEffect(model.homeRequestVersion) {
        if (model.homeRequestVersion > 0) focus.clearRowMemory()
    }
    LaunchedEffect(focus.current?.tag, model.overlay) {
        val tag = focus.current?.tag ?: return@LaunchedEffect
        val target = when (tag) {
            "nav:home" -> "home"
            "nav:apps" -> "apps"
            "nav:widgets" -> "widgets"
            else -> return@LaunchedEffect
        }
        delay(320)
        val suppressed = model.tabSwitchSuppressedUntil - System.currentTimeMillis()
        if (suppressed > 0) delay(suppressed + 30)
        if (model.initialFocusApplied && model.userActionVersion > 0 && focus.pendingTag == null &&
            focus.current?.tag == tag && model.overlay == null && model.page != target &&
            System.currentTimeMillis() >= model.tabSwitchSuppressedUntil
        ) {
            model.switchPage(target)
        }
    }

    fun playNav(direction: FocusDirection) {
        val effect = when (direction) {
            FocusDirection.Up -> SoundEffectConstants.NAVIGATION_UP
            FocusDirection.Down -> SoundEffectConstants.NAVIGATION_DOWN
            FocusDirection.Left -> SoundEffectConstants.NAVIGATION_LEFT
            FocusDirection.Right -> SoundEffectConstants.NAVIGATION_RIGHT
            else -> SoundEffectConstants.NAVIGATION_DOWN
        }
        audio?.playSoundEffect(effect)
        StateHub.incrementSound("nav")
    }

    fun playInvalid() {
        audio?.playSoundEffect(AudioManager.FX_KEYPRESS_INVALID)
        StateHub.incrementSound("invalid")
    }

    fun pageFirstFocus(tag: String): String? = when (tag) {
        "nav:home" -> when {
            // 长辈模式不显示 Hero，首页首焦点是第一个大磁贴
            model.restrictedMode == "elders" -> model.appRowItems().firstOrNull()?.tag
            model.hero != null -> "hero:play"
            else -> model.appRowItems().firstOrNull()?.tag
        }
        "nav:apps" -> model.appsGridItems().firstOrNull()?.tag
        "nav:widgets" -> "widget:builtin:clock"
        else -> null
    }

    fun move(direction: FocusDirection) {
        model.userInteracted()
        if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
            val key = when (focus.current?.tag) {
                "qs:source" -> "color_source"
                "qs:glass" -> "glass_tier"
                "qs:style" -> "scheme_style"
                else -> null
            }
            if (key != null) {
                model.adjustSetting(key, if (direction == FocusDirection.Right) 1 else -1)
                playNav(direction)
                return
            }
            if (focus.current?.tag == "qs:volume") {
                model.adjustVolume(if (direction == FocusDirection.Right) 1 else -1)
                playNav(direction)
                return
            }
        }
        if (model.overlay == "quick_settings") {
            // 面板是一张网格（feature:quicksettings 的 quickSettingsRows）：上下换行并保持列，左右在行内移动
            val rows = quickSettingsRows(model.quickSettingsValues())
            val tag = focus.current?.tag
            val r = rows.indexOfFirst { tag in it }
            if (r >= 0) {
                val c = rows[r].indexOf(tag)
                val target = when (direction) {
                    FocusDirection.Up -> rows.getOrNull(r - 1)?.let { it[minOf(c, it.lastIndex)] }
                    FocusDirection.Down -> rows.getOrNull(r + 1)?.let { it[minOf(c, it.lastIndex)] }
                    FocusDirection.Left -> rows[r].getOrNull(c - 1)
                    FocusDirection.Right -> rows[r].getOrNull(c + 1)
                    else -> null
                }
                if (target != null) {
                    focus.request(target)
                    playNav(direction)
                } else {
                    playInvalid()
                }
                return
            }
        }
        if (model.overlay == "confirm_phone" && focus.current?.rowId == "confirm_phone" &&
            (direction == FocusDirection.Up || direction == FocusDirection.Down)
        ) {
            playInvalid()
            return
        }
        val entry = focus.current
        val rowId = entry?.rowId
        if (entry != null && rowId != null && (direction == FocusDirection.Left || direction == FocusDirection.Right)) {
            val delta = if (direction == FocusDirection.Right) 1 else -1
            val target = focus.tagAt(rowId, entry.index + delta)
            if (target != null) {
                focus.request(target)
                playNav(direction)
            } else {
                playInvalid()
            }
            return
        }
        val tag = focus.current?.tag
        if (tag != null && tag.startsWith("nav:")) {
            if (direction == FocusDirection.Up) {
                playInvalid()
                return
            }
            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                val chain = listOf("nav:home", "nav:apps", "nav:widgets", "nav:search", "nav:ambient", "nav:settings")
                val index = chain.indexOf(tag)
                if (index >= 0) {
                    val next = index + if (direction == FocusDirection.Right) 1 else -1
                    if (next in chain.indices) {
                        focus.request(chain[next])
                        playNav(direction)
                    } else {
                        playInvalid()
                    }
                    return
                }
            }
        }
        if (model.page == "home" && tag != null && tag.startsWith("hero:")) {
            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                // 按钮之间移动；到最左或最右的按钮后再按，切换上一个或下一个精选，两端不循环（§09）。
                // 切换后焦点回到主按钮（原型 heroRender 之后 focus(heroBtns[0])）。
                val buttons = listOf("hero:play", "hero:watchlist")
                val delta = if (direction == FocusDirection.Right) 1 else -1
                val next = buttons.indexOf(tag) + delta
                if (next in buttons.indices) {
                    focus.request(buttons[next])
                    playNav(direction)
                } else if (model.stepHero(delta)) {
                    if (tag != "hero:play") focus.request("hero:play")
                    playNav(direction)
                } else {
                    playInvalid()
                }
                return
            }
            if (direction == FocusDirection.Down) {
                val row = model.homeRows().firstOrNull()
                val target = row?.let { focus.tagForRow(it.id)?.takeIf { candidate -> focus.isRegistered(candidate) } ?: it.items.firstOrNull()?.tag }
                if (target != null) {
                    focus.request(target)
                    playNav(direction)
                } else {
                    playInvalid()
                }
                return
            }
            if (direction == FocusDirection.Up) {
                focus.request("nav:home")
                playNav(direction)
                return
            }
        }
        if (model.page == "home" && rowId != null && (direction == FocusDirection.Up || direction == FocusDirection.Down)) {
            val rows = model.homeRows()
            val index = rows.indexOfFirst { it.id == rowId }
            if (index >= 0) {
                val nextIndex = index + if (direction == FocusDirection.Down) 1 else -1
                if (nextIndex in rows.indices) {
                    val next = rows[nextIndex]
                    val target = focus.tagForRow(next.id)?.takeIf { focus.isRegistered(it) }
                        ?: next.items.firstOrNull()?.tag
                    if (target != null) {
                        focus.request(target)
                        playNav(direction)
                    } else {
                        playInvalid()
                    }
                    return
                }
                if (direction == FocusDirection.Up) {
                    val target = if (model.hero != null && model.restrictedMode != "elders") "hero:play" else "nav:home"
                    focus.request(target)
                    playNav(direction)
                    return
                }
                playInvalid()
                return
            }
        }
        if (direction == FocusDirection.Down && tag != null) {
            val targetPage = when (tag) {
                "nav:home" -> "home"
                "nav:apps" -> "apps"
                "nav:widgets" -> "widgets"
                else -> null
            }
            if (targetPage == model.page) {
                val target = pageFirstFocus(tag)
                if (target != null && target != tag) {
                    focus.request(target)
                    playNav(direction)
                    return
                }
            }
        }
        val beforeRow = focus.current?.rowId
        val remembered = focus.snapshotLastByRow()
        val moved = focusManager.moveFocus(direction)
        if (moved) {
            playNav(direction)
            scope.launch {
                withFrameNanos { }
                val now = focus.current
                if (now?.rowId != null && now.rowId != beforeRow) {
                    val want = remembered[now.rowId]
                    if (want != null && want != now.tag && focus.isRegistered(want)) {
                        focus.request(want)
                    }
                }
            }
        } else {
            playInvalid()
        }
    }

    CompositionLocalProvider(
        LocalTvFocus provides focus,
        LocalRowRecompositionObserver provides { StateHub.incrementRowRecomposition() },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0C10))
            .semantics { testTagsAsResourceId = true }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (model.page == "widgets" &&
                    (model.widgetsUiState.innerFocusId != null || model.widgetsUiState.edit != null) &&
                    event.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
                ) {
                    return@onPreviewKeyEvent false
                }
                if (model.isIdleMode()) {
                    val restore = focus.current?.tag
                    model.exitIdle()
                    if (restore != null) focus.request(restore)
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp -> {
                        move(FocusDirection.Up)
                        true
                    }
                    Key.DirectionDown -> {
                        move(FocusDirection.Down)
                        true
                    }
                    Key.DirectionLeft -> {
                        move(FocusDirection.Left)
                        true
                    }
                    Key.DirectionRight -> {
                        move(FocusDirection.Right)
                        true
                    }
                    Key.MoveHome -> {
                        model.onHomeRequested()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AmbientBackdrop(
            source = model.backdropSource,
            // Hero 展开（主页、有精选、焦点不在内容行）用较轻的遮罩；其余状态叠加浏览态遮罩
            browse = !(page == "home" && model.heroItems.isNotEmpty() && focus.current?.rowId == null) &&
                model.idleMode == null,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (tier == GlassTier.T1) {
                        Modifier.hazeSource(hazeState, 0f, "backdrop")
                    } else {
                        Modifier
                    },
                ),
        )
        if (model.uiReady) {
        when (page) {
            "home" -> if (model.restrictedMode == "elders") EldersHome(
                items = model.appRowItems(),
                enabled = overlay == null,
                onAppClick = model::launchApp,
            ) else HomeScreen(
                enabled = overlay == null,
                heroItems = model.heroItems,
                heroIndex = model.heroIndex.coerceIn(0, (model.heroItems.size - 1).coerceAtLeast(0)),
                heroInWatchlist = model.heroInWatchlist,
                appOf = model::appOf,
                rows = model.homeRows(),
                onPlayHero = model::playHero,
                onToggleWatchlist = model::toggleHeroWatchlist,
                onNextClick = model::launchWatchNext,
                onAppClick = model::launchApp,
                onAppLongClick = { app, _ -> model.openMenu(app) },
                onFolderClick = model::openFolder,
                onFolderLongClick = { },
                onProgramClick = model::launchProgram,
                onProgramLongClick = model::openProgramMenu,
                onManageChannels = model::openManageChannels,
                forceHero = model.homeReset,
                widgetRow = { modifier ->
                    com.github.mytv.feature.widgets.HomeWidgetGlanceRow(
                        repository = model.widgetsRepository,
                        modifier = modifier,
                    )
                },
            )
            "apps" -> AppsPage(
                items = model.appsGridItems(),
                enabled = overlay == null,
                onAppClick = model::launchApp,
                onAppLongClick = { model.openMenu(it) },
                onFolderClick = model::openFolder,
            )
            "widgets" -> com.github.mytv.feature.widgets.WidgetsScreen(
                repository = model.widgetsRepository,
                enabled = overlay == null,
                host = widgetsHost,
                weatherText = if (model.weatherVisible && model.weatherTempC != null) {
                    // 「多云 19°」：天气状况与气温（原型 .wdg-sub）
                    val look = model.weatherCode?.let { com.github.mytv.data.weather.lookOf(it) }
                    val label = look?.let { stringResource(it.label) }
                    listOfNotNull(label, "${model.weatherTempC}°").joinToString(" ")
                } else {
                    null
                },
                onUiState = model::onWidgetsUiState,
                onBuiltinClock = model::startAmbient,
            )
            "organizer" -> PhoneOrganizerScreen(
                state = model.organizerState,
                onDisconnectSession = model::disconnectOrganizerSession,
                onCancelTransfer = model::cancelOrganizerTransfer,
                onRemoveTransfer = model::removeOrganizerTransfer,
                onDeleteTransfer = model::deleteOrganizerTransfer,
                onOpenTransfer = model::openOrganizerTransfer,
                onSetTarget = model::setOrganizerTarget,
                onStorageSettings = model::openOrganizerStorageSettings,
            )
            "kids_lock" -> KidsLockScreen(
                enabled = overlay == null,
                onExtend = model::openParentPin,
            )
            "channels" -> com.github.mytv.feature.home.ChannelsPage(
                channels = model.manageChannels(),
                approvals = model.channelApprovals,
                enabled = overlay == null,
                onToggle = model::toggleChannel,
            )
            "settings" -> SettingsPage(
                hiddenApps = model.hiddenAppEntries(),
                expanded = model.settingsHiddenExpanded,
                highContrast = model.highContrastFocus,
                enabled = overlay == null,
                onToggleHidden = model::toggleHiddenList,
                onToggleHighContrast = { model.setHighContrast(!model.highContrastFocus) },
                onRestore = model::restoreHidden,
                onOrganizer = model::openOrganizer,
            )
        }

        TopBar(
            page = page,
            overlayOpen = overlay != null ||
                (page == "widgets" && model.widgetsUiState.overlayWire != null),
            clockText = model.clockText,
            tier = tier,
            hazeInput = hazeInput,
            onTab = model::switchPage,
            onSettings = model::openQuickSettings,
            onSearch = { model.openSearch() },
            onAmbient = model::startAmbient,
            weatherVisible = model.weatherVisible,
            weatherTempC = model.weatherTempC,
            weatherCode = model.weatherCode,
            weatherIsDay = model.weatherIsDay,
            backdrop = model.backdropSource,
            highContrast = model.highContrastFocus,
        )

        if (page == "widgets") {
            // 小组件选择器等浮层画在顶栏之上（原型 .tv-pk 是 top 14 的右侧面板）
            com.github.mytv.feature.widgets.WidgetsOverlayLayer(
                repository = model.widgetsRepository,
                host = widgetsHost,
                tier = tier,
                colors = model.theme.colors.value,
                input = hazeInput,
                clockText = model.clockText,
            )
        }

        if (overlay == "quick_settings") {
            QuickSettingsOverlay(
                values = model.quickSettingsValues(),
                tier = tier,
                colors = model.theme.colors.value,
                input = hazeInput,
                onAdjust = model::adjustSetting,
                onLauncherSettings = model::openSettings,
                onDismiss = model::closeOverlay,
                onAction = { action ->
                    when (action) {
                        "wifi" -> model.openWifiSettings()
                        "bt" -> model.openBluetoothSettings()
                        "dnd" -> model.toggleDnd()
                        "inputs" -> model.openInputs()
                        "saver" -> model.startSaver()
                        "ambient" -> model.startAmbient()
                        "parent" -> model.openParentPin()
                        "transfer" -> model.openTransfer()
                        "mute" -> model.toggleMute()
                        else -> Unit
                    }
                },
            )
        }

        if (overlay == "menu") {
            val request = model.menuRequest
            val program = model.programMenu
            val actions = if (program != null) {
                listOf(MenuAction("menu:remove", stringResource(DsR.string.menu_remove)))
            } else if (request == null) {
                emptyList()
            } else if (request.folderId == null) {
                listOf(
                    MenuAction("menu:move", stringResource(DsR.string.menu_move)),
                    MenuAction("menu:folder", stringResource(DsR.string.menu_folder)),
                    MenuAction("menu:hide", stringResource(DsR.string.menu_hide)),
                    MenuAction("menu:uninstall", stringResource(DsR.string.menu_uninstall)),
                )
            } else {
                listOf(
                    MenuAction("menu:unfolder", stringResource(DsR.string.menu_unfolder)),
                    MenuAction("menu:hide", stringResource(DsR.string.menu_hide)),
                    MenuAction("menu:uninstall", stringResource(DsR.string.menu_uninstall)),
                )
            }
            MenuOverlay(
                actions = actions,
                onAction = model::onMenuAction,
                onDismiss = model::closeOverlay,
            )
        }

        if (overlay == "folder_pick") {
            FolderPickOverlay(
                folders = model.folders,
                stage = model.folderPickStage,
                onPickExisting = model::pickExistingFolder,
                onNew = model::showFolderNames,
                onPickName = { _, name -> model.chooseFolderName(name) },
                onCancel = model::closeOverlay,
            )
        }

        if (overlay == "folder_open") {
            val pair = model.folderOpenId?.let { model.folderApps(it) }
            if (pair != null) {
                FolderOpenOverlay(
                    folder = pair.first,
                    apps = pair.second,
                    onAppClick = model::launchApp,
                    onAppLongClick = { model.openMenu(it, pair.first.id) },
                    onDismiss = model::closeOverlay,
                )
            } else {
                LaunchedEffect(Unit) { model.closeOverlay() }
            }
        }

        if (overlay == "pin") {
            PinOverlay(wrong = model.pinWrong, entered = model.pinInput.length)
        }

        if (overlay == "confirm_phone") {
            ConfirmPhoneOverlay(
                device = model.organizerState.pendingConnection?.device ?: "",
                onAllow = { model.decideOrganizerConnection(true) },
                onDeny = { model.decideOrganizerConnection(false) },
            )
        }

        if (overlay == "inputs") {
            InputsOverlay(
                inputs = model.tvInputs,
                enabled = true,
                onPick = model::launchInput,
                onDismiss = model::closeOverlay,
            )
        }

        if (overlay == "picker") {
            PickerOverlay(
                onPick = model::pickBuiltin,
                onCancel = model::closeOverlay,
            )
        }
        }
        if (model.idleMode != null) {
            AmbientMode(model)
        }
        }
    }
    }
