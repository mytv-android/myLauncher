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
    private val initialized = HashSet<String>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle?) {
            refresh()
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
            if (discovered.all { it.packageName in initialized }) continue
            refresh()
        }
    }

    fun refresh() {
        scope.launch {
            val discovered = bridge.discover()
            _providers.value = discovered
            val ch = ArrayList<BridgeChannel>()
            val pr = ArrayList<BridgeProgram>()
            val wn = ArrayList<BridgeWatchNext>()
            for (provider in discovered) {
                val hello = bridge.hello(provider)
                val init = if (hello != null) bridge.init(provider) else null
                if (hello != null && init != null) initialized += provider.packageName
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
            val provider = bridge.discover().firstOrNull { it.packageName == packageName } ?: return@launch
            bridge.hello(provider)
            bridge.init(provider)
            refresh()
        }
    }

    fun approve(packageName: String, key: String) {
        // 新装应用的 Provider 可能还没被 discover 到；不能静默丢弃 approve 回传
        retryApprove(packageName, key, 6)
    }

    private fun retryApprove(packageName: String, key: String, attempts: Int) {
        val provider = _providers.value.firstOrNull { it.packageName == packageName }
        if (provider != null) {
            scope.launch { bridge.approve(provider, key) }
            return
        }
        if (attempts <= 0) return
        scope.launch {
            delay(500)
            retryApprove(packageName, key, attempts - 1)
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
