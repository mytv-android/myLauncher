package com.github.mytv.feature.quicksettings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherColors
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.LauncherScrim
import com.github.mytv.core.designsystem.LauncherSurfaceContainer
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.glass.GlassStyle
import com.github.mytv.core.glass.GlassSurface
import com.github.mytv.core.glass.GlassTier
import com.github.mytv.core.tvfocus.TvFocusable
import dev.chrisbanes.haze.HazeInput
import com.github.mytv.core.designsystem.R as DsR

data class QuickSettingsValues(
    val colorSource: String = "content",
    val glassTier: String = "auto",
    val schemeStyle: String = "tonal",
)

@Composable
fun QuickSettingsOverlay(
    values: QuickSettingsValues,
    tier: GlassTier,
    colors: LauncherColors,
    input: HazeInput?,
    onAdjust: (String, Int) -> Unit,
    onLauncherSettings: () -> Unit,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LauncherScrim)
            .pointerInput(Unit) {
                detectTapGestures { onDismiss() }
            },
    ) {
        GlassSurface(
            tier = tier,
            colors = colors,
            input = input,
            style = GlassStyle.Panel,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(420.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 30.dp, end = 20.dp, bottom = 16.dp)
                    .testTag("qs"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(DsR.string.quick_settings_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                SettingRow(
                    tag = "qs:source",
                    label = stringResource(R.string.qs_source),
                    value = sourceLabel(values.colorSource),
                    onClick = { onAdjust("color_source", 1) },
                )
                SettingRow(
                    tag = "qs:glass",
                    label = stringResource(R.string.qs_glass),
                    value = glassLabel(values.glassTier),
                    onClick = { onAdjust("glass_tier", 1) },
                )
                SettingRow(
                    tag = "qs:style",
                    label = stringResource(R.string.qs_style),
                    value = styleLabel(values.schemeStyle),
                    onClick = { onAdjust("scheme_style", 1) },
                )
                SettingRow(
                    tag = "qs:volume",
                    label = stringResource(R.string.qs_volume),
                    value = "",
                    onClick = { },
                )
                TileRow(
                    left = { Tile("qs:wifi", stringResource(R.string.qs_wifi)) { onAction("wifi") } },
                    right = { Tile("qs:bt", stringResource(R.string.qs_bt)) { onAction("bt") } },
                )
                TileRow(
                    left = { Tile("qs:dnd", stringResource(R.string.qs_dnd)) { onAction("dnd") } },
                    right = { Tile("qs:inputs", stringResource(R.string.qs_inputs)) { onAction("inputs") } },
                )
                TileRow(
                    left = { Tile("qs:saver", stringResource(R.string.qs_saver)) { onAction("saver") } },
                    right = { Tile("qs:parent", stringResource(R.string.qs_parent)) { onAction("parent") } },
                )
                TileRow(
                    left = { Tile("qs:ambient", stringResource(R.string.qs_ambient)) { onAction("ambient") } },
                    right = { Tile("qs:transfer", stringResource(R.string.qs_transfer)) { onAction("transfer") } },
                )
                Tile("qs:launcher_settings", stringResource(DsR.string.launcher_settings), fill = true) {
                    onLauncherSettings()
                }
            }
        }
    }
}

@Composable
private fun TileRow(
    left: @Composable () -> Unit,
    right: (@Composable () -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) {
            if (right != null) right()
        }
    }
}

@Composable
private fun Tile(
    tag: String,
    label: String,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .then(if (fill) Modifier.fillMaxWidth() else Modifier.fillMaxWidth())
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = Color.White, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingRow(
    tag: String,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainer)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, color = Color.White, fontSize = 15.sp)
            if (value.isNotEmpty()) {
                Text(text = value, color = if (focused) Color.White else LauncherFocusRing, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun sourceLabel(value: String): String = stringResource(
    when (value) {
        "icon" -> R.string.qs_icon
        "wallpaper" -> R.string.qs_wallpaper
        "fixed" -> R.string.qs_fixed
        else -> R.string.qs_content
    },
)

@Composable
private fun glassLabel(value: String): String = stringResource(
    when (value) {
        "T1" -> R.string.qs_live
        "T2" -> R.string.qs_frozen
        "T3" -> R.string.qs_solid
        else -> R.string.qs_auto
    },
)

@Composable
private fun styleLabel(value: String): String = when (value) {
    "content" -> "Content"
    "vibrant" -> "Vibrant"
    "expressive" -> "Expressive"
    else -> "Tonal"
}
