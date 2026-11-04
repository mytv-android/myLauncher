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
    var themeTransitions: Int = 0,
    var rowRecompositions: Int = 0,
    val widgetReloads: MutableMap<String, Int> = HashMap(),
)

data class ExternalUi(
    val page: String?,
    val overlay: String?,
    val focusTag: String?,
)

object StateHub {
    @Volatile
    var externalUi: ExternalUi? = null

    @Volatile
    var organizerProvider: (() -> JSONObject?)? = null

    @Volatile
    var storageProvider: (() -> JSONObject?)? = null

    @Volatile
    var ambientProvider: (() -> JSONObject?)? = null

    @Volatile
    var widgetsProvider: (() -> JSONArray?)? = null

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

    fun incrementThemeTransition() {
        synchronized(lock) {
            counters.themeTransitions++
        }
    }

    fun incrementWidgetReload(appWidgetId: Int) {
        synchronized(lock) {
            val key = appWidgetId.toString()
            counters.widgetReloads[key] = (counters.widgetReloads[key] ?: 0) + 1
        }
    }

    fun incrementRowRecomposition() {
        synchronized(lock) {
            counters.rowRecompositions++
        }
    }

    fun resetCounters() {
        synchronized(lock) {
            counters.nav = 0
            counters.click = 0
            counters.back = 0
            counters.invalid = 0
            counters.themeTransitions = 0
            counters.rowRecompositions = 0
            counters.widgetReloads.clear()
        }
    }

    fun json(): String = synchronized(lock) {
        build(snapshot, counters).toString()
    }

    private fun build(state: LauncherSnapshot, counters: Counters): JSONObject {
        val root = JSONObject()
        val external = externalUi
        root.put("contract", 1)
        root.put("page", external?.page ?: state.page)
        root.put("overlay", external?.overlay ?: state.overlay ?: JSONObject.NULL)
        root.put("mode", state.mode)
        val externalFocus = external?.focusTag
        root.put(
            "focus",
            if (externalFocus != null) {
                JSONObject()
                    .put("tag", externalFocus)
                    .put("rowId", JSONObject.NULL)
                    .put("index", JSONObject.NULL)
            } else {
                state.focus?.let {
                    JSONObject()
                        .put("tag", it.tag)
                        .put("rowId", it.rowId ?: JSONObject.NULL)
                        .put("index", it.index ?: JSONObject.NULL)
                } ?: JSONObject.NULL
            },
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
        root.put("theme", theme(state.theme))
        root.put("glass", JSONObject().put("tier", state.glassTier).put("blurRegions", state.blurRegions))
        root.put("deviceTier", state.deviceTier)
        val approvals = JSONArray()
        for (approval in state.approvals) {
            approvals.put(
                JSONObject()
                    .put("pkg", approval.packageName)
                    .put("channelKey", approval.channelKey)
                    .put("state", approval.state),
            )
        }
        root.put("approvals", approvals)
        root.put("widgets", widgetsProvider?.invoke() ?: JSONArray())
        root.put("watchlist", JSONArray(state.watchlist))
        root.put("hiddenApps", JSONArray(state.hiddenApps))
        root.put("series", JSONArray())
        root.put("organizer", organizerProvider?.invoke() ?: JSONObject().put("running", false))
        root.put(
            "storage",
            storageProvider?.invoke() ?: JSONObject()
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
        root.put("ambient", ambientProvider?.invoke() ?: JSONObject.NULL)
        val restrictedLocked = state.restrictedMode == "kids" && state.restrictedRemaining <= 0L
        root.put(
            "restricted",
            JSONObject()
                .put("mode", state.restrictedMode)
                .put("paused", false)
                .put("locked", restrictedLocked)
                .put("remainingS", if (state.restrictedMode == "off") JSONObject.NULL else state.restrictedRemaining)
                .put("hasPin", state.restrictedHasPin)
                .put("pinLockedUntil", state.restrictedPinLockedUntil)
                .put("unlockedUntil", state.restrictedUnlockedUntil),
        )
        root.put("clock", JSONObject().put("is24h", state.clockIs24h))
        root.put(
            "weather",
            JSONObject()
                .put("visible", state.weatherVisible)
                .put("tempC", state.weatherTempC ?: JSONObject.NULL)
                .put("code", state.weatherCode ?: JSONObject.NULL)
                .put("city", state.weatherCity ?: JSONObject.NULL),
        )
        root.put("defaultHome", JSONObject.NULL)
        root.put(
            "memory",
            JSONObject()
                .put("imageCacheBytes", 0)
                .put("frozenBlurBytes", state.frozenBlurBytes),
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
                .put("rowRecompositionsDuringThemeTransition", counters.rowRecompositions)
                .put("themeTransitions", counters.themeTransitions)
                .put("widgetReloads", JSONObject(counters.widgetReloads as Map<*, *>))
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

    private fun theme(snapshot: ThemeSnapshot): JSONObject = JSONObject()
        .put("seed", String.format(Locale.US, "#%06X", snapshot.seed and 0xFFFFFF))
        .put("sourceKey", snapshot.sourceKey)
        .put("style", snapshot.style)
        .put("contrast", snapshot.contrast)
        .put("transitioning", snapshot.transitioning)
        .put(
            "roles",
            JSONObject()
                .put("primary", hex(snapshot.roles.primary))
                .put("onPrimary", hex(snapshot.roles.onPrimary))
                .put("primaryContainer", hex(snapshot.roles.primaryContainer))
                .put("onPrimaryContainer", hex(snapshot.roles.onPrimaryContainer))
                .put("secondaryContainer", hex(snapshot.roles.secondaryContainer))
                .put("onSecondaryContainer", hex(snapshot.roles.onSecondaryContainer))
                .put("surface", hex(snapshot.roles.surface))
                .put("onSurface", hex(snapshot.roles.onSurface))
                .put("surfaceContainer", hex(snapshot.roles.surfaceContainer))
                .put("surfaceContainerHigh", hex(snapshot.roles.surfaceContainerHigh))
                .put("outline", hex(snapshot.roles.outline))
                .put("focusRing", hex(snapshot.roles.focusRing)),
        )

    private fun hex(color: androidx.compose.ui.graphics.Color): String =
        String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)
}
