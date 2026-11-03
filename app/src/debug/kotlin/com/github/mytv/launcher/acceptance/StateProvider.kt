package com.github.mytv.launcher.acceptance

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.github.mytv.launcher.StateHub

class StateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

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
        when (method) {
            "reset_counters" -> StateHub.resetCounters()
            "set_pref" -> {
                val raw = arg ?: return null
                val split = raw.indexOf('=')
                if (split <= 0) return null
                val key = raw.substring(0, split)
                val value = raw.substring(split + 1)
                if (key in ALLOWED_PREFS) {
                    val prefs = context?.getSharedPreferences("launcher_debug", Context.MODE_PRIVATE)
                    prefs?.edit()?.putString(key, value)?.apply()
                }
            }
            "set_backdrop", "net_check" -> Unit
            else -> return null
        }
        return null
    }

    private companion object {
        val ALLOWED_PREFS = setOf(
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
