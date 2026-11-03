package com.github.mytv.m0spike

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File

class InstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("INSTALL SESSION", color = Color.White, fontSize = 32.sp)
                }
            }
        }
        Thread { startInstall() }.start()
    }

    private fun startInstall() {
        val apk = File(getExternalFilesDir(null), "installable.apk")
        if (!apk.isFile) {
            log("install_no_apk ${apk.absolutePath}")
            finish()
            return
        }
        try {
            val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            val archiveCode = if (Build.VERSION.SDK_INT >= 28) {
                archive?.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archive?.versionCode?.toLong()
            }
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                    }
                }
                val callback = Intent(this, InstallResultReceiver::class.java)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(this, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
            log(
                "install_committed session=$sessionId apk=${apk.length()} " +
                    "pkg=${archive?.packageName} code=$archiveCode",
            )
        } catch (e: Exception) {
            log("install_error ${e::class.java.simpleName}: ${e.message}")
            finish()
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching {
            File(filesDir, "install.json").writeText(JSONObject().put("message", message).toString())
        }
    }
}
