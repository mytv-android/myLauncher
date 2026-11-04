package com.github.mytv.launcher

import android.graphics.Color as AndroidColor
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mytv.data.weather.lookOf
import java.util.Random
import kotlinx.coroutines.delay

@Composable
fun IdleOverlay(model: LauncherModel) {
    val driftDp = remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(model.idleMode) {
        val random = Random()
        while (true) {
            driftDp.value = (random.nextInt(49) - 24).toFloat() to (random.nextInt(49) - 24).toFloat()
            delay(60_000L)
        }
    }
    val drift = driftDp.value
    val look = model.weatherCode?.let { lookOf(it) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    contentDescription = null
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = 36.dp)
                .padding(start = drift.first.dp, bottom = drift.second.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (model.weatherVisible && model.weatherTempC != null) {
                    if (look != null) {
                        WeatherGlyph(
                            sky = look.sky,
                            code = model.weatherCode ?: 0,
                            isDay = model.weatherIsDay,
                            cloudColor = Color(0xFFC5C6D0),
                            size = 44.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = "${model.weatherTempC}°",
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(end = 28.dp),
                    )
                }
                Text(
                    text = model.clockText,
                    color = Color.White,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Thin,
                    modifier = Modifier.testTag("ambient:clock"),
                )
            }
            val dateLine = if (model.lunarText.isBlank()) {
                model.dateText
            } else {
                "${model.dateText} · ${model.lunarText}"
            }
            Text(
                text = dateLine,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.testTag("ambient:date"),
            )
        }
    }
}
