package com.github.mytv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCard
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.RowLeft
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.data.channels.ChannelApproval
import com.github.mytv.data.channels.ChannelEntry
import com.github.mytv.core.designsystem.R as DsR

@Composable
fun ChannelsPage(
    channels: List<ChannelEntry>,
    approvals: List<ChannelApproval>,
    enabled: Boolean,
    onToggle: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = RowLeft, top = 92.dp, end = RowLeft),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(DsR.string.manage_channels),
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier.padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (channel in channels) {
                val state = approvals.firstOrNull {
                    it.packageName == channel.packageName && it.channelKey == channel.key
                }?.state ?: "pending"
                ChannelRow(
                    channel = channel,
                    enabled = enabled,
                    state = state,
                    onClick = { onToggle(channel.packageName, channel.key) },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelEntry,
    enabled: Boolean,
    state: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    TvFocusable(
        tag = "channels:${channel.packageName}:${channel.key}",
        enabled = enabled,
        scaleOnFocus = 1.02f,
        shape = shape,
        onClick = onClick,
    ) { focused ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(shape)
                .background(if (focused) LauncherCardFocused else LauncherCard)
                .launcherFocusRing(focused, shape)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = channel.title,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = androidx.compose.ui.res.stringResource(
                    if (state == "approved") DsR.string.channel_shown else DsR.string.channel_hidden,
                ),
                color = LauncherFocusRing,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
