package com.github.mytv.data.series

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeriesRepository(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = SeriesBridge(context)
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    private val _providers = MutableStateFlow<List<SeriesProvider>>(emptyList())
    val providers: StateFlow<List<SeriesProvider>> = _providers.asStateFlow()
    private val _channels = MutableStateFlow<List<BridgeChannel>>(emptyList())
    val channels: StateFlow<List<BridgeChannel>> = _channels.asStateFlow()
    private val _previewPrograms = MutableStateFlow<List<BridgeProgram>>(emptyList())
    val previewPrograms: StateFlow<List<BridgeProgram>> = _previewPrograms.asStateFlow()
    private val _watchNext = MutableStateFlow<List<BridgeWatchNext>>(emptyList())
    val watchNext: StateFlow<List<BridgeWatchNext>> = _watchNext.asStateFlow()
    private val prefs = context.getSharedPreferences("series_state", Context.MODE_PRIVATE)
    private val initLock = Mutex()
    // Provider 还没被发现时先把 approve 记住，发现后补发（新装应用在慢设备上可能晚好几秒）
    private val pendingApprovals = java.util.Collections.synchronizedSet(HashSet<Pair<String, String>>())

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle?) {
            scope.launch {
                initLock.withLock { prefs.edit().remove("i|$packageName").apply() }
                refresh()
            }
        }

        override fun onPackageAdded(packageName: String, user: android.os.UserHandle?) {
            handlePackageChanged(packageName)
        }

        override fun onPackageChanged(packageName: String, user: android.os.UserHandle?) {
            handlePackageChanged(packageName)
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: android.os.UserHandle?,
            replacing: Boolean,
        ) {
            packageNames?.forEach(::handlePackageChanged)
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: android.os.UserHandle?,
            replacing: Boolean,
        ) {
            refresh()
        }
    }

    init {
        try {
            launcherApps?.registerCallback(callback)
        } catch (_: Exception) {
        }
        refresh()
        scope.launch { retryUninitialized() }
    }

    private suspend fun retryUninitialized() {
        while (true) {
            delay(3_000L)
            val discovered = bridge.discover()
            if (discovered.isEmpty()) continue
            if (ensureInitialized(discovered)) refresh()
        }
    }

    private fun installStamp(packageName: String): Long = try {
        context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        -1L
    }

    /** hello → init，每个安装版本只调一次（跨进程重启也生效）；init 失败时下一轮重试。 */
    private suspend fun ensureInitialized(discovered: List<SeriesProvider>): Boolean = initLock.withLock {
        var fresh = false
        for (provider in discovered) {
            val key = "i|${provider.packageName}"
            val stamp = installStamp(provider.packageName)
            if (stamp <= 0L || prefs.getLong(key, -1L) == stamp) continue
            // 先记下版本再调用：另一个实例并发初始化同一应用时不会重复 init
            prefs.edit().putLong(key, stamp).apply()
            if (bridge.hello(provider) == null || bridge.init(provider) == null) {
                prefs.edit().remove(key).apply()
                continue
            }
            fresh = true
        }
        fresh
    }

    fun refresh() {
        scope.launch {
            val discovered = bridge.discover()
            _providers.value = discovered
            flushPendingApprovals(discovered)
            ensureInitialized(discovered)
            val ch = ArrayList<BridgeChannel>()
            val pr = ArrayList<BridgeProgram>()
            val wn = ArrayList<BridgeWatchNext>()
            for (provider in discovered) {
                ch += bridge.channels(provider)
                pr += bridge.previewPrograms(provider)
                wn += bridge.watchNext(provider)
            }
            _channels.value = ch
            _previewPrograms.value = pr
            _watchNext.value = wn
        }
    }

    private fun handlePackageChanged(packageName: String) {
        if (!SeriesTrust.isTrusted(context, packageName)) return
        scope.launch {
            initLock.withLock {
                val key = "i|$packageName"
                val stamp = installStamp(packageName)
                if (stamp <= 0L || prefs.getLong(key, -1L) == stamp) return@withLock
                prefs.edit().putLong(key, stamp).apply()
                val provider = bridge.discover().firstOrNull { it.packageName == packageName }
                if (provider == null || bridge.hello(provider) == null || bridge.init(provider) == null) {
                    prefs.edit().remove(key).apply()
                }
            }
            refresh()
        }
    }

    fun approve(packageName: String, key: String) {
        // 新装应用的 Provider 可能还没被 discover 到；不能静默丢弃 approve 回传
        pendingApprovals.add(packageName to key)
        retryApprove(packageName, key, 20)
    }

    private fun retryApprove(packageName: String, key: String, attempts: Int) {
        val provider = _providers.value.firstOrNull { it.packageName == packageName }
        if (provider != null) {
            scope.launch {
                if (bridge.approve(provider, key) != null) pendingApprovals.remove(packageName to key)
            }
            return
        }
        if (attempts <= 0) return
        scope.launch {
            delay(500)
            retryApprove(packageName, key, attempts - 1)
        }
    }

    private fun flushPendingApprovals(discovered: List<SeriesProvider>) {
        val pending = synchronized(pendingApprovals) { pendingApprovals.toList() }
        for ((pkg, key) in pending) {
            val provider = discovered.firstOrNull { it.packageName == pkg } ?: continue
            scope.launch {
                if (bridge.approve(provider, key) != null) pendingApprovals.remove(pkg to key)
            }
        }
    }

    fun hide(packageName: String, key: String) {
        val provider = _providers.value.firstOrNull { it.packageName == packageName } ?: return
        scope.launch { bridge.hide(provider, key) }
    }

    fun providerFor(packageName: String): SeriesProvider? =
        _providers.value.firstOrNull { it.packageName == packageName }

    fun callerLabel(): String = Process.myUserHandle().toString()
}
