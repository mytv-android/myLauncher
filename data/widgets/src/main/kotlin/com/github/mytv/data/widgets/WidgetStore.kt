package com.github.mytv.data.widgets

import android.content.Context
import org.json.JSONArray

/**
 * 小组件绑定的本地持久化。SharedPreferences 里只保存稳定的宿主 ID 与绑定数组，
 * UI 位置、格数、状态都随绑定一起写入，进程重启后可以原样恢复。
 */
class WidgetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    val hostId: Int
        get() = synchronized(lock) {
            val existing = prefs.getInt(KEY_HOST_ID, 0)
            if (existing != 0) return existing
            var id = (System.nanoTime() xor (System.currentTimeMillis() shl 21)).toInt()
            id = id and Int.MAX_VALUE
            if (id == 0) id = 1
            prefs.edit().putInt(KEY_HOST_ID, id).commit()
            id
        }

    fun loadBindings(): List<WidgetBinding> = synchronized(lock) {
        val raw = prefs.getString(KEY_BINDINGS, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<WidgetBinding>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            WidgetBinding.fromJson(item)?.let(out::add)
        }
        out
    }

    fun saveBindings(bindings: List<WidgetBinding>) = synchronized(lock) {
        val array = JSONArray()
        bindings.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_BINDINGS, array.toString()).commit()
    }

    companion object {
        private const val PREFS_NAME = "com.github.mytv.data.widgets"
        private const val KEY_HOST_ID = "hostId"
        private const val KEY_BINDINGS = "bindings"
    }
}
