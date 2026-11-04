package com.github.mytv.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(readAll())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _values.value = readAll()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun readAll(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
}
