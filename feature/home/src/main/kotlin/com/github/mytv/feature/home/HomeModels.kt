package com.github.mytv.feature.home

import com.github.mytv.data.apps.AppEntry
import com.github.mytv.data.apps.Folder
import com.github.mytv.data.channels.WatchNextEntry

sealed interface HomeItem {
    val tag: String

    data class Next(
        val entry: WatchNextEntry,
        val inWatchlist: Boolean = false,
    ) : HomeItem {
        override val tag: String
            get() = if (inWatchlist) entry.watchlistKey else entry.key
    }

    data class App(val app: AppEntry) : HomeItem {
        override val tag: String
            get() = app.tag
    }

    data class FolderTile(
        val folder: Folder,
        val apps: List<AppEntry>,
    ) : HomeItem {
        override val tag: String
            get() = "folder:${folder.id}"
    }

    data class Program(val program: com.github.mytv.data.channels.ChannelProgram) : HomeItem {
        override val tag: String
            get() = program.tag
    }

    data object ManageChannels : HomeItem {
        override val tag: String
            get() = "manage_channels"
    }

    data class Widget(val appWidgetId: Int) : HomeItem {
        override val tag: String
            get() = "widget:$appWidgetId"
    }
}

/**
 * Hero 精选的一项（PLAN 第 3 节第 1 条）：已批准频道各取权重最高的节目，不足时用继续观看补足。
 *
 * [entry] 是可播放、可加入片单的形式；频道节目也转换成它，片单里统一按 `wl:` 键保存。
 */
data class HeroItem(
    /** 状态接口里的 hero.key：频道节目是 `prog:…`，继续观看是 `wn:…`。 */
    val key: String,
    val entry: WatchNextEntry,
    /** 来源：频道名，或继续观看所属应用的名称。 */
    val source: String,
    val badge: android.graphics.Bitmap?,
    val fromChannel: Boolean,
    val genre: String? = null,
    val releaseDate: String? = null,
    val program: com.github.mytv.data.channels.ChannelProgram? = null,
)

data class HomeRowData(
    val id: String,
    val title: String,
    val items: List<HomeItem>,
)

data class MenuAction(
    val tag: String,
    val label: String,
)
