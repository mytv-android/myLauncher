package com.github.mytv.data.channels

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import com.github.mytv.data.series.SeriesRepository
import com.github.mytv.data.series.SeriesTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChannelRepository(
    private val context: Context,
    private val series: SeriesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("channel_state", Context.MODE_PRIVATE)
    private val _approvals = MutableStateFlow<List<ChannelApproval>>(emptyList())
    val approvals: StateFlow<List<ChannelApproval>> = _approvals.asStateFlow()
    private val _channels = MutableStateFlow<List<ChannelEntry>>(emptyList())
    val channels: StateFlow<List<ChannelEntry>> = _channels.asStateFlow()
    private val _programs = MutableStateFlow<Map<String, List<ChannelProgram>>>(emptyMap())
    val programs: StateFlow<Map<String, List<ChannelProgram>>> = _programs.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refresh()
    }

    init {
        try {
            context.contentResolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, observer)
            if (Build.VERSION.SDK_INT >= 26) {
                context.contentResolver.registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer)
            }
        } catch (_: Exception) {
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        scope.launch {
            series.channels.collect { rebuild() }
        }
        scope.launch {
            series.previewPrograms.collect { rebuild() }
        }
        refresh()
    }

    fun refresh() {
        scope.launch { rebuild() }
    }

    private fun rebuild() {
        val channels = ArrayList<ChannelEntry>()
        val programs = LinkedHashMap<String, ArrayList<ChannelProgram>>()
        val seriesPkgs = HashSet<String>()
        if (Build.VERSION.SDK_INT >= 26 && hasTvProvider()) {
            readTvChannels(channels, seriesPkgs)
            readTvPrograms(channels, programs)
        }
        mergeBridge(channels, programs, seriesPkgs)
        val approvals = channels.map { channel ->
            ChannelApproval(channel.packageName, channel.key, approvalState(channel))
        }
        val stateByKey = approvals.associate { (it.packageName to it.channelKey) to it.state }
        val applied = channels.map { channel ->
            channel.copy(approved = stateByKey[channel.packageName to channel.key] == "approved")
        }
        val sorted = applied.sortedWith(
            compareByDescending<ChannelEntry> { it.approved }
                .thenByDescending { programs[it.rowId]?.size ?: 0 }
                .thenBy { it.title },
        )
        val orderedPrograms = LinkedHashMap<String, List<ChannelProgram>>()
        for (channel in sorted) {
            val list = programs[channel.rowId] ?: continue
            orderedPrograms[channel.rowId] = list.sortedWith(
                compareByDescending<ChannelProgram> { it.weight }
                    .thenByDescending { it.lastEngagementTimeMillis },
            )
        }
        _channels.value = sorted
        _programs.value = orderedPrograms
        _approvals.value = approvals
        _loaded.value = true
    }

    private fun approvalState(channel: ChannelEntry): String {
        val approveKey = "a|${channel.packageName}|${channel.key}"
        val certKey = "c|${channel.packageName}|${channel.key}"
        val stored = prefs.getString(approveKey, null)
        if (stored == "approved") {
            // 批准与当时应用的签名绑定：同名不同证书（冒名应用）不继承之前的批准
            val current = SeriesTrust.certDigest(context, channel.packageName)
            val storedCert = prefs.getString(certKey, null)
            val stale = if (storedCert != null) {
                current == null || !storedCert.equals(current, ignoreCase = true)
            } else {
                SeriesTrust.isTrustedPackage(channel.packageName) &&
                    !SeriesTrust.isTrusted(context, channel.packageName)
            }
            if (stale) {
                prefs.edit().putString(approveKey, "pending").remove(certKey).apply()
                return "pending"
            }
            // 之前批准过不代表这次安装拿到过回传；重装/更新后按安装版本补发一次
            ensureApproveSent(channel)
            return "approved"
        }
        if (stored != null) return stored
        if (channel.series || SeriesTrust.isTrusted(context, channel.packageName)) {
            val cert = SeriesTrust.certDigest(context, channel.packageName).orEmpty()
            prefs.edit().putString(approveKey, "approved").putString(certKey, cert).apply()
            ensureApproveSent(channel)
            return "approved"
        }
        prefs.edit().putString(approveKey, "pending").apply()
        return "pending"
    }

    /** 每个安装版本回传一次 approve：重装/更新后要重新得知频道已批准，进程重启不会重复。 */
    private fun ensureApproveSent(channel: ChannelEntry) {
        val stampKey = "u|${channel.packageName}|${channel.key}"
        val stamp = try {
            context.packageManager.getPackageInfo(channel.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        if (prefs.getLong(stampKey, -1L) == stamp) return
        prefs.edit().putLong(stampKey, stamp).apply()
        series.approve(channel.packageName, channel.key)
    }

    private fun hasTvProvider(): Boolean = try {
        context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID),
            null,
            null,
            null,
        )?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    private fun readTvChannels(out: ArrayList<ChannelEntry>, seriesPkgs: MutableSet<String>) {
        val projection = arrayOf(
            BaseColumns._ID,
            TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_TYPE,
        )
        val cursor = try {
            context.contentResolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return
        cursor.use {
            while (it.moveToNext()) {
                val pkg = it.string(1) ?: continue
                val key = it.string(4).orEmpty().ifEmpty { "#${it.long(0)}" }
                if (pkg == context.packageName) continue
                val trusted = SeriesTrust.isTrusted(context, pkg)
                if (trusted) seriesPkgs += pkg
                val channel = ChannelEntry(
                    packageName = pkg,
                    key = key,
                    title = it.string(2).orEmpty(),
                    logoUri = TvContract.buildChannelLogoUri(it.long(0)).toString(),
                    appLinkIntentUri = it.string(3),
                    approved = false,
                    browsable = it.int(5) != 0,
                    series = trusted,
                    type = it.int(6),
                )
                out += channel
            }
        }
    }

    private fun readTvPrograms(channels: List<ChannelEntry>, out: MutableMap<String, ArrayList<ChannelProgram>>) {
        val byId = HashMap<Long, ChannelEntry>()
        // Re-query ids because readTvChannels does not keep them.
        val idCursor = try {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
                null,
                null,
                null,
            )
        } catch (_: Exception) {
            null
        }
        idCursor?.use {
            while (it.moveToNext()) {
                val pkg = it.string(1) ?: continue
                val key = it.string(2).orEmpty().ifEmpty { "#${it.long(0)}" }
                val match = channels.firstOrNull { c -> c.packageName == pkg && c.key == key } ?: continue
                byId[it.long(0)] = match
            }
        }
        val projection = arrayOf(
            BaseColumns._ID,
            TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_TYPE,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_LIVE,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.PreviewPrograms.COLUMN_CANONICAL_GENRE,
            TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
        )
        val cursor = try {
            context.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, projection, null, null, null)
        } catch (_: Exception) {
            null
        } ?: return
        cursor.use {
            while (it.moveToNext()) {
                val channel = byId[it.long(1)] ?: continue
                val internal = it.string(3).orEmpty()
                if (isRemoved(channel.packageName, internal)) continue
                val program = ChannelProgram(
                    packageName = channel.packageName,
                    channelKey = channel.key,
                    internalId = internal,
                    title = it.string(4).orEmpty(),
                    description = it.string(5),
                    posterArtUri = it.string(6),
                    aspectRatio = it.int(7),
                    type = it.int(8),
                    intentUri = it.string(9),
                    weight = it.int(10),
                    live = it.int(11) != 0,
                    durationMillis = it.long(12),
                    playbackPositionMillis = it.long(13),
                    seasonNumber = parseDisplayNumber(it.string(14)),
                    episodeNumber = parseDisplayNumber(it.string(15)),
                    lastEngagementTimeMillis = it.long(16),
                    genre = it.string(17),
                    releaseDate = it.string(18),
                    id = it.long(0),
                )
                out.getOrPut(channel.rowId) { ArrayList() } += program
            }
        }
    }

    private fun mergeBridge(
        channels: ArrayList<ChannelEntry>,
        programs: MutableMap<String, ArrayList<ChannelProgram>>,
        seriesPkgs: MutableSet<String>,
    ) {
        val bridgeChannels = series.channels.value
        val byId = HashMap<String, ChannelEntry>()
        for (bridgeChannel in bridgeChannels) {
            if (bridgeChannel.key.isEmpty()) continue
            seriesPkgs += bridgeChannel.packageName
            val existing = channels.firstOrNull {
                it.packageName == bridgeChannel.packageName && it.key == bridgeChannel.key
            }
            val channel = existing ?: ChannelEntry(
                packageName = bridgeChannel.packageName,
                key = bridgeChannel.key,
                title = bridgeChannel.title,
                logoUri = bridgeChannel.logoUri,
                appLinkIntentUri = bridgeChannel.appLinkIntentUri,
                approved = true,
                browsable = bridgeChannel.browsable,
                series = true,
                type = 0,
            ).also { channels += it }
            byId[bridgeChannel.id.toString()] = channel
        }
        for (bridgeProgram in series.previewPrograms.value) {
            val channel = byId[bridgeProgram.channelKey] ?: channels.firstOrNull {
                it.packageName == bridgeProgram.packageName && it.series
            } ?: continue
            if (isRemoved(bridgeProgram.packageName, bridgeProgram.key)) continue
            val program = ChannelProgram(
                packageName = bridgeProgram.packageName,
                channelKey = channel.key,
                internalId = bridgeProgram.key,
                title = bridgeProgram.title,
                description = bridgeProgram.description,
                posterArtUri = bridgeProgram.posterArtUri,
                aspectRatio = bridgeProgram.aspectRatio,
                type = bridgeProgram.type,
                intentUri = bridgeProgram.intentUri,
                weight = bridgeProgram.weight,
                live = bridgeProgram.live,
                durationMillis = bridgeProgram.durationMillis,
                playbackPositionMillis = bridgeProgram.playbackPositionMillis,
                seasonNumber = bridgeProgram.seasonNumber,
                episodeNumber = bridgeProgram.episodeNumber,
                lastEngagementTimeMillis = bridgeProgram.lastEngagementTimeMillis,
                genre = bridgeProgram.genre,
                releaseDate = bridgeProgram.releaseDate,
                id = 0L,
            )
            programs.getOrPut(channel.rowId) { ArrayList() } += program
        }
    }

    fun setApproval(pkg: String, key: String, state: String) {
        val edit = prefs.edit().putString("a|$pkg|$key", state)
        if (state == "approved") {
            edit.putString("c|$pkg|$key", SeriesTrust.certDigest(context, pkg).orEmpty())
        } else {
            edit.remove("c|$pkg|$key")
        }
        edit.apply()
        if (state == "approved" && SeriesTrust.isTrusted(context, pkg)) {
            series.approve(pkg, key)
        }
        refresh()
    }

    fun approve(pkg: String, key: String) = setApproval(pkg, key, "approved")

    fun deny(pkg: String, key: String) = setApproval(pkg, key, "denied")

    fun approvalFor(pkg: String, key: String): String? =
        _approvals.value.firstOrNull { it.packageName == pkg && it.channelKey == key }?.state

    fun channelById(id: Long): ChannelEntry? {
        var found: ChannelEntry? = null
        try {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
                "${BaseColumns._ID}=?",
                arrayOf(id.toString()),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val pkg = c.string(0).orEmpty()
                    val key = c.string(1).orEmpty().ifEmpty { "#$id" }
                    found = _channels.value.firstOrNull { it.packageName == pkg && it.key == key }
                }
            }
        } catch (_: Exception) {
        }
        return found
    }

    fun onBrowsableRequested(packageName: String?, channelId: Long?) {
        if (channelId != null) {
            val channel = channelById(channelId)
            if (channel != null) {
                setApproval(channel.packageName, channel.key, "pending")
                return
            }
        }
        if (!packageName.isNullOrEmpty()) {
            val current = _channels.value.filter { it.packageName == packageName }
            for (channel in current) {
                if (SeriesTrust.isTrusted(context, packageName)) {
                    setApproval(channel.packageName, channel.key, "approved")
                } else {
                    setApproval(channel.packageName, channel.key, "pending")
                }
            }
        }
        refresh()
    }

    fun removeProgram(pkg: String, internalId: String) {
        if (internalId.isEmpty()) return
        prefs.edit().putBoolean("r|$pkg|$internalId", true).apply()
        if (SeriesTrust.isTrusted(context, pkg)) series.hide(pkg, internalId)
        refresh()
    }

    fun isRemoved(pkg: String, internalId: String): Boolean =
        internalId.isNotEmpty() && prefs.getBoolean("r|$pkg|$internalId", false)

    fun approvedChannels(): List<ChannelEntry> = _channels.value.filter { it.approved }

    fun programsFor(rowId: String): List<ChannelProgram> = _programs.value[rowId] ?: emptyList()

    fun allPrograms(): List<ChannelProgram> = _programs.value.values.flatten()

    fun seriesFor(pkg: String): Boolean = SeriesTrust.isTrusted(context, pkg)

    fun close() {
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }
}

private fun Cursor.string(column: Int): String? = if (column < 0 || isNull(column)) null else getString(column)

private fun Cursor.long(column: Int): Long = if (column < 0 || isNull(column)) 0L else getLong(column)

private fun Cursor.int(column: Int): Int = if (column < 0 || isNull(column)) 0 else getInt(column)
