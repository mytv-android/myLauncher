package com.github.mytv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun PickerOverlay(
    onPick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) {
                detectTapGestures { onCancel() }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(LauncherCard)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(24.dp)
                .testTag("picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvFocusable(
                tag = "picker:builtin:clock",
                scaleOnFocus = 1.02f,
                shape = RoundedCornerShape(14.dp),
                onClick = onPick,
            ) { focused ->
                Box(
                    modifier = Modifier
                        .width(512.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(DsR.string.page_widgets_title),
                        color = Color.White,
                        fontSize = 16.sp,
                    )
                }
            }
            TvFocusable(
                tag = "picker:auth:cancel",
                scaleOnFocus = 1.02f,
                shape = RoundedCornerShape(14.dp),
                onClick = onCancel,
            ) { focused ->
                Box(
                    modifier = Modifier
                        .width(512.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(DsR.string.cancel),
                        color = Color.White,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
