package com.github.mytv.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.mytv.core.tvfocus.FocusEntry
import com.github.mytv.core.tvfocus.TvFocusState
import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.AppPrefsRepository
import com.github.mytv.data.apps.AppsRepository
import com.github.mytv.data.apps.Folder
import com.github.mytv.data.channels.WatchNextEntry
import com.github.mytv.data.channels.WatchNextRepository
import com.github.mytv.data.channels.WatchlistRepository
import com.github.mytv.feature.apps.AppsGridItem
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

data class LauncherSnapshot(
    val page: String,
    val overlay: String?,
    val mode: String,
    val focus: FocusSnapshot?,
    val heroVisible: Boolean,
    val heroIndex: Int?,
    val heroKey: String?,
    val rows: List<RowSnapshot>,
    val watchlist: List<String>,
    val hiddenApps: List<String>,
    val folders: List<FolderSnapshot>,
    val clockIs24h: Boolean,
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appsRepo = AppsRepository(appContext)
    private val channelsRepo = WatchNextRepository(appContext)
    private val watchlistRepo = WatchlistRepository(appContext)
    private val prefsRepo = AppPrefsRepository(appContext)

    val focus = TvFocusState()

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
    private var usedSavedFocus = false
    private var homeFocusPending = true
    private var overlayReturnTag: String? = null
    private var lastStableTag: String? = null
    private var persistJob: Job? = null

    val hero: WatchNextEntry?
        get() = continueItems.firstOrNull()

    val heroInWatchlist: Boolean
        get() {
            val item = hero ?: return false
            return watchlistEntries.any { it.watchlistKey == item.watchlistKey }
        }

    init {
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
            val (persistedPage, tag) = prefsRepo.readPageFocus()
            if (persistedPage != null && persistedPage in setOf("home", "apps", "widgets", "settings")) {
                page = persistedPage
            }
            restoredPage = page
            savedFocus = tag
            restoreFocusPending = tag != null
            restored = true
            uiReady = true
        }
        scope.launch { clockLoop() }
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
        initialFocusApplied = true
        restoreFocusPending = false
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
        restoreFocusPending = false
        overlay = null
        folderOpenId = null
        menuRequest = null
        folderPickStage = "folders"
        settingsHiddenExpanded = false
        page = "home"
        homeFocusPending = true
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
        if (page == "settings" || page == "apps" || page == "widgets") {
            page = "home"
            focus.request("nav:home")
            persistSoon()
            return
        }
        val entry = focus.current
        when {
            entry == null -> focus.request("nav:home")
            entry.tag.startsWith("hero:") -> focus.request("nav:home")
            entry.rowId != null -> focus.request(if (hero != null) "hero:play" else "nav:home")
            entry.tag.startsWith("nav:") -> Unit
            else -> focus.request("nav:home")
        }
    }

    fun openQuickSettings() {
        overlayReturnTag = focus.current?.tag
        overlay = "quick_settings"
        focus.request("qs:launcher_settings")
    }

    fun openSettings() {
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
        appsRepo.launch(app)
    }

    fun homeRows(): List<HomeRowData> {
        val result = ArrayList<HomeRowData>()
        if (continueItems.isNotEmpty()) {
            result += HomeRowData(
                id = "continue",
                title = "",
                items = continueItems.map { HomeItem.Next(it) },
            )
        }
        if (watchlistEntries.isNotEmpty()) {
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
        return result
    }

    fun appRowItems(): List<HomeItem> {
        return buildAppItems(cap = 10)
    }

    fun appsGridItems(): List<AppsGridItem> {
        val valid = apps.filter { it.tag !in hiddenApps }
        val foldersValid = folders.mapNotNull { folder ->
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
            overlay = overlay,
            mode = if (entry?.tag?.startsWith("hero:") == true) "hero" else "browse",
            focus = entry?.let {
                FocusSnapshot(
                    tag = it.tag,
                    rowId = it.rowId,
                    index = it.index.takeIf { _ -> it.rowId != null },
                )
            },
            heroVisible = heroItem != null,
            heroIndex = if (heroItem != null) 0 else null,
            heroKey = heroItem?.key,
            rows = currentRows(),
            watchlist = watchlistEntries.map { it.watchlistKey },
            hiddenApps = hiddenApps.sorted(),
            folders = folders.map { FolderSnapshot(it.id, it.name, it.apps) },
            clockIs24h = clockIs24h,
        )
    }

    private fun buildAppItems(cap: Int): List<HomeItem> {
        val valid = apps.filter { it.tag !in hiddenApps }
        val foldersValid = folders.mapNotNull { folder ->
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
        "apps" -> appsLoaded && apps.isNotEmpty()
        "settings" -> appsLoaded
        else -> true
    }

    private fun firstFolderAppTag(): String? {
        val folderId = folderOpenId ?: return null
        return folderApps(folderId)?.second?.firstOrNull()?.tag
    }

    private fun defaultHomeFocus(): String? {
        if (hero != null) return "hero:play"
        return appRowItems().firstOrNull()?.tag
    }

    private fun defaultFocusForPage(): String? = when (page) {
        "home" -> defaultHomeFocus()
        "apps" -> appsGridItems().firstOrNull()?.tag ?: "nav:apps"
        "widgets" -> "widget:builtin:clock"
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
                else -> false
            }
            "apps" -> when {
                tag.startsWith("app:") -> appsGridItems().any { it.tag == tag }
                tag.startsWith("folder:") -> appsGridItems().any { it.tag == tag }
                else -> false
            }
            "widgets" -> tag == "widget:builtin:clock" || tag == "widget:add"
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
        val is24 = value != "12"
        val pattern = if (is24) "HH:mm" else "a hh:mm"
        val text = try {
            SimpleDateFormat(pattern, Locale.SIMPLIFIED_CHINESE).format(Date())
        } catch (_: Exception) {
            DateFormat.format(pattern, System.currentTimeMillis()).toString()
        }
        clockIs24h = is24
        if (clockText != text) clockText = text
    }
}
