package com.github.mytv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.provider.BaseColumns

class ChannelRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED" -> handleRequest(context, intent)
            "android.media.tv.action.INITIALIZE_PROGRAMS" -> {
                context.getSharedPreferences("channel_state", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("refresh", System.currentTimeMillis())
                    .apply()
            }
        }
    }

    private fun handleRequest(context: Context, intent: Intent) {
        val channelId = intent.getLongExtra("android.media.tv.extra.CHANNEL_ID", -1L)
        if (channelId <= 0) return
        try {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    TvContract.Channels.COLUMN_PACKAGE_NAME,
                    TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                "${BaseColumns._ID}=?",
                arrayOf(channelId.toString()),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pkg = cursor.getString(0) ?: return
                    val key = cursor.getString(1).orEmpty().ifEmpty { "#$channelId" }
                    context.getSharedPreferences("channel_state", Context.MODE_PRIVATE)
                        .edit()
                        .putString("a|$pkg|$key", "pending")
                        .apply()
                }
            }
        } catch (_: Exception) {
        }
    }
}
