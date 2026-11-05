package com.github.mytv.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Process-wide guard so a boot target opens once per boot. The screen-on path
 * in MainActivity consults it to avoid a second launch after the receiver has
 * already opened the target.
 */
object BootTargetState {
    @Volatile
    var launchedThisProcess = false
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (BootTargetState.launchedThisProcess) return
        val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        val target = prefs.getString("boot_target", "home") ?: "home"
        val appTarget = target.removePrefix("app:")
        if (!target.startsWith("app:")) return
        val component = ComponentName.unflattenFromString(appTarget) ?: return
        BootTargetState.launchedThisProcess = true
        prefs.edit().putLong("boot_elapsed_ms", android.os.SystemClock.elapsedRealtime()).commit()
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            context.startActivity(launch)
        } catch (_: Exception) {
        }
    }
}
