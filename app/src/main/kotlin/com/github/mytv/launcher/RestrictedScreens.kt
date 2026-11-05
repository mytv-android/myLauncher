package com.github.mytv.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.feature.home.HomeItem
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun KidsLockScreen(enabled: Boolean, onExtend: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141218)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(DsR.string.kids_time_up),
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(32.dp))
            val shape = RoundedCornerShape(23.dp)
            TvFocusable(
                tag = "kids_lock:extend15",
                enabled = enabled,
                shape = shape,
                onClick = onExtend,
            ) { focused ->
                Text(
                    text = stringResource(DsR.string.kids_extend15),
                    color = if (focused) Color.Black else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(shape)
                        .background(if (focused) Color.White else LauncherSurfaceContainerHigh)
                        .launcherFocusRing(focused, shape)
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
fun PinOverlay(wrong: Boolean, entered: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF232127))
                .padding(32.dp)
                .testTag("pin"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(DsR.string.parent_pin),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(DsR.string.pin_enter),
                color = Color(0xFFCAC4D0),
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (index in 0 until 4) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        drawCircle(
                            color = if (index < entered) Color.White else Color(0xFF5A5761),
                            radius = size.minDimension / 2f,
                        )
                    }
                }
            }
            if (wrong) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(DsR.string.pin_wrong),
                    color = Color(0xFFFFB4AB),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
fun InputsOverlay(
    inputs: List<android.media.tv.TvInputInfo>,
    enabled: Boolean,
    onPick: (android.media.tv.TvInputInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        if (inputs.isEmpty()) {
            TvFocusable(
                tag = "inputs",
                enabled = enabled,
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.Center),
            ) { _ ->
                InputsPanel(inputs, enabled, onPick, tag = null)
            }
        } else {
            InputsPanel(inputs, enabled, onPick, tag = "inputs")
        }
    }
}

@Composable
private fun InputsPanel(
    inputs: List<android.media.tv.TvInputInfo>,
    enabled: Boolean,
    onPick: (android.media.tv.TvInputInfo) -> Unit,
    tag: String?,
) {
    Column(
        modifier = Modifier
            .width(560.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF232127))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(24.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(DsR.string.inputs_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        for (input in inputs) {
            val shape = RoundedCornerShape(14.dp)
            TvFocusable(
                tag = "input:${input.id}",
                enabled = enabled,
                shape = shape,
                onClick = { onPick(input) },
            ) { focused ->
                Text(
                    text = input.id,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .width(512.dp)
                        .height(54.dp)
                        .clip(shape)
                        .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                        .launcherFocusRing(focused, shape)
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
fun EldersHome(items: List<HomeItem>, enabled: Boolean, onAppClick: (com.github.mytv.data.apps.AppEntry) -> Unit) {
    val apps = items.filterIsInstance<HomeItem.App>().map { it.app }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = RowLeft, top = 220.dp, end = RowLeft),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for (rowStart in apps.indices step 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                for (index in rowStart until minOf(rowStart + 4, apps.size)) {
                    val app = apps[index]
                    ElderTile(app = app, index = index, enabled = enabled, onClick = { onAppClick(app) })
                }
            }
        }
    }
}

@Composable
private fun ElderTile(
    app: com.github.mytv.data.apps.AppEntry,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = app.tag,
        rowId = "apps",
        index = index,
        enabled = enabled,
        scaleOnFocus = 1.06f,
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
    ) { focused ->
        val shape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .width(150.dp)
                .launcherFocusRing(focused, shape)
                .clip(shape)
                .background(if (focused) LauncherCardFocused else Color(0x33FFFFFF))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val icon = app.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                )
            } else {
                Box(modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF44424A)))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
