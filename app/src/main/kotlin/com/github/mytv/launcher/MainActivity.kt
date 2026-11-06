package com.github.mytv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.github.mytv.core.designsystem.MyLauncherTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: LauncherModel
    private var createdAt = 0L
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameDurations = LongArray(600)
    private var frameCount = 0
    private var frameWindowStart = 0L
    private var downgradeNoticeShown = false
    private val homeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS &&
                intent.getStringExtra("reason") == "homekey"
            ) {
                model.onHomeRequested()
            }
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                model.onScreenOn()
            } else if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                model.onScreenOff()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()
        model = LauncherModel(this)
        val homeFilter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(homeReceiver, homeFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(homeReceiver, homeFilter)
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                val duration = try {
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                } catch (_: Throwable) {
                    return@OnFrameMetricsAvailableListener
                }
                if (frameCount < frameDurations.size) frameDurations[frameCount++] = duration
                val now = SystemClock.elapsedRealtime()
                if (frameWindowStart == 0L) frameWindowStart = now
                if (now - frameWindowStart >= 3000 && frameCount >= 30) {
                    val sorted = frameDurations.copyOf(frameCount).apply { sort() }
                    val p95 = sorted[(frameCount - 1) * 95 / 100]
                    frameCount = 0
                    frameWindowStart = now
                    if (p95 > 16_700_000L && model.onFrameBudgetExceeded() && !downgradeNoticeShown) {
                        downgradeNoticeShown = true
                        Toast.makeText(this, getString(R.string.glass_downgraded), Toast.LENGTH_LONG).show()
                    }
                }
            }
            window.addOnFrameMetricsAvailableListener(listener, frameHandler)
            frameListener = listener
        }
        setContent {
            val highContrast = remember {
                derivedStateOf { model.highContrastFocus || model.restrictedMode == "elders" }
            }
            val frozen = remember { derivedStateOf { model.frozenBackdrop } }
            MyLauncherTheme(
                colors = model.theme.colors,
                transitioning = model.theme.transitionState,
                highContrastFocus = highContrast,
                frozenBackdrop = frozen,
            ) {
                LauncherRoot(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (SystemClock.elapsedRealtime() - createdAt < 2000) return
        if (intent.getBooleanExtra("kids_lock", false)) {
            model.showKidsLock()
            return
        }
        model.onHomeRequested()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && model.overlay == "pin") {
            val code = event.keyCode
            if (code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                model.onPinDigit(('0' + (code - android.view.KeyEvent.KEYCODE_0)))
                return true
            }
            if (code == android.view.KeyEvent.KEYCODE_DEL) {
                model.onPinBackspace()
                return true
            }
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_TV_INPUT) {
            if (event.action == android.view.KeyEvent.ACTION_UP) model.openInputs()
            return true
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_HOME) {
            if (event.action == android.view.KeyEvent.ACTION_UP) model.onHomeRequested()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        model.onHostResumed()
        model.refresh()
    }

    override fun onPause() {
        model.onHostPaused()
        super.onPause()
    }

    override fun onStop() {
        model.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        frameListener?.let { window.removeOnFrameMetricsAvailableListener(it) }
        frameListener = null
        try {
            unregisterReceiver(homeReceiver)
        } catch (_: Throwable) {
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
