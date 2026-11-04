package com.github.mytv.data.widgets

import android.content.ComponentName
import org.json.JSONObject

/** 小组件实例的加载状态，wire 值与状态接口一致。 */
enum class WidgetState(val wire: String) {
    OK("ok"),
    ERROR("error"),
    MISSING("missing");

    companion object {
        fun fromWire(value: String?): WidgetState =
            entries.firstOrNull { it.wire == value } ?: OK
    }
}

/**
 * 桌面上的一个第三方小组件实例。
 * provider 使用 [ComponentName.flattenToString]，即 `包名/类全名`，与状态接口一致。
 */
data class WidgetBinding(
    val appWidgetId: Int,
    val provider: String,
    val page: Int = 0,
    val x: Int = 0,
    val y: Int = 0,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val onHome: Boolean = false,
    val state: WidgetState = WidgetState.OK,
    val configured: Boolean = false,
) {
    val tag: String
        get() = "widget:$appWidgetId"

    val component: ComponentName?
        get() = ComponentName.unflattenFromString(provider)

    fun toJson(): JSONObject = JSONObject()
        .put("id", appWidgetId)
        .put("provider", provider)
        .put("page", page)
        .put("x", x)
        .put("y", y)
        .put("spanX", spanX)
        .put("spanY", spanY)
        .put("onHome", onHome)
        .put("state", state.wire)
        .put("configured", configured)

    companion object {
        fun fromJson(json: JSONObject): WidgetBinding? {
            val id = json.optInt("id", -1)
            val provider = json.optString("provider", "")
            if (id < 0 || provider.isEmpty()) return null
            return WidgetBinding(
                appWidgetId = id,
                provider = provider,
                page = json.optInt("page", 0),
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                spanX = json.optInt("spanX", 1).coerceAtLeast(1),
                spanY = json.optInt("spanY", 1).coerceAtLeast(1),
                onHome = json.optBoolean("onHome", false),
                state = WidgetState.fromWire(json.optString("state", "")),
                configured = json.optBoolean("configured", false),
            )
        }
    }
}

/** 数据层内置小组件的稳定键与标题资源。 */
object BuiltinWidgets {
    const val CLOCK = "widget:builtin:clock"
    const val WEATHER = "widget:builtin:weather"

    val all: List<String> = listOf(CLOCK, WEATHER)

    fun titleRes(id: String): Int = when (id) {
        CLOCK -> R.string.builtin_clock_title
        WEATHER -> R.string.builtin_weather_title
        else -> 0
    }
}
