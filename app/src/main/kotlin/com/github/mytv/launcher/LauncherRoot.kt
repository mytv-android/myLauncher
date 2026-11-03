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
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.feature.apps.AppsPage
import com.github.mytv.feature.home.FolderOpenOverlay
import com.github.mytv.feature.home.FolderPickOverlay
import com.github.mytv.feature.home.HomeScreen
import com.github.mytv.feature.home.MenuAction
import com.github.mytv.feature.home.MenuOverlay
import com.github.mytv.feature.home.PickerOverlay
import com.github.mytv.feature.home.WidgetsPage
import com.github.mytv.feature.settings.QuickSettingsOverlay
import com.github.mytv.feature.settings.SettingsPage
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
        model.apps,
        model.continueItems,
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
        if (model.initialFocusApplied && model.userActionVersion > 0 && focus.pendingTag == null &&
            focus.current?.tag == tag && model.overlay == null && model.page != target
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
            model.hero != null -> "hero:play"
            else -> model.appRowItems().firstOrNull()?.tag
        }
        "nav:apps" -> model.appsGridItems().firstOrNull()?.tag
        "nav:widgets" -> "widget:builtin:clock"
        else -> null
    }

    fun move(direction: FocusDirection) {
        model.userInteracted()
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
        if (model.page == "home" && tag != null && tag.startsWith("hero:")) {
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
                    val target = if (model.hero != null) "hero:play" else "nav:home"
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

    CompositionLocalProvider(LocalTvFocus provides focus) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0C10))
            .semantics { testTagsAsResourceId = true }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
        if (model.uiReady) {
        when (page) {
            "home" -> HomeScreen(
                enabled = overlay == null,
                hero = model.hero,
                heroInWatchlist = model.heroInWatchlist,
                rows = model.homeRows(),
                onPlayHero = model::playHero,
                onToggleWatchlist = model::toggleHeroWatchlist,
                onHeroDetails = model::heroDetails,
                onNextClick = model::launchWatchNext,
                onAppClick = model::launchApp,
                onAppLongClick = { app, _ -> model.openMenu(app) },
                onFolderClick = model::openFolder,
                onFolderLongClick = { },
            )
            "apps" -> AppsPage(
                items = model.appsGridItems(),
                enabled = overlay == null,
                onAppClick = model::launchApp,
                onAppLongClick = { model.openMenu(it) },
                onFolderClick = model::openFolder,
            )
            "widgets" -> WidgetsPage(
                enabled = overlay == null,
                clockText = model.clockText,
                onAdd = model::openPicker,
            )
            "settings" -> SettingsPage(
                hiddenApps = model.hiddenAppEntries(),
                expanded = model.settingsHiddenExpanded,
                enabled = overlay == null,
                onToggleHidden = model::toggleHiddenList,
                onRestore = model::restoreHidden,
            )
        }

        TopBar(
            page = page,
            overlayOpen = overlay != null,
            clockText = model.clockText,
            onTab = model::switchPage,
            onSettings = model::openQuickSettings,
        )

        if (overlay == "quick_settings") {
            QuickSettingsOverlay(
                onLauncherSettings = model::openSettings,
                onDismiss = model::closeOverlay,
            )
        }

        if (overlay == "menu") {
            val request = model.menuRequest
            val actions = if (request == null) {
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

        if (overlay == "picker") {
            PickerOverlay(
                onPick = model::pickBuiltin,
                onCancel = model::closeOverlay,
            )
        }
        }
    }
    }
}
