package com.github.mytv.launcher.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.mytv.data.restricted.RestrictedRepository

object RestrictedConfigEvents {
    @Volatile
    var onPinCleared: (() -> Unit)? = null
}

class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clear_pin", false)) {
            RestrictedRepository(context).clearPin()
            context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
                .edit()
                .remove("pin")
                .apply()
            RestrictedConfigEvents.onPinCleared?.invoke()
        }
    }
}
