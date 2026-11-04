package com.github.mytv.feature.widgets

import android.content.Intent
import com.github.mytv.data.widgets.WidgetBinding

/** 小组件页当前打开的浮层。picker 与授权/电脑授权页共用同一个面板。 */
enum class WidgetOverlay {
    NONE,
    PICKER,
    AUTH,
    ADB,
    MENU,
}

/** 移动 / 调整大小的临时编辑状态，OK 前不写存储。 */
data class WidgetEdit(
    val appWidgetId: Int,
    val moving: Boolean,
    val x: Int,
    val y: Int,
    val spanX: Int,
    val spanY: Int,
)

/** 正在绑定、等待配置页返回的实例。 */
data class PendingWidget(
    val provider: String,
    val appWidgetId: Int,
    val reconfiguring: Boolean,
)

/** 请求 UI 启动某个配置页。 */
data class ConfigLaunch(val intent: Intent)

data class WidgetMenuAction(
    val tag: String,
    val labelRes: Int,
)

/**
 * 小组件页对外的状态快照。app 可以把它映射到状态接口：
 * overlay 非 NONE 时对应 "picker" / "menu"，focusTag 为当前焦点。
 */
data class WidgetsUiState(
    val bindings: List<WidgetBinding> = emptyList(),
    val overlay: WidgetOverlay = WidgetOverlay.NONE,
    val menuBindingId: Int? = null,
    val edit: WidgetEdit? = null,
    val pending: PendingWidget? = null,
    val focusTag: String? = null,
    val innerFocusId: Int? = null,
    val pendingFocusTag: String? = null,
    val colorCounts: Map<Int, Int> = emptyMap(),
) {
    val overlayWire: String?
        get() = when (overlay) {
            WidgetOverlay.NONE -> null
            WidgetOverlay.MENU -> "menu"
            else -> "picker"
        }
}
