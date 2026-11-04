package com.github.mytv.launcher

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import android.os.Bundle
import android.provider.BaseColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.core.tvfocus.TvFocusState
import com.github.mytv.core.tvfocus.TvFocusable
import com.github.mytv.core.designsystem.MyLauncherTheme
import kotlinx.coroutines.flow.collectLatest
import com.github.mytv.core.designsystem.R as DsR

class ConfirmChannelActivity : ComponentActivity() {
    private var packageName: String? = null
    private var channelKey: String? = null
    private val externalUi = ExternalUi(
        page = "home",
        overlay = "confirm_channel",
        focusTag = "confirm_channel:allow",
    )
    private var publishedUi: ExternalUi = externalUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateHub.externalUi = externalUi
        val channelId = intent.getLongExtra("android.media.tv.extra.CHANNEL_ID", -1L)
        resolveChannel(channelId)
        setContent {
            val focusState = remember { TvFocusState() }
            LaunchedEffect(focusState) {
                for (i in 0 until 20) {
                    focusState.request("confirm_channel:allow")
                    kotlinx.coroutines.delay(50)
                    if (focusState.current?.tag == "confirm_channel:allow") break
                }
                snapshotFlow { focusState.current?.tag }.collectLatest { tag ->
                    if (tag != null) {
                        publishedUi = externalUi.copy(focusTag = tag)
                        StateHub.externalUi = publishedUi
                    }
                }
            }
            CompositionLocalProvider(LocalTvFocus provides focusState) {
                MyLauncherTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xE6000000))
                            .semantics { testTagsAsResourceId = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            ConfirmButton("confirm_channel:allow", stringResource(DsR.string.confirm_show)) {
                                finishWith(Activity.RESULT_OK, "approved")
                            }
                            ConfirmButton("confirm_channel:deny", stringResource(DsR.string.confirm_hide)) {
                                finishWith(Activity.RESULT_CANCELED, "denied")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (StateHub.externalUi === publishedUi) StateHub.externalUi = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        finishWith(Activity.RESULT_CANCELED, "denied")
    }

    private fun resolveChannel(channelId: Long) {
        if (channelId <= 0) return
        try {
            // TvProvider 不接受 selection（含 _ID），只能全量查询后本地筛选
            contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    BaseColumns._ID,
                    TvContract.Channels.COLUMN_PACKAGE_NAME,
                    TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getLong(0) != channelId) continue
                    packageName = cursor.getString(1)
                    channelKey = cursor.getString(2).orEmpty().ifEmpty { "#$channelId" }
                    break
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun finishWith(result: Int, state: String) {
        val pkg = packageName
        val key = channelKey
        if (pkg != null && key != null) {
            val edit = getSharedPreferences("channel_state", MODE_PRIVATE)
                .edit()
                .putString("a|$pkg|$key", state)
            if (state == "approved") {
                edit.putString("c|$pkg|$key", com.github.mytv.data.series.SeriesTrust.certDigest(this, pkg).orEmpty())
            }
            edit.apply()
        }
        setResult(result, Intent())
        finish()
    }
}

@Composable
private fun ConfirmButton(tag: String, label: String, onClick: () -> Unit) {
    TvFocusable(
        tag = tag,
        scaleOnFocus = 1.05f,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
    ) { focused ->
        Box(
            modifier = Modifier
                .width(260.dp)
                .height(62.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (focused) Color.White else Color(0xFF2A2830)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color.Black else Color.White,
                fontSize = 20.sp,
            )
        }
    }
}
