package com.github.mytv.feature.widgets

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import com.github.mytv.data.widgets.BuiltinWidgets
import com.github.mytv.data.widgets.WidgetBinding
import com.github.mytv.data.widgets.WidgetBindResult
import com.github.mytv.data.widgets.WidgetState
import com.github.mytv.data.widgets.WidgetsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 小组件页的状态机：选择器、授权页、配置页、菜单、移动/调整大小。
 * app 只需要创建 [WidgetsRepository]，把 [WidgetsScreen] 放到“小组件”页，并把 [ui] 映射到状态接口。
 */
class WidgetsHost(
    val repository: WidgetsRepository,
    private val scope: CoroutineScope,
) {

    private val _ui = MutableStateFlow(WidgetsUiState(bindings = repository.bindings.value))
    val ui: StateFlow<WidgetsUiState> = _ui.asStateFlow()

    val providers: StateFlow<List<AppWidgetProviderInfo>> = repository.installedProviders

    private val _configRequests = MutableSharedFlow<ConfigLaunch>(extraBufferCapacity = 1)
    val configRequests: SharedFlow<ConfigLaunch> = _configRequests

    private var pendingInfo: AppWidgetProviderInfo? = null
    private var lastBindings: List<WidgetBinding> = repository.bindings.value

    init {
        scope.launch {
            repository.bindings.collect { bindings ->
                onBindingsChanged(bindings)
            }
        }
        scope.launch {
            repository.colorApplyCounts.collect { counts ->
                _ui.update { it.copy(colorCounts = counts) }
            }
        }
    }

    // ---------------------------------------------------------------- 页面状态

    fun openPicker() {
        repository.refreshProviders()
        pendingInfo = null
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.PICKER,
                menuBindingId = null,
                edit = null,
                pending = null,
                pendingFocusTag = "picker:builtin:clock",
            )
        }
    }

    fun selectProvider(providerTag: String) {
        if (providerTag == BuiltinWidgets.CLOCK) {
            _ui.update {
                it.copy(overlay = WidgetOverlay.NONE, pendingFocusTag = BuiltinWidgets.CLOCK)
            }
            return
        }
        val info = repository.findProvider(providerTag) ?: return
        when (val result = repository.allocateAndBind(info)) {
            is WidgetBindResult.Bound -> {
                pendingInfo = info
                continueAfterBind(info, result.appWidgetId)
            }

            is WidgetBindResult.NeedsGrant -> {
                pendingInfo = info
                _ui.update {
                    it.copy(
                        overlay = WidgetOverlay.AUTH,
                        pending = PendingWidget(providerTag, result.appWidgetId, false),
                        pendingFocusTag = "picker:auth:adb",
                    )
                }
            }

            is WidgetBindResult.Failed -> {
                if (result.appWidgetId >= 0) repository.releaseAppWidgetId(result.appWidgetId)
            }
        }
    }

    private fun continueAfterBind(info: AppWidgetProviderInfo, appWidgetId: Int) {
        val configure = repository.configureIntent(info, appWidgetId)
        if (configure != null) {
            _ui.update {
                it.copy(
                    overlay = WidgetOverlay.PICKER,
                    pending = PendingWidget(repository.providerTag(info), appWidgetId, false),
                )
            }
            _configRequests.tryEmit(ConfigLaunch(configure))
        } else {
            commit(info, appWidgetId)
        }
    }

    private fun commit(info: AppWidgetProviderInfo, appWidgetId: Int) {
        val span = repository.defaultSpan(info)
        val (x, y) = repository.findFreePlacement(span.first, span.second)
        val binding = repository.commitBinding(
            info = info,
            appWidgetId = appWidgetId,
            page = 0,
            x = x,
            y = y,
            spanX = span.first,
            spanY = span.second,
        )
        pendingInfo = null
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.NONE,
                menuBindingId = null,
                edit = null,
                pending = null,
                pendingFocusTag = binding.tag,
            )
        }
    }

    fun onConfigResult(resultCode: Int) {
        val pending = _ui.value.pending ?: return
        val info = pendingInfo
        if (pending.reconfiguring) {
            if (resultCode == Activity.RESULT_OK && info != null) {
                repository.setState(pending.appWidgetId, WidgetState.OK)
                repository.updateOptions(pending.appWidgetId)
            }
            pendingInfo = null
            _ui.update {
                it.copy(
                    overlay = WidgetOverlay.NONE,
                    pending = null,
                    pendingFocusTag = "widget:${pending.appWidgetId}",
                )
            }
            return
        }
        if (resultCode == Activity.RESULT_OK && info != null) {
            commit(info, pending.appWidgetId)
        } else {
            repository.releaseAppWidgetId(pending.appWidgetId)
            pendingInfo = null
            _ui.update {
                it.copy(
                    overlay = WidgetOverlay.NONE,
                    pending = null,
                    pendingFocusTag = "widget:add",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 授权

    fun authShowAdb() {
        _ui.update {
            it.copy(overlay = WidgetOverlay.ADB, pendingFocusTag = "picker:adb:done")
        }
    }

    fun authBack() {
        _ui.update {
            it.copy(overlay = WidgetOverlay.AUTH, pendingFocusTag = "picker:auth:adb")
        }
    }

    fun authDone() {
        val pending = _ui.value.pending ?: return
        val info = pendingInfo ?: return
        if (repository.retryBind(pending.appWidgetId, info)) {
            continueAfterBind(info, pending.appWidgetId)
        } else {
            _ui.update { it.copy(pendingFocusTag = "picker:adb:done") }
        }
    }

    fun authCancel() {
        val pending = _ui.value.pending
        if (pending != null && !pending.reconfiguring) {
            repository.releaseAppWidgetId(pending.appWidgetId)
        }
        pendingInfo = null
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.NONE,
                pending = null,
                pendingFocusTag = "widget:add",
            )
        }
    }

    // ---------------------------------------------------------------- 浮层返回

    fun dismissOverlay() {
        val state = _ui.value
        val pending = state.pending
        if (pending != null && !pending.reconfiguring) {
            repository.releaseAppWidgetId(pending.appWidgetId)
        }
        pendingInfo = null
        val focus = when (state.overlay) {
            WidgetOverlay.MENU -> state.menuBindingId?.let { "widget:$it" }
            else -> "widget:add"
        }
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.NONE,
                menuBindingId = null,
                edit = null,
                pending = null,
                pendingFocusTag = focus,
            )
        }
    }

    fun onPageHidden() {
        val pending = _ui.value.pending
        if (pending != null && !pending.reconfiguring) {
            repository.releaseAppWidgetId(pending.appWidgetId)
        }
        pendingInfo = null
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.NONE,
                menuBindingId = null,
                edit = null,
                pending = null,
                pendingFocusTag = null,
            )
        }
    }

    // ---------------------------------------------------------------- 菜单

    fun openMenu(appWidgetId: Int) {
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.MENU,
                menuBindingId = appWidgetId,
                pendingFocusTag = "menu:move",
            )
        }
    }

    fun menuActions(appWidgetId: Int): List<WidgetMenuAction> {
        val binding = repository.findBinding(appWidgetId) ?: return emptyList()
        if (binding.state != WidgetState.OK) {
            return listOf(WidgetMenuAction("menu:remove", R.string.widget_remove))
        }
        val actions = ArrayList<WidgetMenuAction>()
        actions += WidgetMenuAction("menu:move", R.string.menu_move)
        actions += WidgetMenuAction("menu:resize", R.string.menu_resize)
        val info = repository.providerInfoFor(appWidgetId)
        if (info?.configure != null) {
            actions += WidgetMenuAction("menu:reconfigure", R.string.menu_reconfigure)
        }
        actions += if (binding.onHome) {
            WidgetMenuAction("menu:from_home", R.string.menu_from_home)
        } else {
            WidgetMenuAction("menu:to_home", R.string.menu_to_home)
        }
        actions += WidgetMenuAction("menu:remove", R.string.widget_remove)
        return actions
    }

    fun onMenuAction(tag: String) {
        val appWidgetId = _ui.value.menuBindingId ?: return
        val binding = repository.findBinding(appWidgetId) ?: return
        when (tag) {
            "menu:move" -> {
                _ui.update {
                    it.copy(
                        overlay = WidgetOverlay.NONE,
                        menuBindingId = null,
                        edit = WidgetEdit(
                            appWidgetId = appWidgetId,
                            moving = true,
                            x = binding.x,
                            y = binding.y,
                            spanX = binding.spanX,
                            spanY = binding.spanY,
                        ),
                        pendingFocusTag = binding.tag,
                    )
                }
            }

            "menu:resize" -> {
                _ui.update {
                    it.copy(
                        overlay = WidgetOverlay.NONE,
                        menuBindingId = null,
                        edit = WidgetEdit(
                            appWidgetId = appWidgetId,
                            moving = false,
                            x = binding.x,
                            y = binding.y,
                            spanX = binding.spanX,
                            spanY = binding.spanY,
                        ),
                        pendingFocusTag = binding.tag,
                    )
                }
            }

            "menu:to_home" -> {
                repository.setOnHome(appWidgetId, true)
                closeMenu(binding.tag)
            }

            "menu:from_home" -> {
                repository.setOnHome(appWidgetId, false)
                closeMenu(binding.tag)
            }

            "menu:reconfigure" -> {
                val info = repository.providerInfoFor(appWidgetId) ?: return
                val intent = repository.configureIntent(info, appWidgetId) ?: return
                pendingInfo = info
                _ui.update {
                    it.copy(
                        overlay = WidgetOverlay.NONE,
                        menuBindingId = null,
                        pending = PendingWidget(binding.provider, appWidgetId, true),
                        pendingFocusTag = binding.tag,
                    )
                }
                _configRequests.tryEmit(ConfigLaunch(intent))
            }

            "menu:remove" -> {
                repository.removeBinding(appWidgetId)
                _ui.update {
                    it.copy(
                        overlay = WidgetOverlay.NONE,
                        menuBindingId = null,
                        edit = null,
                        pendingFocusTag = "widget:add",
                    )
                }
            }
        }
    }

    private fun closeMenu(focusTag: String) {
        _ui.update {
            it.copy(
                overlay = WidgetOverlay.NONE,
                menuBindingId = null,
                pendingFocusTag = focusTag,
            )
        }
    }

    // ---------------------------------------------------------------- 移动与调整大小

    fun editDirection(dx: Int, dy: Int) {
        val edit = _ui.value.edit ?: return
        _ui.update { state ->
            val current = state.edit ?: return@update state
            val updated = if (current.moving) {
                val maxX = (WidgetsRepository.GRID_COLUMNS - current.spanX).coerceAtLeast(0)
                current.copy(
                    x = (current.x + dx).coerceIn(0, maxX),
                    y = (current.y + dy).coerceAtLeast(0),
                )
            } else {
                val spanX = if (dx != 0) {
                    (current.spanX + dx).coerceIn(1, WidgetsRepository.GRID_COLUMNS)
                } else {
                    current.spanX
                }
                val spanY = if (dy != 0) {
                    (current.spanY + dy).coerceIn(1, WidgetsRepository.GRID_ROWS)
                } else {
                    current.spanY
                }
                current.copy(
                    spanX = spanX,
                    spanY = spanY,
                    x = current.x.coerceIn(0, (WidgetsRepository.GRID_COLUMNS - spanX).coerceAtLeast(0)),
                )
            }
            state.copy(edit = updated)
        }
    }

    fun commitEdit() {
        val edit = _ui.value.edit ?: return
        repository.moveBinding(edit.appWidgetId, edit.x, edit.y, edit.spanX, edit.spanY)
        _ui.update {
            it.copy(edit = null, pendingFocusTag = "widget:${edit.appWidgetId}")
        }
    }

    fun cancelEdit() {
        val edit = _ui.value.edit ?: return
        _ui.update {
            it.copy(edit = null, pendingFocusTag = "widget:${edit.appWidgetId}")
        }
    }

    // ---------------------------------------------------------------- 占位卡动作

    fun reload(appWidgetId: Int) {
        repository.setState(appWidgetId, WidgetState.OK)
        repository.recreateHostView(appWidgetId)
        repository.updateOptions(appWidgetId)
        _ui.update { it.copy(pendingFocusTag = "widget:$appWidgetId") }
    }

    fun remove(appWidgetId: Int) {
        repository.removeBinding(appWidgetId)
        _ui.update {
            it.copy(
                menuBindingId = null,
                edit = null,
                pendingFocusTag = "widget:add",
            )
        }
    }

    fun toggleHome(appWidgetId: Int) {
        val binding = repository.findBinding(appWidgetId) ?: return
        repository.setOnHome(appWidgetId, !binding.onHome)
        _ui.update { it.copy(pendingFocusTag = binding.tag) }
    }

    // ---------------------------------------------------------------- 焦点

    fun noteFrameFocus(appWidgetId: Int, focused: Boolean) {
        if (!focused) return
        _ui.update {
            it.copy(focusTag = "widget:$appWidgetId", innerFocusId = null)
        }
    }

    /** 由页面把全局焦点状态同步进来，覆盖占位卡按钮、菜单项等非外框焦点。 */
    fun updateFocusTag(tag: String?) {
        if (tag == null) return
        _ui.update { if (it.focusTag == tag) it else it.copy(focusTag = tag) }
    }

    fun setInnerFocus(appWidgetId: Int, active: Boolean) {
        _ui.update { state ->
            if (active) {
                state.copy(
                    focusTag = "widget:$appWidgetId",
                    innerFocusId = appWidgetId,
                )
            } else if (state.innerFocusId == appWidgetId) {
                state.copy(innerFocusId = null)
            } else {
                state
            }
        }
    }

    fun exitInner() {
        val id = _ui.value.innerFocusId ?: return
        _ui.update {
            it.copy(innerFocusId = null, pendingFocusTag = "widget:$id")
        }
    }

    fun consumePendingFocus(tag: String) {
        _ui.update {
            if (it.pendingFocusTag == tag) it.copy(pendingFocusTag = null) else it
        }
    }

    // ---------------------------------------------------------------- 内部逻辑

    private fun onBindingsChanged(bindings: List<WidgetBinding>) {
        val previous = lastBindings.associateBy { it.appWidgetId }
        var nextFocus: String? = null
        val state = _ui.value
        for (binding in bindings) {
            val before = previous[binding.appWidgetId] ?: continue
            if (before.state == WidgetState.OK && binding.state != WidgetState.OK) {
                val watching = state.focusTag == binding.tag ||
                    state.pendingFocusTag == binding.tag
                if (watching) {
                    nextFocus = if (binding.state == WidgetState.ERROR) {
                        "${binding.tag}:reload"
                    } else {
                        "${binding.tag}:remove"
                    }
                }
            }
        }
        val removedFocused = previous.values.any { old ->
            bindings.none { it.appWidgetId == old.appWidgetId } && state.focusTag == old.tag
        }
        if (nextFocus == null && removedFocused) nextFocus = "widget:add"
        lastBindings = bindings
        _ui.update {
            it.copy(
                bindings = bindings,
                pendingFocusTag = nextFocus ?: it.pendingFocusTag,
            )
        }
    }

}
