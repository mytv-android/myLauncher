package com.github.mytv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherScrim
import com.github.mytv.core.designsystem.LauncherSurfaceContainer
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun QuickSettingsOverlay(
    onLauncherSettings: () -> Unit,
    onDismiss: () -> Unit,
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
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp)
                .background(LauncherSurfaceContainer)
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(start = 24.dp, top = 40.dp, end = 24.dp, bottom = 24.dp)
                .testTag("qs"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(DsR.string.quick_settings_title),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SettingsRow(
                tag = "qs:launcher_settings",
                label = stringResource(DsR.string.launcher_settings),
                onClick = onLauncherSettings,
            )
        }
    }
}

@Composable
fun SettingsPage(
    hiddenApps: List<com.github.mytv.data.apps.AppEntry>,
    expanded: Boolean,
    enabled: Boolean,
    onToggleHidden: () -> Unit,
    onRestore: (com.github.mytv.data.apps.AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0D11))
            .testTag("settings"),
    ) {
        Column(modifier = Modifier.padding(start = RowLeft, top = 92.dp)) {
            Text(
                text = stringResource(DsR.string.settings_title),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.padding(top = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsRow(
                    tag = "settings:hidden_apps",
                    label = stringResource(DsR.string.settings_hidden_apps),
                    enabled = enabled,
                    onClick = onToggleHidden,
                )
                if (expanded) {
                    for (app in hiddenApps) {
                        HiddenAppRow(
                            app = app,
                            enabled = enabled,
                            onRestore = { onRestore(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    tag: String,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .width(520.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = label, color = Color.White, fontSize = 17.sp)
        }
    }
}

@Composable
private fun HiddenAppRow(
    app: com.github.mytv.data.apps.AppEntry,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    val tag = "hidden:${app.tag}"
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.02f,
        shape = RoundedCornerShape(14.dp),
        onClick = onRestore,
    ) { focused ->
        Box(
            modifier = Modifier
                .width(520.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (focused) LauncherCardFocused else LauncherSurfaceContainerHigh)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = app.label + " · " + stringResource(DsR.string.settings_restore),
                color = Color.White,
                fontSize = 16.sp,
            )
        }
    }
}
