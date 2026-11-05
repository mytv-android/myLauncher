package com.github.mytv.launcher.acceptance

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.github.mytv.core.ambient.BackdropOverride
import com.github.mytv.core.ambient.BackdropSource
import com.github.mytv.launcher.StateHub

class StateProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        StateHttpServer.start(context?.applicationContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("json"))
        cursor.addRow(arrayOf(StateHub.json()))
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        handleCall(context?.applicationContext, method, arg)
        return null
    }

    companion object {
        internal fun handleCall(context: Context?, method: String, arg: String?): String {
            when (method) {
                "reset_counters" -> StateHub.resetCounters()
                "set_pref" -> {
                    val raw = arg ?: return ""
                    val split = raw.indexOf('=')
                    if (split <= 0) return ""
                    val key = raw.substring(0, split)
                    val value = raw.substring(split + 1)
                    if (key in ALLOWED_PREFS) {
                        val prefs = context?.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
                        // commit：验收步骤常常 set_pref 后立刻 force-stop 重启，apply 的异步落盘会丢
                        prefs?.edit()?.putString(key, value)?.commit()
                    }
                }
                "set_backdrop" -> BackdropOverride.set(parseBackdrop(arg))
                "net_check" -> Unit
                else -> return ""
            }
            return ""
        }

        private fun parseBackdrop(raw: String?): BackdropSource? {
            val value = raw?.trim() ?: return null
            if (value == "none") return null
            val parts = value.split(':')
            return when (parts.firstOrNull()) {
                "solid" -> parts.getOrNull(1)?.let { color ->
                    runCatching { android.graphics.Color.parseColor(color) }.getOrNull()
                        ?.let { BackdropSource.Solid(it) }
                }
                "checker" -> {
                    val cell = parts.getOrNull(1)?.toIntOrNull()
                    val first = parts.getOrNull(2)?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                    val second = parts.getOrNull(3)?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                    if (cell != null && first != null && second != null) {
                        BackdropSource.Checker(cell, first, second)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

        private val ALLOWED_PREFS = setOf(
            "ambient_idle_s",
            "saver_idle_s",
            "glass_tier",
            "color_source",
            "scheme_style",
            "contrast",
            "device_tier",
            "onboarding_done",
            "high_contrast_focus",
            "restricted_mode",
            "restricted_allowed",
            "kids_quota_s",
            "pin",
            "boot_target",
            "weather_city",
            "weather_base_url",
            "bing_base_url",
            "update_url",
        )
    }
}
