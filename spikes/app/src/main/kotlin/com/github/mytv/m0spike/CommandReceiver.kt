package com.github.mytv.m0spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "command $command")
        when (command) {
            "open" -> {
                val target = intent.getStringExtra("target") ?: "main"
                val cls = when (target) {
                    "haze" -> HazeActivity::class.java
                    "frozen" -> FrozenBlurActivity::class.java
                    "pixelcopy" -> PixelCopyActivity::class.java
                    "install" -> InstallActivity::class.java
                    "lockscreen" -> LockscreenActivity::class.java
                    else -> MainActivity::class.java
                }
                val activityIntent = Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.getStringExtra("mode")?.let { activityIntent.putExtra("mode", it) }
                activityIntent.putExtra("duration", intent.getLongExtra("duration", 8000L))
                try {
                    context.startActivity(activityIntent)
                    Log.i(TAG, "command_started target=$target")
                } catch (e: Exception) {
                    Log.i(TAG, "command_start_failed target=$target error=${e::class.java.simpleName}: ${e.message}")
                }
            }
            "info" -> Log.i(
                TAG,
                "info sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL} " +
                    "fingerprint=${Build.FINGERPRINT}",
            )
            "storage" -> probeStorage(context)
            else -> Log.i(TAG, "command_unknown $command")
        }
    }

    private fun probeStorage(context: Context) {
        val external = android.os.Environment.getExternalStorageDirectory()
        Log.i(TAG, "storage_volume ${external.absolutePath} state=${android.os.Environment.getExternalStorageState()}")
        if (Build.VERSION.SDK_INT >= 30) {
            Log.i(TAG, "storage_manager=${android.os.Environment.isExternalStorageManager()}")
        }
        val name = "m0-storage-${System.currentTimeMillis()}.txt"
        try {
            val file = java.io.File("/sdcard/Download", name)
            file.writeText("m0")
            Log.i(TAG, "storage_write_new ok path=${file.absolutePath} size=${file.length()}")
        } catch (e: Exception) {
            Log.i(TAG, "storage_write_new failed ${e::class.java.simpleName}: ${e.message}")
        }
        val roots = java.io.File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("emulated", "self", "installer", "androidwritable") }
            ?: emptyList()
        Log.i(TAG, "storage_roots=${roots.joinToString(",") { it.name }}")
        for (root in roots + listOfNotNull(java.io.File("/storage/0000-0000").takeIf { it.isDirectory })) {
            try {
                val file = java.io.File(root, name)
                file.writeText("m0")
                Log.i(TAG, "storage_public_write ok root=${root.name} path=${file.absolutePath}")
            } catch (e: Exception) {
                Log.i(TAG, "storage_public_write failed root=${root.name} ${e::class.java.simpleName}: ${e.message}")
            }
        }
        val target = java.io.File("/sdcard/Download")
        val file = java.io.File(target, "m0-storage.txt")
        try {
            target.mkdirs()
            file.writeText("m0 ${System.currentTimeMillis()}")
            Log.i(TAG, "storage_write ok path=${file.absolutePath} size=${file.length()}")
        } catch (e: Exception) {
            Log.i(TAG, "storage_write failed ${e::class.java.simpleName}: ${e.message}")
        }
        val appDir = java.io.File(context.getExternalFilesDir(null), "m0-app.txt")
        try {
            appDir.writeText("m0")
            Log.i(TAG, "storage_app_write ok path=${appDir.absolutePath}")
        } catch (e: Exception) {
            Log.i(TAG, "storage_app_write failed ${e::class.java.simpleName}: ${e.message}")
        }
    }
}
