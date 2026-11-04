package com.github.mytv.launcher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.mytv.core.designsystem.LauncherCardFocused
import com.github.mytv.core.designsystem.MyLauncherTheme
import com.github.mytv.core.designsystem.launcherFocusRing
import com.github.mytv.core.tvfocus.LocalTvFocus
import com.github.mytv.core.tvfocus.TvFocusState
import com.github.mytv.core.tvfocus.TvFocusable
import kotlinx.coroutines.flow.collectLatest
import com.github.mytv.core.designsystem.R as DsR

class ConfirmPinWidgetActivity : ComponentActivity() {
    private val externalUi = ExternalUi(
        page = "home",
        overlay = "confirm_pin",
        focusTag = "confirm_pin:add",
    )
    private var publishedUi: ExternalUi = externalUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StateHub.externalUi = externalUi
        setContent {
            val focusState = remember { TvFocusState() }
            LaunchedEffect(focusState) {
                for (i in 0 until 20) {
                    focusState.request("confirm_pin:add")
                    kotlinx.coroutines.delay(50)
                    if (focusState.current?.tag == "confirm_pin:add") break
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
                    PinConfirm(onAdd = { confirmPin() }, onCancel = { finish() })
                }
            }
        }
    }

    override fun onDestroy() {
        if (StateHub.externalUi === publishedUi) StateHub.externalUi = null
        super.onDestroy()
    }

    private fun confirmPin() {
        if (Build.VERSION.SDK_INT >= 26) {
            val request: LauncherApps.PinItemRequest? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    LauncherApps.EXTRA_PIN_ITEM_REQUEST,
                    LauncherApps.PinItemRequest::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST)
            }
            val provider = request?.getAppWidgetProviderInfo(this)
            if (request != null && provider != null) {
                val repository = WidgetsHolder.get(this)
                when (val result = repository.allocateAndBind(provider)) {
                    is com.github.mytv.data.widgets.WidgetBindResult.Bound -> {
                        repository.addPinnedBinding(provider, result.appWidgetId)
                        request.accept(
                            Bundle().apply {
                                putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, result.appWidgetId)
                            },
                        )
                    }
                    is com.github.mytv.data.widgets.WidgetBindResult.NeedsGrant -> {
                        repository.releaseAppWidgetId(result.appWidgetId)
                    }
                    else -> Unit
                }
            }
        }
        finish()
    }
}

@Composable
private fun PinConfirm(onAdd: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141218))
            .semantics { testTagsAsResourceId = true }
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(DsR.string.widgets_add),
            color = Color.White,
            fontSize = 30.sp,
        )
        Row(modifier = Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val shape = RoundedCornerShape(23.dp)
            TvFocusable(tag = "confirm_pin:add", shape = shape, onClick = onAdd) { focused ->
                Text(
                    text = stringResource(DsR.string.widget_pin_add),
                    color = if (focused) Color.Black else Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .width(120.dp)
                        .height(46.dp)
                        .background(if (focused) Color.White else LauncherCardFocused)
                        .launcherFocusRing(focused, shape)
                        .padding(14.dp),
                )
            }
            TvFocusable(tag = "confirm_pin:cancel", shape = shape, onClick = onCancel) { focused ->
                Text(
                    text = stringResource(DsR.string.cancel),
                    color = if (focused) Color.Black else Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .width(120.dp)
                        .height(46.dp)
                        .background(if (focused) Color.White else LauncherCardFocused)
                        .launcherFocusRing(focused, shape)
                        .padding(14.dp),
                )
            }
        }
        Text(
            text = stringResource(DsR.string.widgets_add),
            color = Color.Transparent,
            fontSize = 1.sp,
            modifier = Modifier.height(1.dp),
        )
    }
}
