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

data class HomeRowData(
    val id: String,
    val title: String,
    val items: List<HomeItem>,
)

data class MenuAction(
    val tag: String,
    val label: String,
)
