package com.github.mytv.data.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.SizeF
import android.util.SparseIntArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.WeakHashMap

/** 一次绑定尝试的结果。NeedsGrant 时 appWidgetId 仍然有效，授权后可以重试。 */
sealed interface WidgetBindResult {
    data class Bound(val appWidgetId: Int) : WidgetBindResult
    data class NeedsGrant(val appWidgetId: Int, val systemBindIntent: Intent?) : WidgetBindResult
    data class Failed(val appWidgetId: Int, val reason: String?) : WidgetBindResult
}

/**
 * 第三方小组件的数据层：绑定、宿主、持久化、尺寸下发、失效检测与配色。
 *
 * 线程约束：公开方法应在主线程调用。AppWidgetHost 本身就是主线程对象。
 */
class WidgetsRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val store = WidgetStore(appContext)
    private val manager: AppWidgetManager =
        AppWidgetManager.getInstance(appContext)
            ?: throw IllegalStateException("AppWidgetManager unavailable")
    private val packageManager = appContext.packageManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val _bindings = MutableStateFlow(store.loadBindings())
    val bindings: StateFlow<List<WidgetBinding>> = _bindings.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _installedProviders = MutableStateFlow<List<AppWidgetProviderInfo>>(emptyList())
    val installedProviders: StateFlow<List<AppWidgetProviderInfo>> = _installedProviders.asStateFlow()

    private val _colorApplyCounts = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val colorApplyCounts: StateFlow<Map<Int, Int>> = _colorApplyCounts.asStateFlow()

    /** 每真正下发一次 setColorResources 回调一次，参数为 appWidgetId。 */
    var onColorApplied: ((Int) -> Unit)? = null

    val host: AppWidgetHost = LauncherAppWidgetHost(appContext, store.hostId)

    private val trackedViews: MutableSet<AppWidgetHostView> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val lastColors = WeakHashMap<AppWidgetHostView, Map<Int, Int>>()
    private val pendingIds: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    private var lastAppliedColors: Map<Int, Int>? = null

    /**
     * 每个绑定保留一个宿主视图，页面切换时只做挂载/卸载，不销毁。
     * 这样主题稳定后即使小组件页不在前台，也能对它下发配色。
     */
    private val hostViews: MutableMap<Int, LauncherWidgetHostView> = HashMap()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshProviders()
            refreshBindingStates()
        }
    }

    init {
        host.startListening()
        registerPackageReceiver()
        refreshProviders()
        refreshBindingStates()
        ensureHostViews()
        // 服务刚启动时 getAppWidgetInfo 可能暂时为空，稍后再对账一次。
        mainHandler.postDelayed({
            refreshProviders()
            refreshBindingStates()
            ensureHostViews()
        }, 1_500)
        // AppWidgetHost 的 ID 列表可能晚于 init 才从服务返回，延后清理不在本地的孤立 ID。
        mainHandler.postDelayed({ cleanupOrphanIds() }, 3_000)
    }

    // ---------------------------------------------------------------- 查询

    fun findBinding(appWidgetId: Int): WidgetBinding? =
        _bindings.value.firstOrNull { it.appWidgetId == appWidgetId }

    fun findProvider(providerTag: String): AppWidgetProviderInfo? {
        val cached = _installedProviders.value.firstOrNull {
            it.provider.flattenToString() == providerTag
        }
        if (cached != null) return cached
        refreshProviders()
        return _installedProviders.value.firstOrNull {
            it.provider.flattenToString() == providerTag
        }
    }

    fun providerLabel(info: AppWidgetProviderInfo): String = try {
        info.loadLabel(packageManager).toString()
    } catch (_: Exception) {
        info.provider.packageName
    }

    fun providerTag(info: AppWidgetProviderInfo): String = info.provider.flattenToString()

    fun providerInfoFor(appWidgetId: Int): AppWidgetProviderInfo? = try {
        manager.getAppWidgetInfo(appWidgetId)
    } catch (_: Exception) {
        null
    }

    fun hasLiveWidget(appWidgetId: Int): Boolean = providerInfoFor(appWidgetId) != null

    fun isProviderInstalled(binding: WidgetBinding): Boolean {
        val pkg = binding.component?.packageName ?: return false
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 按 targetCellWidth/Height（API 31+）或 minWidth/minHeight 换算出默认格数。 */
    fun defaultSpan(info: AppWidgetProviderInfo): Pair<Int, Int> {
        var spanX = 0
        var spanY = 0
        if (Build.VERSION.SDK_INT >= 31) {
            spanX = info.targetCellWidth
            spanY = info.targetCellHeight
        }
        if (spanX <= 0) spanX = (info.minWidth + 30 + CELL_DP - 1) / CELL_DP
        if (spanY <= 0) spanY = (info.minHeight + 30 + CELL_DP - 1) / CELL_DP
        return spanX.coerceIn(1, GRID_COLUMNS) to spanY.coerceIn(1, GRID_ROWS)
    }

    /** 主页速览最多放 4 个实例。 */
    fun onHomeBindings(limit: Int = 4): List<WidgetBinding> =
        _bindings.value.filter { it.onHome }.take(limit)

    fun optionsFor(binding: WidgetBinding): Bundle {
        val minWidth = (CELL_DP * binding.spanX - 30).coerceAtLeast(0)
        val minHeight = (CELL_DP * binding.spanY - 30).coerceAtLeast(0)
        val maxWidth = (CELL_DP * (binding.spanX + 1) - 30).coerceAtLeast(minWidth)
        val maxHeight = (CELL_DP * (binding.spanY + 1) - 30).coerceAtLeast(minHeight)
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, minHeight)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, maxWidth)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
            putInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            )
            if (Build.VERSION.SDK_INT >= 31) {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(SizeF(minWidth.toFloat(), minHeight.toFloat())),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 绑定

    fun allocateAndBind(info: AppWidgetProviderInfo): WidgetBindResult {
        val id = try {
            host.allocateAppWidgetId()
        } catch (e: Exception) {
            return WidgetBindResult.Failed(-1, e.message)
        }
        pendingIds.add(id)
        return if (bindInternal(id, info)) {
            WidgetBindResult.Bound(id)
        } else {
            WidgetBindResult.NeedsGrant(id, systemBindIntent(id, info))
        }
    }

    fun retryBind(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean =
        bindInternal(appWidgetId, info)

    private fun bindInternal(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean = try {
        val profile = info.profile ?: Process.myUserHandle()
        val options = Bundle().apply {
            putInt(
                AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            )
        }
        manager.bindAppWidgetIdIfAllowed(appWidgetId, profile, info.provider, options)
    } catch (_: Exception) {
        false
    }

    fun systemBindIntent(appWidgetId: Int, info: AppWidgetProviderInfo): Intent? {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
        return try {
            if (intent.resolveActivity(packageManager) != null) intent else null
        } catch (_: Exception) {
            null
        }
    }

    fun configureIntent(info: AppWidgetProviderInfo, appWidgetId: Int): Intent? {
        val component = info.configure ?: return null
        return Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }

    /** 首个不冲突的网格位置；第一行留给内置时钟与添加入口。 */
    fun findFreePlacement(spanX: Int, spanY: Int): Pair<Int, Int> {
        val occupied = ArrayList<IntArray>()
        occupied += intArrayOf(0, 0, 2, 1)
        occupied += intArrayOf(2, 0, 1, 1)
        _bindings.value.filter { it.page == 0 }.forEach {
            occupied += intArrayOf(it.x, it.y, it.spanX, it.spanY)
        }
        for (y in 0..40) {
            for (x in 0..(GRID_COLUMNS - spanX)) {
                val candidate = intArrayOf(x, y, spanX, spanY)
                if (occupied.none { overlaps(it, candidate) }) return x to y
            }
        }
        return 0 to 0
    }

    /**
     * 应用通过 requestPinAppWidget 发起的添加：用已分配的 ID 直接落位。
     * 调用方负责在成功后对 PinItemRequest.accept(appWidgetId)。
     */
    fun addPinnedBinding(info: AppWidgetProviderInfo, appWidgetId: Int): WidgetBinding {
        val span = defaultSpan(info)
        val (x, y) = findFreePlacement(span.first, span.second)
        return commitBinding(info, appWidgetId, 0, x, y, span.first, span.second)
    }

    private fun overlaps(a: IntArray, b: IntArray): Boolean =
        a[0] < b[0] + b[2] && b[0] < a[0] + a[2] &&
            a[1] < b[1] + b[3] && b[1] < a[1] + a[3]

    fun commitBinding(
        info: AppWidgetProviderInfo,
        appWidgetId: Int,
        page: Int,
        x: Int,
        y: Int,
        spanX: Int,
        spanY: Int,
        onHome: Boolean = false,
    ): WidgetBinding {
        val binding = WidgetBinding(
            appWidgetId = appWidgetId,
            provider = providerTag(info),
            page = page,
            x = x.coerceAtLeast(0),
            y = y.coerceAtLeast(0),
            spanX = spanX.coerceIn(1, GRID_COLUMNS),
            spanY = spanY.coerceIn(1, GRID_ROWS),
            onHome = onHome,
            state = WidgetState.OK,
            configured = true,
        )
        persist(_bindings.value.filterNot { it.appWidgetId == appWidgetId } + binding)
        pendingIds.remove(appWidgetId)
        updateOptions(binding)
        ensureHostViews()
        return binding
    }

    fun releaseAppWidgetId(appWidgetId: Int) {
        pendingIds.remove(appWidgetId)
        try {
            host.deleteAppWidgetId(appWidgetId)
        } catch (_: Exception) {
        }
    }

    fun removeBinding(appWidgetId: Int) {
        val binding = findBinding(appWidgetId) ?: return
        persist(_bindings.value.filterNot { it.appWidgetId == appWidgetId })
        forgetHostView(appWidgetId)
        releaseAppWidgetId(appWidgetId)
    }

    fun moveBinding(
        appWidgetId: Int,
        x: Int,
        y: Int,
        spanX: Int,
        spanY: Int,
    ): WidgetBinding? {
        val binding = findBinding(appWidgetId) ?: return null
        val updated = binding.copy(
            x = x.coerceAtLeast(0),
            y = y.coerceAtLeast(0),
            spanX = spanX.coerceIn(1, GRID_COLUMNS),
            spanY = spanY.coerceIn(1, GRID_ROWS),
        )
        if (updated != binding) persist(replace(updated))
        updateOptions(updated)
        return updated
    }

    fun setOnHome(appWidgetId: Int, onHome: Boolean) {
        val binding = findBinding(appWidgetId) ?: return
        if (binding.onHome == onHome) return
        persist(replace(binding.copy(onHome = onHome)))
    }

    fun setState(appWidgetId: Int, state: WidgetState) {
        val binding = findBinding(appWidgetId) ?: return
        if (binding.state == state) return
        persist(replace(binding.copy(state = state)))
    }

    fun markError(appWidgetId: Int) {
        val binding = findBinding(appWidgetId) ?: return
        if (binding.state == WidgetState.OK) setState(appWidgetId, WidgetState.ERROR)
    }

    fun updateOptions(appWidgetId: Int) {
        findBinding(appWidgetId)?.let(::updateOptions)
    }

    fun updateOptions(binding: WidgetBinding) {
        try {
            manager.updateAppWidgetOptions(binding.appWidgetId, optionsFor(binding))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- 宿主视图

    /**
     * 取绑定对应的宿主视图。视图由仓库缓存，可跨页面反复挂载；provider 被卸载或
     * ID 失效时返回 null。调用方不需要销毁视图，[releaseHostView] 只表示本次界面解除挂载。
     */
    fun createHostView(appWidgetId: Int): LauncherWidgetHostView? {
        hostViews[appWidgetId]?.let { cached ->
            if (cached.parent == null) return cached
        }
        val binding = findBinding(appWidgetId) ?: return null
        val info = providerInfoFor(appWidgetId) ?: return null
        if (!isProviderInstalled(binding)) {
            setState(appWidgetId, WidgetState.MISSING)
            return null
        }
        val view = try {
            host.createView(appContext, appWidgetId, info) as? LauncherWidgetHostView
        } catch (_: Exception) {
            null
        } ?: return null
        view.onLoadStateChanged = { failed ->
            if (failed) {
                mainHandler.post { markError(appWidgetId) }
            }
        }
        view.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        WidgetInnerFocus.tagHostViewForAccessibility(view, binding.tag)
        if (hostViews[appWidgetId] == null) hostViews[appWidgetId] = view
        trackedViews.add(view)
        // 新创建的宿主视图立即跟上当前主题，避免要等下一次主题稳定才换色。
        lastAppliedColors?.let { colors -> applyToView(view, colors) }
        return view
    }

    /** 调试命令 reset_counters 使用。 */
    fun resetColorApplyCounts() {
        _colorApplyCounts.value = emptyMap()
    }

    private fun ensureHostViews() {
        for (binding in _bindings.value) {
            if (binding.state == WidgetState.OK && isProviderInstalled(binding)) {
                createHostView(binding.appWidgetId)
            }
        }
    }

    /** 占位卡点“重新加载”时丢弃旧宿主视图，按系统里的最新内容重新创建。 */
    fun recreateHostView(appWidgetId: Int) {
        forgetHostView(appWidgetId)
        createHostView(appWidgetId)
    }

    /** 界面解除挂载。缓存视图继续保留；同时挂载产生的临时视图随界面回收。 */
    fun releaseHostView(view: AppWidgetHostView) {
        if (hostViews.values.none { it === view }) {
            trackedViews.remove(view)
            synchronized(lastColors) { lastColors.remove(view) }
        }
    }

    private fun forgetHostView(appWidgetId: Int) {
        hostViews.remove(appWidgetId)?.let { view ->
            trackedViews.remove(view)
            synchronized(lastColors) { lastColors.remove(view) }
        }
    }

    // ---------------------------------------------------------------- 配色

    /**
     * 把配色映射下发到当前所有宿主视图（API 31+）。默认相同的映射不会重复下发；
     * [force] 用于主题稳定后的那一次下发，即使配色未变也让小组件重新应用一次。
     * 返回本次真正更新过的视图数量；每更新一个视图回调一次 [onColorApplied]。
     */
    fun applyColors(colors: Map<Int, Int>, force: Boolean = false): Int {
        if (Build.VERSION.SDK_INT < 31 || colors.isEmpty()) return 0
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyColors(colors, force) }
            return 0
        }
        lastAppliedColors = colors
        val snapshot = synchronized(trackedViews) { trackedViews.toList() }
        var updated = 0
        for (view in snapshot) {
            if (applyToView(view, colors, force)) updated++
        }
        return updated
    }

    private fun applyToView(view: AppWidgetHostView, colors: Map<Int, Int>, force: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < 31 || colors.isEmpty() || view.appWidgetId < 0) return false
        val previous = synchronized(lastColors) { lastColors[view] }
        if (!force && previous == colors) return false
        val sparse = SparseIntArray()
        colors.forEach { (key, value) -> sparse.put(key, value) }
        try {
            view.setColorResources(sparse)
        } catch (_: Throwable) {
            return false
        }
        synchronized(lastColors) { lastColors[view] = colors }
        val id = view.appWidgetId
        _colorApplyCounts.value = _colorApplyCounts.value.toMutableMap().apply {
            this[id] = (this[id] ?: 0) + 1
        }
        onColorApplied?.invoke(id)
        return true
    }

    // ---------------------------------------------------------------- 刷新

    fun refreshProviders() {
        _installedProviders.value = try {
            manager.installedProviders.sortedBy { it.provider.flattenToString() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 重新对照系统状态：provider 不存在、被卸载或 ID 失效时标记为 missing。 */
    fun refreshBindingStates() {
        val current = _bindings.value
        var changed = false
        val updated = current.map { binding ->
            val info = providerInfoFor(binding.appWidgetId)
            val installed = isProviderInstalled(binding)
            val state = when {
                info == null || !installed -> WidgetState.MISSING
                binding.state == WidgetState.MISSING -> WidgetState.OK
                else -> binding.state
            }
            if (!installed) forgetHostView(binding.appWidgetId)
            if (state != binding.state) {
                changed = true
                binding.copy(state = state)
            } else {
                binding
            }
        }
        if (changed) persist(updated)
    }

    private fun cleanupOrphanIds() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val ids = host.appWidgetIds?.toSet() ?: return
            if (ids.isEmpty()) return
            val known = _bindings.value.map { it.appWidgetId }.toSet()
            for (id in ids) {
                if (id !in known && id !in pendingIds) {
                    try {
                        host.deleteAppWidgetId(id)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(packageReceiver, filter)
            }
        } catch (_: Exception) {
        }
    }

    private fun replace(binding: WidgetBinding): List<WidgetBinding> =
        _bindings.value.map { if (it.appWidgetId == binding.appWidgetId) binding else it }

    private fun persist(bindings: List<WidgetBinding>) {
        synchronized(lock) {
            store.saveBindings(bindings)
            _bindings.value = bindings
            _revision.value = _revision.value + 1
        }
    }

    companion object {
        const val GRID_COLUMNS = 4
        const val GRID_ROWS = 3
        const val CELL_DP = 70
    }
}
