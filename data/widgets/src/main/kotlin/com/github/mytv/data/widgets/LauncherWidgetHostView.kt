package com.github.mytv.data.widgets

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import android.widget.TextView
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo

/**
 * 带崩溃隔离的 AppWidgetHostView。
 *
 * RemoteViews 的布局可能在宿主端无法展开（例如布局里出现框架不允许的 View）。
 * 这里在应用前先做一次展开探测，只把 InflateException 视为确定的布局错误；
 * 应用后再检查宿主是否换成了系统占位文字，两种情况都通过 [onLoadStateChanged] 上报。
 */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var loadFailed: Boolean = false
        private set

    var onLoadStateChanged: ((Boolean) -> Unit)? = null

    /** 内部控件持有真实焦点时，把本宿主节点也标记为焦点，供无障碍树关联契约标签。 */
    var accessibilityFocusOverride: Boolean = false
        set(value) {
            field = value
        }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (accessibilityFocusOverride) info.isFocused = true
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        var preInflateFailed = false
        if (remoteViews != null) {
            try {
                // 探测与应用走同一个入口；展开失败时宿主只会显示系统占位文字。
                remoteViews.apply(context, this)
            } catch (e: android.view.InflateException) {
                preInflateFailed = true
            } catch (_: Throwable) {
                // 其他异常可能是集合视图、适配器等待宿主补全，交给系统应用后的检查判断。
            }
        }
        try {
            super.updateAppWidget(remoteViews)
        } catch (e: Throwable) {
            preInflateFailed = true
        }
        val failed = preInflateFailed
        post {
            if (failed || hasSystemErrorView()) markFailed() else markSucceeded()
        }
    }

    override fun updateAppWidgetOptions(options: android.os.Bundle?) {
        try {
            super.updateAppWidgetOptions(options)
        } catch (_: Throwable) {
            markFailed()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT < 26) {
            WidgetInnerFocus.ensureFocusable(this)
        }
    }

    private fun markFailed() {
        if (loadFailed) return
        loadFailed = true
        onLoadStateChanged?.invoke(true)
    }

    private fun markSucceeded() {
        if (!loadFailed) return
        loadFailed = false
        onLoadStateChanged?.invoke(false)
    }

    private fun hasSystemErrorView(): Boolean {
        val text = systemErrorText() ?: return false
        return containsText(this, text)
    }

    private fun systemErrorText(): String? {
        val id = try {
            resources.getIdentifier("gadget_host_error_inflating", "string", "android")
        } catch (_: Exception) {
            0
        }
        return if (id != 0) {
            try {
                resources.getString(id)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private fun containsText(group: ViewGroup, text: String): Boolean {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView && child.text?.toString() == text) return true
            if (child is ViewGroup && containsText(child, text)) return true
        }
        return false
    }
}

/**
 * AppWidgetHost 的子类，让 createView 返回带错误探测能力的宿主视图。
 */
class LauncherAppWidgetHost(context: Context, hostId: Int) : android.appwidget.AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidgetInfo: AppWidgetProviderInfo,
    ): AppWidgetHostView = LauncherWidgetHostView(context)
}

/**
 * 遥控器进入小组件内部时需要的焦点处理。
 * API 23–25 的 RemoteViews 不会把可点击 View 自动设为可聚焦，这里统一补设。
 */
object WidgetInnerFocus {

    /** 递归把可点击的 View 设为可聚焦。 */
    fun ensureFocusable(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) {
                if (child.isClickable) child.isFocusable = true
                ensureFocusable(child)
            } else if (child.isClickable) {
                child.isFocusable = true
            }
        }
    }

    /**
     * 把焦点送入小组件内部的第一个可聚焦控件；没有可聚焦控件时直接点击第一个可点击控件。
     * 返回是否成功把焦点交给了某个子 View。
     */
    fun requestInnerFocus(root: ViewGroup): Boolean {
        ensureFocusable(root)
        val focusable = findFirst(root) { it.isFocusable && it.isShown && it.isEnabled }
        if (focusable != null) return focusable.requestFocus()
        val clickable = findFirst(root) { it.isClickable && it.isShown && it.isEnabled }
        if (clickable != null) {
            clickable.performClick()
            return true
        }
        return root.requestFocus()
    }

    fun tagHostViewForAccessibility(view: View, tag: String) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.viewIdResourceName = tag
            }
        }
    }

    private fun findFirst(group: ViewGroup, predicate: (View) -> Boolean): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (predicate(child)) return child
            if (child is ViewGroup) {
                findFirst(child, predicate)?.let { return it }
            }
        }
        return null
    }
}

/** API 31+ 的配色资源 ID。设计稿要求五组、每组 13 个色阶。 */
object WidgetColorResources {
    val accent1: IntArray = intArrayOf(
        android.R.color.system_accent1_0,
        android.R.color.system_accent1_10,
        android.R.color.system_accent1_50,
        android.R.color.system_accent1_100,
        android.R.color.system_accent1_200,
        android.R.color.system_accent1_300,
        android.R.color.system_accent1_400,
        android.R.color.system_accent1_500,
        android.R.color.system_accent1_600,
        android.R.color.system_accent1_700,
        android.R.color.system_accent1_800,
        android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    )

    val accent2: IntArray = intArrayOf(
        android.R.color.system_accent2_0,
        android.R.color.system_accent2_10,
        android.R.color.system_accent2_50,
        android.R.color.system_accent2_100,
        android.R.color.system_accent2_200,
        android.R.color.system_accent2_300,
        android.R.color.system_accent2_400,
        android.R.color.system_accent2_500,
        android.R.color.system_accent2_600,
        android.R.color.system_accent2_700,
        android.R.color.system_accent2_800,
        android.R.color.system_accent2_900,
        android.R.color.system_accent2_1000,
    )

    val accent3: IntArray = intArrayOf(
        android.R.color.system_accent3_0,
        android.R.color.system_accent3_10,
        android.R.color.system_accent3_50,
        android.R.color.system_accent3_100,
        android.R.color.system_accent3_200,
        android.R.color.system_accent3_300,
        android.R.color.system_accent3_400,
        android.R.color.system_accent3_500,
        android.R.color.system_accent3_600,
        android.R.color.system_accent3_700,
        android.R.color.system_accent3_800,
        android.R.color.system_accent3_900,
        android.R.color.system_accent3_1000,
    )

    val neutral1: IntArray = intArrayOf(
        android.R.color.system_neutral1_0,
        android.R.color.system_neutral1_10,
        android.R.color.system_neutral1_50,
        android.R.color.system_neutral1_100,
        android.R.color.system_neutral1_200,
        android.R.color.system_neutral1_300,
        android.R.color.system_neutral1_400,
        android.R.color.system_neutral1_500,
        android.R.color.system_neutral1_600,
        android.R.color.system_neutral1_700,
        android.R.color.system_neutral1_800,
        android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_1000,
    )

    val neutral2: IntArray = intArrayOf(
        android.R.color.system_neutral2_0,
        android.R.color.system_neutral2_10,
        android.R.color.system_neutral2_50,
        android.R.color.system_neutral2_100,
        android.R.color.system_neutral2_200,
        android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_400,
        android.R.color.system_neutral2_500,
        android.R.color.system_neutral2_600,
        android.R.color.system_neutral2_700,
        android.R.color.system_neutral2_800,
        android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_1000,
    )

    /** 把五组色板（每组 13 个 ARGB）拼成 setColorResources 需要的映射。 */
    fun build(
        accent1Tones: IntArray? = null,
        accent2Tones: IntArray? = null,
        accent3Tones: IntArray? = null,
        neutral1Tones: IntArray? = null,
        neutral2Tones: IntArray? = null,
    ): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        fun put(ids: IntArray, tones: IntArray?) {
            if (tones == null) return
            for (i in 0 until minOf(ids.size, tones.size)) out[ids[i]] = tones[i]
        }
        put(accent1, accent1Tones)
        put(accent2, accent2Tones)
        put(accent3, accent3Tones)
        put(neutral1, neutral1Tones)
        put(neutral2, neutral2Tones)
        return out
    }
}
