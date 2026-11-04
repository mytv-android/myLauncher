package com.github.mytv.core.tvfocus

import androidx.compose.animation.core.animateFloatAsState
import com.github.mytv.core.designsystem.LocalHighContrastFocus
import com.github.mytv.core.designsystem.launcherFastSpatialSpec
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvFocusable(
    tag: String,
    rowId: String? = null,
    index: Int = 0,
    enabled: Boolean = true,
    scaleOnFocus: Float = 1f,
    shape: Shape = RoundedCornerShape(16.dp),
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusState = LocalTvFocus.current
    val requester = remember { FocusRequester() }
    val entry = remember(tag, rowId, index) { FocusEntry(tag, rowId, index) }
    var focused by remember { mutableStateOf(false) }
    var longJob by remember { mutableStateOf<Job?>(null) }
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    var downTime by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()

    DisposableEffect(entry) {
        focusState.register(entry, requester)
        onDispose { focusState.unregister(tag) }
    }

    SideEffect {
        if (focused && focusState.current != entry) {
            focusState.onFocused(entry)
        }
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

    val highContrast = LocalHighContrastFocus.current
    val scale by animateFloatAsState(
        targetValue = if (focused && !highContrast.value) scaleOnFocus else 1f,
        animationSpec = launcherFastSpatialSpec(),
    )

    fun pressCenter(fromKey: Boolean = false) {
        if (fromKey) focusState.onKeySound?.invoke("click")
        focusState.onUserAction?.invoke()
        focusState.request(tag)
        onClick()
    }

    fun longCenter(fromKey: Boolean = false) {
        if (onLongClick != null) {
            if (fromKey) focusState.onKeySound?.invoke("click")
            focusState.onUserAction?.invoke()
            focusState.request(tag)
            onLongClick.invoke()
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val center = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        if (!center) return false
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (!down) {
                    down = true
                    longFired = false
                    downTime = event.nativeKeyEvent.eventTime
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
                return true
            }
            KeyEventType.KeyUp -> {
                val hadDown = down
                down = false
                longJob?.cancel()
                longJob = null
                if (hadDown && !longFired) {
                    val held = event.nativeKeyEvent.eventTime - downTime
                    if (held >= 400L) longCenter(fromKey = true) else pressCenter(fromKey = true)
                }
                longFired = false
                return true
            }
            else -> return false
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) focusState.onFocused(entry)
            }
            .testTag(tag)
            .focusable(enabled = enabled)
            .onKeyEvent { handleKey(it) }
            .pointerInput(enabled, onClick, onLongClick) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = {
                        // 触屏/鼠标不移动遥控焦点：只执行一次打开（§10）
                        focusState.onUserAction?.invoke()
                        onClick()
                    },
                    onLongPress = {
                        if (onLongClick != null) {
                            focusState.onUserAction?.invoke()
                            onLongClick.invoke()
                        }
                    },
                )
            },
    ) {
        content(focused)
    }
}
