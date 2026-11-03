package com.github.mytv.launcher.acceptance

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

class StateProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("json"))
        cursor.addRow(arrayOf(stateJson()))
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    private fun stateJson(): String {
        val root = JSONObject()
        root.put("contract", 1)
        root.put("page", "home")
        root.put("overlay", JSONObject.NULL)
        root.put("mode", "browse")
        root.put("focus", JSONObject.NULL)
        root.put(
            "hero",
            JSONObject()
                .put("visible", false)
                .put("index", JSONObject.NULL)
                .put("key", JSONObject.NULL),
        )
        root.put("rows", JSONArray())
        root.put("theme", JSONObject.NULL)
        root.put("glass", JSONObject().put("tier", "T3").put("blurRegions", 0))
        root.put("deviceTier", "mid")
        root.put("approvals", JSONArray())
        root.put("widgets", JSONArray())
        root.put("watchlist", JSONArray())
        root.put("hiddenApps", JSONArray())
        root.put("series", JSONArray())
        root.put("organizer", JSONObject().put("running", false))
        root.put(
            "storage",
            JSONObject()
                .put("allFiles", JSONObject.NULL)
                .put("legacy", JSONObject.NULL)
                .put("writePermission", JSONObject.NULL)
                .put("roots", JSONArray()),
        )
        root.put("install", JSONObject().put("state", "idle"))
        root.put("viewer", JSONObject.NULL)
        root.put("player", JSONObject.NULL)
        root.put("folders", JSONArray())
        root.put("ambient", JSONObject.NULL)
        root.put(
            "restricted",
            JSONObject()
                .put("mode", "off")
                .put("paused", false)
                .put("locked", false)
                .put("remainingS", JSONObject.NULL)
                .put("hasPin", false)
                .put("pinLockedUntil", 0)
                .put("unlockedUntil", 0),
        )
        root.put("clock", JSONObject().put("is24h", true))
        root.put("weather", JSONObject().put("visible", false))
        root.put("defaultHome", JSONObject.NULL)
        root.put(
            "memory",
            JSONObject()
                .put("imageCacheBytes", 0)
                .put("frozenBlurBytes", 0),
        )
        root.put(
            "update",
            JSONObject()
                .put("state", "idle")
                .put("available", JSONObject.NULL)
                .put("lastError", JSONObject.NULL),
        )
        root.put(
            "counters",
            JSONObject()
                .put("rowRecompositionsDuringThemeTransition", 0)
                .put("themeTransitions", 0)
                .put("widgetReloads", JSONObject())
                .put(
                    "soundEffects",
                    JSONObject()
                        .put("nav", 0)
                        .put("click", 0)
                        .put("back", 0)
                        .put("invalid", 0),
                )
                .put("weatherRequests", 0)
                .put("updateChecks", 0),
        )
        return root.toString()
    }
}
