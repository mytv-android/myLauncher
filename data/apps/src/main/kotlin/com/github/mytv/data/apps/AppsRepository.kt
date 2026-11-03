package com.github.mytv.data.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppsRepository(private val context: Context) {
    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle?) = refresh()
        override fun onPackageAdded(packageName: String, user: android.os.UserHandle?) = refresh()
        override fun onPackageChanged(packageName: String, user: android.os.UserHandle?) = refresh()
        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: android.os.UserHandle?,
            replacing: Boolean,
        ) = refresh()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: android.os.UserHandle?,
            replacing: Boolean,
        ) = refresh()
    }

    init {
        launcherApps.registerCallback(callback)
        refresh()
    }

    fun refresh() {
        scope.launch {
            try {
                val loadedApps = load()
                _apps.value = loadedApps
            } catch (_: Exception) {
            } finally {
                _loaded.value = true
            }
        }
    }

    private fun load(): List<AppEntry> {
        val user = Process.myUserHandle()
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        val installTimes = HashMap<String, Long>()
        val activities = try {
            launcherApps.getActivityList(null, user)
        } catch (_: Exception) {
            emptyList()
        }
        for (activity in activities) {
            val info = activity.applicationInfo
            val component = activity.componentName
            if (component.packageName == context.packageName) continue
            val key = "${component.packageName}/${component.className}"
            if (!seen.add(key)) continue
            val label = try {
                activity.label.toString()
            } catch (_: Exception) {
                component.packageName
            }
            val icon = try {
                val drawable = if (android.os.Build.VERSION.SDK_INT >= 25) {
                    activity.getBadgedIcon(0)
                } else {
                    activity.getIcon(0)
                }
                drawable.toBitmap(144, 144)
            } catch (_: Exception) {
                null
            }
            val banner = try {
                info.loadBanner(context.packageManager)?.toBitmap(480, 270)
            } catch (_: Exception) {
                null
            }
            installTimes[component.packageName] = try {
                context.packageManager.getPackageInfo(component.packageName, 0).firstInstallTime
            } catch (_: Exception) {
                0L
            }
            out += AppEntry(component.packageName, component.className, label, icon, banner)
        }
        return out.sortedWith(
            compareByDescending<AppEntry> { installTimes[it.packageName] ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    fun launch(entry: AppEntry): Boolean {
        val component = ComponentName(entry.packageName, entry.className)
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                launcherApps.startMainActivity(component, Process.myUserHandle(), null, null)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        val source = bitmap
        if (source.width == width && source.height == height) return source
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, width, height)
    draw(canvas)
    return bitmap
}
