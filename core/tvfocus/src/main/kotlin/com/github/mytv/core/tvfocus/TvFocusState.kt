package com.github.mytv.core.tvfocus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

data class FocusEntry(
    val tag: String,
    val rowId: String?,
    val index: Int,
)

class TvFocusState {
    var current by mutableStateOf<FocusEntry?>(null)
        private set

    var pendingTag by mutableStateOf<String?>(null)
        internal set
    var pendingVersion by mutableIntStateOf(0)
        private set
    var onUserAction: (() -> Unit)? = null
    var onKeySound: ((String) -> Unit)? = null

    private val requesters = mutableMapOf<String, FocusRequester>()
    private val entries = mutableMapOf<String, FocusEntry>()
    private val lastByRow = mutableMapOf<String, String>()

    fun register(entry: FocusEntry, requester: FocusRequester) {
        requesters[entry.tag] = requester
        entries[entry.tag] = entry
    }

    fun unregister(tag: String) {
        requesters.remove(tag)
        entries.remove(tag)
    }

    fun onFocused(entry: FocusEntry) {
        current = entry
        entry.rowId?.let { lastByRow[it] = entry.tag }
    }

    fun request(tag: String?) {
        if (tag.isNullOrEmpty()) return
        pendingTag = tag
        pendingVersion++
        val requester = requesters[tag]
        if (requester == null) return
        try {
            requester.requestFocus()
            if (pendingTag == tag) pendingTag = null
        } catch (_: Exception) {
        }
    }

    fun clearPending(tag: String) {
        if (pendingTag == tag) pendingTag = null
    }

    fun isRegistered(tag: String): Boolean = entries.containsKey(tag)

    fun entryFor(tag: String): FocusEntry? = entries[tag]

    fun tagForRow(rowId: String): String? = lastByRow[rowId]

    fun tagAt(rowId: String, index: Int): String? =
        entries.values.firstOrNull { it.rowId == rowId && it.index == index }?.tag

    fun snapshotLastByRow(): Map<String, String> = lastByRow.toMap()

    fun clearRowMemory() = lastByRow.clear()
}

val LocalTvFocus = staticCompositionLocalOf<TvFocusState> {
    error("TvFocusState not provided")
}
