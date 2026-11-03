package com.github.mytv.m0spike

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

class LockscreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "lockscreen_created")
        setContent {
            MaterialExpressiveTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1A0505)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("LOCKED", color = Color.White, fontSize = 72.sp)
                }
            }
        }
    }
}
