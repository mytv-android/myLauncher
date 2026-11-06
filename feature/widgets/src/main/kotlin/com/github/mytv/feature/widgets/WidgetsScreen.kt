package com.github.mytv.feature.widgets

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherScrim
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.launcherFastSpatialSpec
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.FocusEntry
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.widgets.BuiltinWidgets
import com.github.mytv.data.widgets.LauncherWidgetHostView
import com.github.mytv.data.widgets.WidgetBinding
import com.github.mytv.data.widgets.WidgetInnerFocus
import com.github.mytv.data.widgets.WidgetState
import com.github.mytv.data.widgets.WidgetsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.github.mytv.data.widgets.R as WidgetsDataR

private val GridGap = 12.dp
private val GridCellHeight = 112.dp
private val WidgetShape = RoundedCornerShape(24.dp)

@Composable
fun rememberWidgetsHost(repository: WidgetsRepository): WidgetsHost {
    val scope = rememberCoroutineScope()
    return remember(repository) { WidgetsHost(repository, scope) }
}

/**
 * 小组件页。app 在“小组件”Tab 下放这个 composable，并通过 [onUiState] 同步状态接口。
 */
@Composable
fun WidgetsScreen(
    repository: WidgetsRepository,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    weatherText: String? = null,
    host: WidgetsHost = rememberWidgetsHost(repository),
    onUiState: (WidgetsUiState) -> Unit = {},
    /** 内置时钟小组件上按 OK：宿主进入环境模式（大时钟）。 */
    onBuiltinClock: () -> Unit = {},
) {
    val ui by host.ui.collectAsState()
    val providers by repository.installedProviders.collectAsState()
    val focusState = LocalTvFocus.current
    val hostViews = remember { mutableMapOf<Int, LauncherWidgetHostView>() }

    val configLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        host.onConfigResult(result.resultCode)
    }

    LaunchedEffect(Unit) {
        repository.refreshProviders()
        repository.refreshBindingStates()
    }

    LaunchedEffect(host) {
        host.configRequests.collect { request ->
            try {
                configLauncher.launch(request.intent)
            } catch (_: Exception) {
                host.onConfigResult(Activity.RESULT_CANCELED)
            }
        }
    }

    LaunchedEffect(ui) { onUiState(ui) }

    LaunchedEffect(focusState) {
        snapshotFlow { focusState.current?.tag }.collect { tag -> host.updateFocusTag(tag) }
    }

    LaunchedEffect(ui.pendingFocusTag) {
        val tag = ui.pendingFocusTag ?: return@LaunchedEffect
        withFrameNanos { }
        focusState.request(tag)
        host.consumePendingFocus(tag)
    }

    DisposableEffect(host) {
        onDispose { host.onPageHidden() }
    }

    BackHandler(
        enabled = ui.edit != null || ui.innerFocusId != null || ui.overlay != WidgetOverlay.NONE,
    ) {
        focusState.onUserAction?.invoke()
        focusState.onKeySound?.invoke("back")
        when {
            ui.edit != null -> host.cancelEdit()
            ui.innerFocusId != null -> host.exitInner()
            ui.overlay == WidgetOverlay.ADB -> host.authBack()
            else -> host.dismissOverlay()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event -> handleEditKey(ui, host, event) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 94.dp, end = 48.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.page_widgets_title),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(18.dp))
            WidgetGrid(
                repository = repository,
                host = host,
                ui = ui,
                hostViews = hostViews,
                pageEnabled = enabled,
                weatherText = weatherText,
                onBuiltinClock = onBuiltinClock,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (ui.overlay) {
            WidgetOverlay.PICKER -> PickerOverlay(
                providers = providers,
                label = repository::providerLabel,
                providerTag = repository::providerTag,
                onPick = host::selectProvider,
                onDismiss = host::dismissOverlay,
            )

            WidgetOverlay.AUTH -> AuthOverlay(
                onAdb = host::authShowAdb,
                onCancel = host::authCancel,
            )

            WidgetOverlay.ADB -> AdbOverlay(
                onDone = host::authDone,
                onBack = host::authBack,
                onDismiss = host::dismissOverlay,
            )

            WidgetOverlay.MENU -> {
                val id = ui.menuBindingId
                if (id != null) {
                    MenuOverlay(
                        actions = host.menuActions(id),
                        onAction = host::onMenuAction,
                        onDismiss = host::dismissOverlay,
                    )
                }
            }

            WidgetOverlay.NONE -> Unit
        }
    }
}

private fun handleEditKey(ui: WidgetsUiState, host: WidgetsHost, event: KeyEvent): Boolean {
    if (ui.edit == null) return false
    val isDown = event.type == KeyEventType.KeyDown
    return when (event.key) {
        Key.DirectionLeft -> {
            if (isDown) host.editDirection(-1, 0)
            true
        }

        Key.DirectionRight -> {
            if (isDown) host.editDirection(1, 0)
            true
        }

        Key.DirectionUp -> {
            if (isDown) host.editDirection(0, -1)
            true
        }

        Key.DirectionDown -> {
            if (isDown) host.editDirection(0, 1)
            true
        }

        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
            if (!isDown) host.commitEdit()
            true
        }

        else -> false
    }
}

// ------------------------------------------------------------------ 网格

@Composable
private fun WidgetGrid(
    repository: WidgetsRepository,
    host: WidgetsHost,
    ui: WidgetsUiState,
    hostViews: MutableMap<Int, LauncherWidgetHostView>,
    pageEnabled: Boolean,
    weatherText: String?,
    onBuiltinClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = WidgetsRepository.GRID_COLUMNS
        val cellWidth = ((maxWidth - GridGap * (columns - 1)) / columns).coerceAtLeast(1.dp)
        val maxRow = (
            ui.bindings.filter { it.page == 0 }.map { it.y + it.spanY } +
                listOfNotNull(ui.edit?.let { it.y + it.spanY })
            ).maxOrNull() ?: 1
        val rows = maxOf(3, maxRow)
        val gridHeight = GridCellHeight * rows + GridGap * (rows - 1)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val focusTag = ui.focusTag ?: ui.pendingFocusTag
        LaunchedEffect(focusTag, scroll.maxValue, ui.bindings, ui.edit) {
            val tag = focusTag ?: return@LaunchedEffect
            val id = tag.removePrefix("widget:").substringBefore(':').toIntOrNull()
            val y = when {
                tag == "widget:add" || tag == BuiltinWidgets.CLOCK -> 0
                id != null -> ui.edit?.takeIf { it.appWidgetId == id }?.y
                    ?: ui.bindings.firstOrNull { it.appWidgetId == id }?.y
                    ?: 0
                else -> return@LaunchedEffect
            }
            val offset = with(density) { (GridCellHeight * y + GridGap * y).roundToPx() }
            scroll.scrollTo(offset.coerceIn(0, scroll.maxValue))
        }
        val inventory = repository
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
            ) {
                val idle = pageEnabled && ui.overlay == WidgetOverlay.NONE
                BuiltinClockTile(
                    weatherText = weatherText,
                    onClick = onBuiltinClock,
                    modifier = Modifier
                        .offset(x = 0.dp, y = 0.dp)
                        .size(cellWidth * 2 + GridGap, GridCellHeight),
                    enabled = idle && ui.edit == null,
                )
                AddTile(
                    modifier = Modifier
                        .offset(x = cellWidth * 2 + GridGap * 2, y = 0.dp)
                        .size(cellWidth, GridCellHeight),
                    enabled = idle && ui.edit == null,
                    onClick = host::openPicker,
                )

                ui.bindings.filter { it.page == 0 }.forEach { binding ->
                    val edit = ui.edit
                    val edited = edit?.takeIf { it.appWidgetId == binding.appWidgetId }
                    val x = edited?.x ?: binding.x
                    val y = edited?.y ?: binding.y
                    val spanX = edited?.spanX ?: binding.spanX
                    val spanY = edited?.spanY ?: binding.spanY
                    val frameModifier = Modifier
                        .offset(
                            x = cellWidth * x + GridGap * x,
                            y = GridCellHeight * y + GridGap * y,
                        )
                        .size(
                            width = cellWidth * spanX + GridGap * (spanX - 1),
                            height = GridCellHeight * spanY + GridGap * (spanY - 1),
                        )
                    val enabled = idle && (ui.edit == null || edited != null)
                    when {
                        edited != null -> EditPlaceholder(
                            binding = binding,
                            host = host,
                            modifier = frameModifier,
                        )

                        binding.state == WidgetState.ERROR -> WidgetPlaceholder(
                            binding = binding,
                            broken = true,
                            modifier = frameModifier,
                            onReload = { host.reload(binding.appWidgetId) },
                            onRemove = { host.remove(binding.appWidgetId) },
                        )

                        binding.state == WidgetState.MISSING -> WidgetPlaceholder(
                            binding = binding,
                            broken = false,
                            modifier = frameModifier,
                            onReload = { host.reload(binding.appWidgetId) },
                            onRemove = { host.remove(binding.appWidgetId) },
                        )

                        else -> WidgetHostFrame(
                            repository = inventory,
                            host = host,
                            binding = binding,
                            hostViews = hostViews,
                            enabled = enabled,
                            showTestTag = ui.innerFocusId != binding.appWidgetId,
                            modifier = frameModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltinClockTile(
    weatherText: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val is12h = remember(context) {
        Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24) == "12"
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val text = remember(now, is12h) {
        val pattern = if (is12h) "h:mm a" else "HH:mm"
        SimpleDateFormat(pattern, Locale.US).format(Date(now))
    }
    val title = stringResource(WidgetsDataR.string.builtin_clock_title)
    TvFocusable(
        tag = BuiltinWidgets.CLOCK,
        enabled = enabled,
        scaleOnFocus = 1.06f,
        shape = WidgetShape,
        onClick = onClick,
        modifier = modifier,
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(WidgetShape)
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, WidgetShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
            )
            if (!weatherText.isNullOrEmpty()) {
                Text(
                    text = weatherText,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AddTile(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvFocusable(
        tag = "widget:add",
        enabled = enabled,
        scaleOnFocus = 1.06f,
        shape = WidgetShape,
        onClick = onClick,
        modifier = modifier,
    ) { focused ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(WidgetShape)
                .background(LauncherSurfaceContainerHigh)
                .launcherFocusRing(focused, WidgetShape),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.widgets_add),
                color = Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun EditPlaceholder(
    binding: WidgetBinding,
    host: WidgetsHost,
    modifier: Modifier = Modifier,
) {
    WidgetFocusable(
        tag = binding.tag,
        enabled = true,
        showTestTag = true,
        onClick = {},
        onLongClick = null,
        onFocusChanged = { focused -> host.noteFrameFocus(binding.appWidgetId, focused) },
        modifier = modifier,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(WidgetShape)
                .background(LauncherSurfaceContainerHigh.copy(alpha = 0.6f))
                .launcherFocusRing(focused, WidgetShape),
        )
    }
}

@Composable
private fun WidgetPlaceholder(
    binding: WidgetBinding,
    broken: Boolean,
    onReload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .testTag(binding.tag)
            .clip(WidgetShape)
            .background(LauncherSurfaceContainerHigh)
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (broken) R.string.widget_broken else R.string.widget_missing,
                ),
                color = Color.White,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (broken) {
                    PlaceholderButton(
                        tag = "${binding.tag}:reload",
                        label = stringResource(R.string.widget_reload),
                        onClick = onReload,
                    )
                }
                PlaceholderButton(
                    tag = "${binding.tag}:remove",
                    label = stringResource(R.string.widget_remove),
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun PlaceholderButton(tag: String, label: String, onClick: () -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.03f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WidgetHostFrame(
    repository: WidgetsRepository,
    host: WidgetsHost,
    binding: WidgetBinding,
    hostViews: MutableMap<Int, LauncherWidgetHostView>,
    enabled: Boolean,
    showTestTag: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listeners = remember {
        mutableMapOf<
            LauncherWidgetHostView,
            Pair<ViewTreeObserver, ViewTreeObserver.OnGlobalFocusChangeListener>
            >()
    }
    val live by produceState(
        initialValue = repository.hasLiveWidget(binding.appWidgetId),
        binding.appWidgetId,
    ) {
        if (!value) {
            repeat(20) {
                delay(250)
                if (repository.hasLiveWidget(binding.appWidgetId)) {
                    value = true
                    return@produceState
                }
            }
        }
    }

    LaunchedEffect(binding.appWidgetId, binding.spanX, binding.spanY) {
        repository.updateOptions(binding.appWidgetId)
    }

    WidgetFocusable(
        tag = binding.tag,
        enabled = enabled,
        showTestTag = showTestTag,
        onClick = {
            val view = hostViews[binding.appWidgetId]
            if (view != null && WidgetInnerFocus.requestInnerFocus(view)) {
                host.setInnerFocus(binding.appWidgetId, true)
            }
        },
        onLongClick = { host.openMenu(binding.appWidgetId) },
        onFocusChanged = { focused -> host.noteFrameFocus(binding.appWidgetId, focused) },
        modifier = modifier,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(WidgetShape)
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, WidgetShape),
        ) {
            if (live) {
                AndroidView(
                    factory = {
                        val view = repository.createHostView(binding.appWidgetId)
                        if (view != null) {
                            hostViews[binding.appWidgetId] = view
                            WidgetInnerFocus.tagHostViewForAccessibility(view, binding.tag)
                            val launcherView = view
                            val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                                val inside = isInside(newFocus, launcherView)
                                launcherView.accessibilityFocusOverride = inside
                                if (inside) {
                                    host.setInnerFocus(binding.appWidgetId, true)
                                } else if (host.ui.value.innerFocusId == binding.appWidgetId) {
                                    host.setInnerFocus(binding.appWidgetId, false)
                                }
                            }
                            val observer = view.viewTreeObserver
                            listeners[view] = observer to listener
                            observer.addOnGlobalFocusChangeListener(listener)
                            view
                        } else {
                            View(context)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { },
                    onRelease = { view ->
                        if (view is LauncherWidgetHostView) {
                            view.accessibilityFocusOverride = false
                            listeners.remove(view)?.let { (_, listener) ->
                                try {
                                    view.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                                } catch (_: Throwable) {
                                }
                            }
                            hostViews.remove(binding.appWidgetId)
                            if (host.ui.value.innerFocusId == binding.appWidgetId) {
                                host.setInnerFocus(binding.appWidgetId, false)
                            }
                            repository.releaseHostView(view)
                        }
                    },
                )
            } else {
                Text(
                    text = stringResource(R.string.widget_broken),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private fun isInside(child: View?, ancestor: View): Boolean {
    var current: View? = child
    while (current != null) {
        if (current === ancestor) return true
        current = current.parent as? View
    }
    return false
}

// ------------------------------------------------------------------ 自定义可聚焦外框

@Composable
private fun WidgetFocusable(
    tag: String,
    enabled: Boolean,
    showTestTag: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusState = LocalTvFocus.current
    val requester = remember { FocusRequester() }
    val entry = remember(tag) { FocusEntry(tag, "widgets", 0) }
    var focused by remember { mutableStateOf(false) }
    var longJob by remember { mutableStateOf<Job?>(null) }
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DisposableEffect(entry) {
        focusState.register(entry, requester)
        onDispose { focusState.unregister(tag) }
    }

    SideEffect {
        if (focused && focusState.current != entry) focusState.onFocused(entry)
    }

    LaunchedEffect(focusState.pendingTag, focusState.pendingVersion, enabled) {
        if (enabled && focusState.pendingTag == tag) {
            repeat(6) {
                withFrameNanos { }
                try {
                    requester.requestFocus()
                    focusState.clearPending(tag)
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(16)
                }
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = launcherFastSpatialSpec(),
    )

    fun pressCenter(fromKey: Boolean = false) {
        if (fromKey) focusState.onKeySound?.invoke("click")
        focusState.onUserAction?.invoke()
        focusState.request(tag)
        onClick()
    }

    fun longCenter(fromKey: Boolean = false) {
        if (onLongClick == null) return
        if (fromKey) focusState.onKeySound?.invoke("click")
        focusState.onUserAction?.invoke()
        focusState.request(tag)
        onLongClick.invoke()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val center = event.key == Key.DirectionCenter || event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        if (!center) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (!down) {
                    down = true
                    longFired = false
                    longJob?.cancel()
                    longJob = scope.launch {
                        delay(400)
                        if (down && !longFired) {
                            longFired = true
                            longCenter(fromKey = true)
                        }
                    }
                } else if (!longFired && event.nativeKeyEvent.repeatCount > 0) {
                    longFired = true
                    longJob?.cancel()
                    longCenter(fromKey = true)
                }
                true
            }

            KeyEventType.KeyUp -> {
                val hadDown = down
                down = false
                longJob?.cancel()
                longJob = null
                if (hadDown && !longFired) pressCenter(fromKey = true)
                longFired = false
                true
            }

            else -> false
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    focusState.onFocused(entry)
                    onFocusChanged(true)
                } else {
                    onFocusChanged(false)
                }
            }
            .then(if (showTestTag) Modifier.testTag(tag) else Modifier)
            .focusable(enabled = enabled)
            .onKeyEvent { handleKey(it) }
            .pointerInput(enabled, onClick, onLongClick) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { pressCenter() },
                    onLongPress = { longCenter() },
                )
            },
    ) {
        content(focused)
    }
}

// ------------------------------------------------------------------ 浮层

@Composable
private fun PickerOverlay(
    providers: List<AppWidgetProviderInfo>,
    label: (AppWidgetProviderInfo) -> String,
    providerTag: (AppWidgetProviderInfo) -> String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayScrim(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(20.dp)
                .testTag("picker"),
        ) {
            Text(
                text = stringResource(R.string.widgets_add),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayItem(
                    tag = "picker:builtin:clock",
                    label = stringResource(WidgetsDataR.string.builtin_clock_title),
                    onClick = { onPick(BuiltinWidgets.CLOCK) },
                )
                providers.forEach { info ->
                    OverlayItem(
                        tag = "picker:${providerTag(info)}",
                        label = label(info),
                        onClick = { onPick(providerTag(info)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthOverlay(onAdb: () -> Unit, onCancel: () -> Unit) {
    OverlayScrim(onDismiss = onCancel) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(24.dp)
                .testTag("picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.widget_auth_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.widget_auth_body),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
            OverlayItem(
                tag = "picker:auth:adb",
                label = stringResource(R.string.widget_auth_adb),
                onClick = onAdb,
            )
            OverlayItem(
                tag = "picker:auth:cancel",
                label = stringResource(R.string.cancel),
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun AdbOverlay(onDone: () -> Unit, onBack: () -> Unit, onDismiss: () -> Unit) {
    OverlayScrim(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(24.dp)
                .testTag("picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.widget_auth_adb),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.widget_auth_adb_body),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
            Text(
                text = stringResource(R.string.widget_auth_command),
                color = Color.White,
                fontSize = 13.sp,
            )
            OverlayItem(
                tag = "picker:adb:done",
                label = stringResource(R.string.widget_auth_done),
                onClick = onDone,
            )
            OverlayItem(
                tag = "picker:adb:back",
                label = stringResource(R.string.back),
                onClick = onBack,
            )
        }
    }
}

@Composable
private fun MenuOverlay(
    actions: List<WidgetMenuAction>,
    onAction: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 64.dp)
                .width(340.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .padding(16.dp)
                .testTag("menu"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                OverlayItem(
                    tag = action.tag,
                    label = stringResource(action.labelRes),
                    onClick = { onAction(action.tag) },
                )
            }
        }
    }
}

@Composable
private fun OverlayScrim(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures { }
            },
        ) {
            content()
        }
    }
}

@Composable
private fun OverlayItem(tag: String, label: String, onClick: () -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                .launcherFocusRing(focused, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = label, color = Color.White, fontSize = 16.sp)
        }
    }
}

// ------------------------------------------------------------------ 主页速览

/**
 * 主页的第三方小组件速览行：最多 4 个 onHome 实例，按原尺寸显示实时宿主视图。
 * 调用方负责在外层行容器设置 `row:widgets` 标签。
 */
@Composable
fun HomeWidgetGlanceRow(
    repository: WidgetsRepository,
    modifier: Modifier = Modifier,
    maxCount: Int = 4,
) {
    val bindings by repository.bindings.collectAsState()
    val items = remember(bindings, maxCount) {
        bindings
            .filter { it.onHome && it.state == WidgetState.OK }
            .take(maxCount)
    }
    val hostViews = remember { mutableMapOf<Int, LauncherWidgetHostView>() }
    var innerFocusId by remember { mutableStateOf<Int?>(null) }
    val focusState = LocalTvFocus.current

    BackHandler(enabled = innerFocusId != null) {
        val id = innerFocusId
        innerFocusId = null
        if (id != null) focusState.request("widget:$id")
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { binding ->
            GlanceWidgetFrame(
                repository = repository,
                binding = binding,
                hostViews = hostViews,
                innerFocusId = innerFocusId,
                onEnter = { id -> innerFocusId = id },
                onLeave = { id ->
                    if (innerFocusId == id) innerFocusId = null
                },
            )
        }
    }
}

@Composable
private fun GlanceWidgetFrame(
    repository: WidgetsRepository,
    binding: WidgetBinding,
    hostViews: MutableMap<Int, LauncherWidgetHostView>,
    innerFocusId: Int?,
    onEnter: (Int) -> Unit,
    onLeave: (Int) -> Unit,
) {
    val context = LocalContext.current
    val listeners = remember {
        mutableMapOf<
            LauncherWidgetHostView,
            Pair<ViewTreeObserver, ViewTreeObserver.OnGlobalFocusChangeListener>
            >()
    }
    LaunchedEffect(binding.appWidgetId, binding.spanX, binding.spanY) {
        repository.updateOptions(binding.appWidgetId)
    }
    WidgetFocusable(
        tag = binding.tag,
        enabled = true,
        showTestTag = innerFocusId != binding.appWidgetId,
        onClick = {
            val view = hostViews[binding.appWidgetId]
            if (view != null && WidgetInnerFocus.requestInnerFocus(view)) {
                onEnter(binding.appWidgetId)
            }
        },
        onLongClick = null,
        onFocusChanged = { },
    ) { focused ->
        Box(
            modifier = Modifier
                .width(236.dp)
                .height(124.dp)
                .clip(WidgetShape)
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, WidgetShape),
        ) {
            AndroidView(
                factory = {
                    val view = repository.createHostView(binding.appWidgetId)
                    if (view != null) {
                        hostViews[binding.appWidgetId] = view
                        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                            if (isInside(newFocus, view)) {
                                onEnter(binding.appWidgetId)
                            } else {
                                onLeave(binding.appWidgetId)
                            }
                        }
                        val observer = view.viewTreeObserver
                        listeners[view] = observer to listener
                        observer.addOnGlobalFocusChangeListener(listener)
                        view
                    } else {
                        View(context)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { },
                onRelease = { view ->
                    if (view is LauncherWidgetHostView) {
                        listeners.remove(view)?.let { (_, listener) ->
                            try {
                                view.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                            } catch (_: Throwable) {
                            }
                        }
                        hostViews.remove(binding.appWidgetId)
                        repository.releaseHostView(view)
                    }
                },
            )
        }
    }
}
