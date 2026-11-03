package com.github.mytv.launcher

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.github.mytv.core.designsystem.MyLauncherTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: LauncherModel
    private var createdAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()
        model = LauncherModel(this)
        setContent {
            MyLauncherTheme {
                LauncherRoot(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (SystemClock.elapsedRealtime() - createdAt < 2000) return
        model.onHomeRequested()
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}
