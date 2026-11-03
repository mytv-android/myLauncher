package com.github.mytv.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun TopBar(
    page: String,
    overlayOpen: Boolean,
    clockText: String,
    onTab: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !overlayOpen
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = RowLeft, end = RowLeft, top = 22.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavTab(
            tag = "nav:home",
            label = stringResource(DsR.string.nav_home),
            selected = page == "home",
            enabled = enabled,
            onClick = { onTab("home") },
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        NavTab(
            tag = "nav:apps",
            label = stringResource(DsR.string.nav_apps),
            selected = page == "apps",
            enabled = enabled,
            onClick = { onTab("apps") },
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        NavTab(
            tag = "nav:widgets",
            label = stringResource(DsR.string.nav_widgets),
            selected = page == "widgets",
            enabled = enabled,
            onClick = { onTab("widgets") },
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(LauncherSurfaceContainerHigh)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsuleButton(
                tag = "nav:search",
                label = stringResource(DsR.string.nav_search),
                enabled = enabled,
                onClick = {},
            )
            CapsuleButton(
                tag = "nav:ambient",
                label = stringResource(DsR.string.nav_ambient),
                enabled = enabled,
                onClick = {},
            )
            CapsuleButton(
                tag = "nav:settings",
                label = stringResource(DsR.string.nav_settings),
                enabled = enabled,
                onClick = onSettings,
            )
            Text(
                text = clockText,
                color = Color(0xFFE6E0E9),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .testTag("clock")
                    .padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun NavTab(
    tag: String,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.03f,
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        focused -> Color.White
                        selected -> Color(0x1FFFFFFF)
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color(0xFF17151C) else if (selected) Color.White else Color(0xFFCAC4D0),
                fontSize = 14.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CapsuleButton(
    tag: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TvFocusable(
        tag = tag,
        enabled = enabled,
        scaleOnFocus = 1.03f,
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(if (focused) LauncherCardFocused else Color.Transparent)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color.White else Color(0xFFCAC4D0),
                fontSize = 13.sp,
            )
        }
    }
}
