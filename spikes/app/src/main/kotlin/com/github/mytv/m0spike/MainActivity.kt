package com.github.mytv.m0spike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "main_created sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        setContent {
            MaterialExpressiveTheme(
                colorScheme = darkColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                SpikeMenu(
                    onHaze = { open(HazeActivity::class.java, "haze") },
                    onFrozen = { open(FrozenBlurActivity::class.java, "frozen") },
                    onPixel = { open(PixelCopyActivity::class.java, "pixelcopy") },
                    onInstall = { open(InstallActivity::class.java, "install") },
                )
            }
        }
    }

    private fun open(cls: Class<*>, name: String) {
        startActivity(Intent(this, cls))
        Log.i(TAG, "activity_started $name")
    }
}

@Composable
private fun SpikeMenu(
    onHaze: () -> Unit,
    onFrozen: () -> Unit,
    onPixel: () -> Unit,
    onInstall: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("M0 SPIKE", color = Color.White, fontSize = 40.sp)
        Text("sdk ${Build.VERSION.SDK_INT} / ${Build.MODEL}", color = Color(0xFFBBBBBB), fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onHaze) { Text("Haze T1") }
            Button(onClick = onFrozen) { Text("Frozen T2") }
            Button(onClick = onPixel) { Text("PixelCopy") }
            Button(onClick = onInstall) { Text("Install UI") }
        }
    }
}

const val TAG = "M0Spike"
