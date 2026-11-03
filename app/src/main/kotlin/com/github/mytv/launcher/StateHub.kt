package com.github.mytv.launcher

import androidx.compose.ui.graphics.toArgb
import com.github.mytv.core.designsystem.LauncherFocusRing
import com.github.mytv.core.designsystem.LauncherOnPrimary
import com.github.mytv.core.designsystem.LauncherOnPrimaryContainer
import com.github.mytv.core.designsystem.LauncherOnSecondaryContainer
import com.github.mytv.core.designsystem.LauncherOnSurface
import com.github.mytv.core.designsystem.LauncherOutline
import com.github.mytv.core.designsystem.LauncherPrimary
import com.github.mytv.core.designsystem.LauncherPrimaryContainer
import com.github.mytv.core.designsystem.LauncherSecondaryContainer
import com.github.mytv.core.designsystem.LauncherSurface
import com.github.mytv.core.designsystem.LauncherSurfaceContainer
import com.github.mytv.core.designsystem.LauncherSurfaceContainerHigh
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class Counters(
    var nav: Int = 0,
    var click: Int = 0,
    var back: Int = 0,
    var invalid: Int = 0,
)

object StateHub {
    private val lock = Any()
    private var snapshot = LauncherSnapshot.EMPTY
    private val counters = Counters()

    fun update(value: LauncherSnapshot) {
        synchronized(lock) {
            snapshot = value
        }
    }

    fun incrementSound(kind: String) {
        synchronized(lock) {
            when (kind) {
                "nav" -> counters.nav++
                "click" -> counters.click++
                "back" -> counters.back++
                "invalid" -> counters.invalid++
            }
        }
    }

    fun resetCounters() {
        synchronized(lock) {
            counters.nav = 0
            counters.click = 0
            counters.back = 0
            counters.invalid = 0
        }
    }

    fun json(): String = synchronized(lock) {
        build(snapshot, counters).toString()
    }

    private fun build(state: LauncherSnapshot, counters: Counters): JSONObject {
        val root = JSONObject()
        root.put("contract", 1)
        root.put("page", state.page)
        root.put("overlay", state.overlay ?: JSONObject.NULL)
        root.put("mode", state.mode)
        root.put(
            "focus",
            state.focus?.let {
                JSONObject()
                    .put("tag", it.tag)
                    .put("rowId", it.rowId ?: JSONObject.NULL)
                    .put("index", it.index ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        root.put(
            "hero",
            JSONObject()
                .put("visible", state.heroVisible)
                .put("index", state.heroIndex ?: JSONObject.NULL)
                .put("key", state.heroKey ?: JSONObject.NULL),
        )
        val rows = JSONArray()
        for (row in state.rows) {
            rows.put(
                JSONObject()
                    .put("id", row.id)
                    .put("items", JSONArray(row.items)),
            )
        }
        root.put("rows", rows)
        root.put("theme", theme())
        root.put("glass", JSONObject().put("tier", "T3").put("blurRegions", 0))
        root.put("deviceTier", "mid")
        root.put("approvals", JSONArray())
        root.put(
            "widgets",
            JSONArray().put(
                JSONObject()
                    .put("id", 0)
                    .put("provider", "builtin:clock")
                    .put("page", 0)
                    .put("x", 0)
                    .put("y", 0)
                    .put("spanX", 2)
                    .put("spanY", 2)
                    .put("onHome", false)
                    .put("state", "ok"),
            ),
        )
        root.put("watchlist", JSONArray(state.watchlist))
        root.put("hiddenApps", JSONArray(state.hiddenApps))
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
        val folders = JSONArray()
        for (folder in state.folders) {
            folders.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("apps", JSONArray(folder.apps)),
            )
        }
        root.put("folders", folders)
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
        root.put("clock", JSONObject().put("is24h", state.clockIs24h))
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
                        .put("nav", counters.nav)
                        .put("click", counters.click)
                        .put("back", counters.back)
                        .put("invalid", counters.invalid),
                )
                .put("weatherRequests", 0)
                .put("updateChecks", 0),
        )
        return root
    }

    private fun theme(): JSONObject = JSONObject()
        .put("seed", "#6750A4")
        .put("sourceKey", "fixed")
        .put("style", "tonal")
        .put("contrast", 0.0)
        .put("transitioning", false)
        .put(
            "roles",
            JSONObject()
                .put("primary", hex(LauncherPrimary))
                .put("onPrimary", hex(LauncherOnPrimary))
                .put("primaryContainer", hex(LauncherPrimaryContainer))
                .put("onPrimaryContainer", hex(LauncherOnPrimaryContainer))
                .put("secondaryContainer", hex(LauncherSecondaryContainer))
                .put("onSecondaryContainer", hex(LauncherOnSecondaryContainer))
                .put("surface", hex(LauncherSurface))
                .put("onSurface", hex(LauncherOnSurface))
                .put("surfaceContainer", hex(LauncherSurfaceContainer))
                .put("surfaceContainerHigh", hex(LauncherSurfaceContainerHigh))
                .put("outline", hex(LauncherOutline))
                .put("focusRing", hex(LauncherFocusRing)),
        )

    private fun hex(color: androidx.compose.ui.graphics.Color): String =
        String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)
}
