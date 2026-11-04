package com.github.mytv.feature.organizer

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Hooks the launcher wires to the organizer.
 *
 * The module owns networking, files, pairing and install; everything that touches a
 * launcher page or another module goes through this interface. All methods are called
 * on a NanoHTTPD worker thread.
 */
interface OrganizerCallbacks {
    /** Persists a display-name override for a component. */
    fun onAppLabelChanged(component: String, label: String)

    /** Persists the weather city chosen on the phone page. */
    fun onWeatherCityChanged(city: String)

    /** A wallpaper photo was pushed; [file] is a private copy. */
    fun onWallpaperChanged(file: File)

    /** A custom app icon was pushed; [file] is private. */
    fun onIconChanged(component: String, file: File)

    /** A personal video finished uploading into the private ambient folder. */
    fun onClipUploaded(file: File)

    /** Ask the launcher to show a pushed/opened file. */
    fun onOpenFile(file: File, mime: String, action: TransferAction)

    /** Plain text diagnostic report (redacted by the caller). */
    fun diagnostics(): String

    /** True while the children/elders restricted mode is active. */
    fun restrictedMode(): Boolean

    /** TV name for the web header (Settings.Global.DEVICE_NAME or model). */
    fun deviceName(): String

    companion object {
        fun default(context: Context): OrganizerCallbacks = DefaultOrganizerCallbacks(context)
    }
}

class DefaultOrganizerCallbacks(private val context: Context) : OrganizerCallbacks {
    override fun onAppLabelChanged(component: String, label: String) {
        OrganizerPrefs.setAppLabel(context, component, label)
    }

    override fun onWeatherCityChanged(city: String) {
        OrganizerPrefs.setWeatherCity(context, city)
    }

    override fun onWallpaperChanged(file: File) = Unit

    override fun onIconChanged(component: String, file: File) = Unit

    override fun onClipUploaded(file: File) = Unit

    override fun onOpenFile(file: File, mime: String, action: TransferAction) = Unit

    override fun diagnostics(): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        return buildString {
            appendLine("myLauncher diagnostics")
            appendLine("P50: 0ms")
            appendLine("P90: 0ms")
            appendLine("P99: 0ms")
            appendLine("manufacturer: ${Build.MANUFACTURER}")
            appendLine("model: ${Build.MODEL}")
            appendLine("device: ${Build.DEVICE}")
            appendLine("fingerprint: ${Build.FINGERPRINT}")
            appendLine("sdk: ${Build.VERSION.SDK_INT}")
            appendLine("version: $version")
        }
    }

    override fun restrictedMode(): Boolean = false

    override fun deviceName(): String =
        try {
            val name = if (Build.VERSION.SDK_INT >= 25) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
            name?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Throwable) {
            Build.MODEL
        }
}

/** Shared preference keys used by the phone page and the launcher. */
object OrganizerPrefs {
    const val ORGANIZER_PREFS = "launcher_organizer"
    const val LAUNCHER_PREFS = "launcher_settings"
    const val WEATHER_CITY_KEY = "weather_city"
    private const val LABEL_PREFIX = "label:"

    fun setAppLabel(context: Context, component: String, label: String) {
        context.getSharedPreferences(ORGANIZER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LABEL_PREFIX + component, label)
            .apply()
    }

    fun appLabel(context: Context, component: String): String? =
        context.getSharedPreferences(ORGANIZER_PREFS, Context.MODE_PRIVATE)
            .getString(LABEL_PREFIX + component, null)

    fun clearAppLabel(context: Context, component: String) {
        context.getSharedPreferences(ORGANIZER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(LABEL_PREFIX + component)
            .apply()
    }

    fun setWeatherCity(context: Context, city: String) {
        context.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(WEATHER_CITY_KEY, city)
            .apply()
    }

    fun weatherCity(context: Context): String =
        context.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
            .getString(WEATHER_CITY_KEY, "") ?: ""
}
