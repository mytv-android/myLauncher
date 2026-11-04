package com.github.mytv.feature.organizer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.github.mytv.data.files.NameResolution
import com.github.mytv.data.files.PathGuard
import com.github.mytv.data.files.PathResolution
import com.github.mytv.data.files.ResumableUpload
import com.github.mytv.data.files.UploadAppendResult
import com.github.mytv.data.files.UploadBeginResult
import com.github.mytv.data.files.UploadError
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone / PC transfer service (§14).
 *
 * Lifecycle: the launcher calls [onPageOpened] when the TV page is visible and one of
 * [onLeaveBackground], [onLeaveStop] or [onPageClosed] when it goes away. The server
 * binds only while the page is open. It listens on the selected LAN address and on
 * 127.0.0.1 (for adb forward); every request is rejected with 421 unless the Host
 * header equals `<address>:<port>`.
 *
 * Public API for the launcher:
 *  - [state]: everything the TV page and the debug state interface need;
 *  - [organizerJson], [storageJson], [installJson], [transfersJson];
 *  - [installManager] for the install card;
 *  - [decideConnection] for the `confirm_phone` overlay;
 *  - [disconnectSession], [cancelTransfer], [removeTransfer], [deleteTransfer],
 *    [openTransfer], [setTarget], [refreshStorage], [processPendingTransfers].
 */
class PhoneOrganizerServer(
    private val context: Context,
    private val callbacks: OrganizerCallbacks = OrganizerCallbacks.default(context),
    private val storage: OrganizerStorage = OrganizerStorage(context),
    private val lanProvider: LanAddressProvider = LanAddressProvider(context),
) {
    companion object {
        private const val TAG = "PhoneOrganizer"
        private const val MAX_TRANSFERS = 200
        private const val PAGE_SIZE = 500
        private const val TICKET_TTL_MS = 60L * 1000
        private const val MIN_PORT = 49152
        private const val MAX_PORT = 65535
        private const val MAX_WALLPAPER_BYTES = 20L * 1024 * 1024
        private const val MAX_ICON_BYTES = 5L * 1024 * 1024
        private const val PUSH_DIR = "push"
        private const val AMBIENT_DIR = "ambient/videos"
    }

    val installManager = InstallManager(context)
    private val pairing = PairingStateMachine()
    private val uploads = ResumableUpload(storage)
    private val pathGuard = PathGuard(
        privateDirs = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir),
    )
    private val assets = WebAssets(context)
    private val secureRandom = SecureRandom()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(OrganizerState())
    val state: StateFlow<OrganizerState> = _state.asStateFlow()

    /** Optional DynamicScheme CSS variables for the web client. */
    var themeCssProvider: () -> String = { "" }

    private val servers = ArrayList<NanoHTTPD>()
    private val transfers = LinkedHashMap<String, OrganizerTransfer>()
    private val uploadsById = HashMap<String, UploadContext>()
    private val tickets = HashMap<String, Ticket>()
    private val waiters = HashMap<String, Waiter>()
    private val activeUploadSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    private var lan: LanAddress? = null
    private var port = 0
    private var running = false
    private var pageVisible = false
    private var acceptingNew = true
    private var stopWhenIdle = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        scope.launch {
            installManager.state.collect { install -> onInstallState(install) }
        }
    }

    // ------------------------------------------------------------------ lifecycle
    fun onPageOpened() {
        pageVisible = true
        acceptingNew = true
        stopWhenIdle = false
        start()
        processPendingTransfers()
    }

    /** "后台继续": keep active transfers, refuse new sessions, stop when idle. */
    fun onLeaveBackground() {
        pageVisible = false
        acceptingNew = false
        stopWhenIdle = true
        publish()
        if (activeTransfers() == 0) stop()
    }

    /** "全部停止": abort transfers, delete part files and stop immediately. */
    fun onLeaveStop() {
        pageVisible = false
        stopNow()
    }

    /** BACK without running transfers. */
    fun onPageClosed() {
        pageVisible = false
        if (activeTransfers() == 0) stop() else {
            acceptingNew = false
            stopWhenIdle = true
            publish()
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val address = lanProvider.discover() ?: run {
            lan = null
            port = 0
            running = false
            publish()
            return false
        }
        val chosenPort = freePort(address.address) ?: run {
            lan = address
            port = 0
            running = false
            publish()
            return false
        }
        val lanServer = BoundHttpServer(address.address, chosenPort, context.cacheDir) { handle(it) }
        val loopServer = BoundHttpServer("127.0.0.1", chosenPort, context.cacheDir) { handle(it) }
        return try {
            lanServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            loopServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            servers += lanServer
            servers += loopServer
            lan = address
            port = chosenPort
            running = true
            acceptingNew = true
            pairing.rotateCredentials()
            registerNetworkCallback()
            refreshStorageInternal()
            publish()
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot start listener", e)
            try {
                lanServer.stop()
                loopServer.stop()
            } catch (_: Exception) {
            }
            lan = address
            port = 0
            running = false
            publish()
            false
        }
    }

    @Synchronized
    fun stop() {
        servers.forEach {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        servers.clear()
        unregisterNetworkCallback()
        running = false
        lan = null
        port = 0
        abortWaiters()
        pairing.disconnectAll()
        uploadsById.clear()
        activeUploadSessions.clear()
        publish()
    }

    fun stopNow() {
        val snapshot = synchronized(lock) { uploadsById.values.toList() }
        for (upload in snapshot) {
            uploads.cancel(upload.id)
            updatesTransfer(upload.transferId) {
                it.copy(state = TransferState.CANCELLED, result = TransferResult.CANCELLED)
            }
        }
        synchronized(lock) { uploadsById.clear() }
        installManager.cancel()
        tickets.clear()
        stop()
    }

    // ------------------------------------------------------------------ public state
    fun organizerJson(): JSONObject {
        val snapshot = _state.value
        if (!snapshot.running) return JSONObject().put("running", false)
        val sessions = JSONArray()
        for (session in snapshot.sessions) {
            sessions.put(
                JSONObject()
                    .put("id", session.id)
                    .put("device", session.device)
                    .put("approved", session.approved),
            )
        }
        return JSONObject()
            .put("running", true)
            .put("address", snapshot.address ?: "")
            .put("port", snapshot.port)
            .put("token", snapshot.token)
            .put("code", snapshot.code)
            .put("sessions", sessions)
            .put("transfers", transfersJson())
            .put(
                "target",
                JSONObject()
                    .put("root", snapshot.target.root)
                    .put("path", snapshot.target.path),
            )
    }

    fun storageJson(): JSONObject {
        val storageState = _state.value.storage
        val roots = JSONArray()
        for (root in storageState.roots) {
            roots.put(
                JSONObject()
                    .put("id", root.id)
                    .put("label", root.label)
                    .put("total", root.total)
                    .put("free", root.free)
                    .put("writable", root.writable)
                    .put("fs", root.fileSystem)
                    .put("restricted", root.restricted),
            )
        }
        return JSONObject()
            .put("allFiles", storageState.allFiles ?: JSONObject.NULL)
            .put("legacy", storageState.legacy ?: JSONObject.NULL)
            .put("writePermission", storageState.writePermission ?: JSONObject.NULL)
            .put("roots", roots)
    }

    fun installJson(): JSONObject {
        val install = installManager.state.value
        return JSONObject()
            .put("state", install.state)
            .put("pkg", install.packageName ?: JSONObject.NULL)
            .put("versionCode", install.versionCode)
            .put("error", install.error ?: JSONObject.NULL)
    }

    fun transfersJson(): JSONArray {
        val array = JSONArray()
        val snapshot = synchronized(transfers) {
            transfers.values.sortedByDescending { it.createdAt }
        }
        for (transfer in snapshot) array.put(transferJson(transfer))
        return array
    }

    fun activeTransferCount(): Int = activeTransfers()

    /** System page for "all files access" / the legacy storage permission. */
    fun storageSettingsIntent(): Intent? = storage.storageSettingsIntent()

    /** Subfolders for the save-location drawer. */
    fun listDirectories(root: String, path: String): List<String> {
        val rootFile = storage.resolveRoot(root, callbacks.restrictedMode()) ?: return emptyList()
        val resolved = when (val result = pathGuard.resolve(rootFile, path, decode = false)) {
            is PathResolution.Ok -> result.file
            else -> return emptyList()
        }
        return resolved.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun refreshStorage() {
        refreshStorageInternal()
        publish()
    }

    fun setTarget(root: String, path: String): Boolean {
        val rootFile = storage.resolveRoot(root, callbacks.restrictedMode()) ?: return false
        val relative = path.trim('/').replace('\\', '/')
        val target = if (relative.isEmpty()) rootFile else File(rootFile, relative)
        if (!target.isDirectory && !target.mkdirs()) return false
        if (!target.canWrite()) return false
        storage.setTarget(SaveTarget(root, relative))
        publish()
        return true
    }

    fun decideConnection(allow: Boolean): Boolean {
        val pending = pairing.pendingRequests().firstOrNull() ?: return false
        val resolution = pairing.decide(pending.id, allow)
        val waiter = synchronized(lock) { waiters.remove(pending.id) }
        waiter?.let {
            it.resolution = resolution
            it.latch.countDown()
        }
        publish()
        return resolution !is PairingResolution.Unknown
    }

    fun disconnectSession(sessionId: String): Boolean {
        val removed = pairing.disconnect(sessionId)
        if (removed) publish()
        return removed
    }

    fun cancelTransfer(id: String): Boolean {
        val upload = synchronized(lock) {
            uploadsById.values.firstOrNull { it.transferId == id }
        }
        if (upload != null) {
            uploads.cancel(upload.id)
            synchronized(lock) { uploadsById.remove(upload.id) }
            endUploadSession(upload)
        }
        val transfer = getTransfer(id) ?: return upload != null
        updatesTransfer(id) {
            it.copy(state = TransferState.CANCELLED, result = TransferResult.CANCELLED)
        }
        return true
    }

    fun removeTransfer(id: String): Boolean {
        val removed = synchronized(transfers) { transfers.remove(id) } != null
        if (removed) publish()
        return removed
    }

    fun deleteTransfer(id: String): Boolean {
        val transfer = getTransfer(id) ?: return false
        transfer.filePath?.let { path ->
            try {
                File(path).deleteRecursively()
            } catch (_: Exception) {
            }
        }
        updatesTransfer(id) {
            it.copy(state = TransferState.DONE, result = TransferResult.CANCELLED, filePath = null, error = null)
        }
        return true
    }

    fun openTransfer(id: String): Boolean {
        val transfer = getTransfer(id) ?: return false
        return when (transfer.action) {
            TransferAction.INSTALL -> {
                if (transfer.filePath == null) return false
                installManager.prepare(File(transfer.filePath), deleteOnSuccess = true)
                updatesTransfer(id) { it.copy(state = TransferState.PENDING) }
                publish()
                true
            }
            TransferAction.VIEW, TransferAction.PLAY -> {
                val file = transfer.filePath?.let(::File) ?: return false
                callbacks.onOpenFile(file, transfer.mime ?: mimeOfName(transfer.name), transfer.action)
                updatesTransfer(id) {
                    it.copy(
                        state = TransferState.DONE,
                        result = if (transfer.action == TransferAction.PLAY) {
                            TransferResult.PLAYED
                        } else {
                            TransferResult.SHOWN
                        },
                    )
                }
                true
            }
            TransferAction.NONE -> false
        }
    }

    fun processPendingTransfers() {
        val pending = synchronized(transfers) {
            transfers.values.filter { it.state == TransferState.PENDING }.sortedByDescending { it.createdAt }
        }
        for (transfer in pending) {
            when (transfer.action) {
                TransferAction.INSTALL -> openTransfer(transfer.id)
                TransferAction.VIEW, TransferAction.PLAY -> openTransfer(transfer.id)
                TransferAction.NONE -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ HTTP entry
    private fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val address = lan
            if (!running || address == null) {
                return HttpSupport.error(503, "not running")
            }
            val host = session.headers["host"]
            if (!PairingStateMachine.hostValid(host, address.address, port)) {
                return HttpSupport.error(421, "bad host")
            }
            val uri = session.uri
            val method = session.method
            when {
                method == NanoHTTPD.Method.GET && uri == "/" ->
                    HttpSupport.fixed(200, HttpSupport.HTML_MIME, assets.index(themeCssProvider()))
                method == NanoHTTPD.Method.GET && uri == "/app.js" ->
                    assetResponse("web/app.js")
                method == NanoHTTPD.Method.GET && uri == "/app.css" ->
                    assetResponse("web/app.css")
                method == NanoHTTPD.Method.GET && uri == "/icons.svg" ->
                    assetResponse("web/icons.svg")
                method == NanoHTTPD.Method.GET && uri.startsWith("/dl/") ->
                    handleDownload(session, uri)
                method == NanoHTTPD.Method.POST && uri == "/api/session" ->
                    handleNewSession(session)
                !uri.startsWith("/api/") ->
                    HttpSupport.error(404, "not found")
                else -> {
                    val sessionId = session.headers["x-session"]
                    if (sessionId.isNullOrEmpty() || pairing.session(sessionId) == null) {
                        HttpSupport.error(401, "no session")
                    } else {
                        pairing.touch(sessionId)
                        routeApi(session, uri, method, sessionId)
                    }
                }
            }
        } catch (e: HttpSupport.HttpError) {
            val message = e.body.ifBlank { "error" }
            HttpSupport.error(e.code, message, e.headers)
        } catch (e: Exception) {
            Log.e(TAG, "request failed", e)
            HttpSupport.error(500, "internal error")
        }
    }

    private fun assetResponse(path: String): NanoHTTPD.Response {
        val bytes = assets.read(path) ?: return HttpSupport.error(404, "not found")
        return HttpSupport.fixed(200, assets.mimeOf(path), bytes, mapOf("Cache-Control" to "no-store"))
    }

    private fun routeApi(
        session: NanoHTTPD.IHTTPSession,
        uri: String,
        method: NanoHTTPD.Method,
        sessionId: String,
    ): NanoHTTPD.Response = when {
        uri == "/api/apps" && method == NanoHTTPD.Method.GET -> HttpSupport.json(200, appsJson())
        uri == "/api/apps/label" && method == NanoHTTPD.Method.POST -> renameApp(session)
        uri == "/api/diag" && method == NanoHTTPD.Method.GET ->
            HttpSupport.fixed(200, HttpSupport.TEXT_MIME, redact(callbacks.diagnostics()))
        uri == "/api/fs/roots" && method == NanoHTTPD.Method.GET -> HttpSupport.json(200, rootsJson())
        uri == "/api/fs/list" && method == NanoHTTPD.Method.GET -> listFiles(session)
        uri == "/api/fs/mkdir" && method == NanoHTTPD.Method.POST -> mkdir(session)
        uri == "/api/fs/rename" && method == NanoHTTPD.Method.POST -> rename(session)
        uri == "/api/fs/move" && method == NanoHTTPD.Method.POST -> move(session)
        uri == "/api/fs/delete" && method == NanoHTTPD.Method.POST -> delete(session)
        uri == "/api/fs/uploads" && method == NanoHTTPD.Method.POST -> beginUpload(session, sessionId)
        uri.startsWith("/api/fs/uploads/") && method == NanoHTTPD.Method.PUT ->
            appendUpload(session, uri, sessionId)
        uri.startsWith("/api/fs/uploads/") && method == NanoHTTPD.Method.HEAD ->
            headUpload(uri)
        uri.startsWith("/api/fs/uploads/") && method == NanoHTTPD.Method.DELETE ->
            deleteUpload(uri)
        uri == "/api/fs/ticket" && method == NanoHTTPD.Method.POST -> createTicket(session, sessionId)
        uri == "/api/open" && method == NanoHTTPD.Method.POST -> openExisting(session, sessionId)
        uri == "/api/transfers" && method == NanoHTTPD.Method.GET -> HttpSupport.json(200, transfersJson())
        uri == "/api/install" && method == NanoHTTPD.Method.GET -> HttpSupport.json(200, installJson())
        uri == "/api/clips" && method == NanoHTTPD.Method.POST -> uploadClip(session, sessionId)
        uri == "/api/desktop" && method == NanoHTTPD.Method.GET -> desktopJson()
        uri == "/api/desktop/weather" && method == NanoHTTPD.Method.GET ->
            HttpSupport.json(200, JSONObject().put("city", OrganizerPrefs.weatherCity(context)))
        uri == "/api/desktop/weather" && method == NanoHTTPD.Method.POST -> setWeatherCity(session)
        uri == "/api/desktop/wallpaper" && method == NanoHTTPD.Method.POST -> uploadWallpaper(session)
        uri == "/api/desktop/icon" && method == NanoHTTPD.Method.POST -> uploadIcon(session)
        uri == "/api/desktop/widgets" && method == NanoHTTPD.Method.GET ->
            HttpSupport.json(200, JSONObject().put("command", context.getString(R.string.organizer_grant_command)))
        uri == "/api/session" && method == NanoHTTPD.Method.DELETE -> {
            pairing.disconnect(sessionId)
            publish()
            HttpSupport.json(200, JSONObject().put("ok", true))
        }
        uri == "/api/fs/thumb" -> HttpSupport.error(404, "not found")
        else -> HttpSupport.error(404, "not found")
    }

    // ------------------------------------------------------------------ pairing
    private fun handleNewSession(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!acceptingNew || stopWhenIdle) {
            return HttpSupport.error(503, "not accepting")
        }
        val token = session.headers["x-token"]
        val code = session.headers["x-code"]
        val kind: CredentialKind
        val credential: String
        when {
            !token.isNullOrBlank() -> {
                kind = CredentialKind.TOKEN
                credential = token.trim()
            }
            !code.isNullOrBlank() -> {
                kind = CredentialKind.CODE
                credential = code.filter { it.isDigit() }
            }
            else -> return HttpSupport.error(401, "credential required")
        }
        val source = session.remoteIpAddress ?: "unknown"
        val device = session.headers["x-device"]?.takeIf { it.isNotBlank() }
            ?: deviceFromUserAgent(session.headers["user-agent"])
        return when (val result = pairing.request(kind, credential, source, device)) {
            is PairingRequestResult.Unauthorized -> HttpSupport.error(401, "invalid credential")
            is PairingRequestResult.Blocked -> {
                val seconds = ((result.retryAfterMs + 999) / 1000).coerceAtLeast(1)
                HttpSupport.error(429, "too many attempts", mapOf("Retry-After" to seconds.toString()))
            }
            is PairingRequestResult.Pending -> {
                publish()
                val waiter = Waiter()
                synchronized(lock) { waiters[result.request.id] = waiter }
                val decided = try {
                    waiter.latch.await(PairingStateMachine.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    false
                }
                synchronized(lock) { waiters.remove(result.request.id) }
                if (!decided) {
                    pairing.cancelPending(result.request.id)
                    publish()
                    return HttpSupport.error(408, "request timeout")
                }
                when (val resolution = waiter.resolution) {
                    is PairingResolution.Allowed -> HttpSupport.json(
                        200,
                        JSONObject()
                            .put("session", resolution.session.id)
                            .put("expiresInS", 600),
                    )
                    is PairingResolution.Denied -> HttpSupport.error(403, "denied")
                    is PairingResolution.LimitReached -> HttpSupport.error(503, "too many sessions")
                    else -> HttpSupport.error(503, "stopped")
                }
            }
        }
    }

    // ------------------------------------------------------------------ apps
    private fun appsJson(): JSONArray {
        val seen = HashSet<String>()
        val out = ArrayList<Triple<String, String, String>>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        for (intent in intents) {
            val resolved = try {
                context.packageManager.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                emptyList()
            }
            for (info in resolved) {
                val activity = info.activityInfo ?: continue
                val component = "${activity.packageName}/${activity.name}"
                if (!seen.add(component)) continue
                val label = OrganizerPrefs.appLabel(context, component)
                    ?: activity.loadLabel(context.packageManager).toString()
                out += Triple(component, label, activity.packageName)
            }
        }
        out.sortBy { it.second.lowercase(Locale.ROOT) }
        val array = JSONArray()
        for ((component, label) in out) {
            array.put(JSONObject().put("component", component).put("label", label))
        }
        return array
    }

    private fun renameApp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val component = body.optString("component")
        val label = body.optString("label").trim()
        if (component.isEmpty() || "/" !in component) return HttpSupport.error(400, "bad component")
        if (label.isEmpty() || label.length > 255) return HttpSupport.error(400, "bad label")
        callbacks.onAppLabelChanged(component, label)
        OrganizerPrefs.setAppLabel(context, component, label)
        return HttpSupport.json(
            200,
            JSONObject().put("component", component).put("label", label),
        )
    }

    // ------------------------------------------------------------------ files
    private fun rootsJson(): JSONArray {
        val restricted = callbacks.restrictedMode()
        val snapshot = storage.snapshot(restricted)
        val array = JSONArray()
        for (root in snapshot.roots) {
            array.put(
                JSONObject()
                    .put("id", root.id)
                    .put("label", root.label)
                    .put("total", root.total)
                    .put("free", root.free)
                    .put("writable", root.writable)
                    .put("fs", root.fileSystem)
                    .put("restricted", root.restricted),
            )
        }
        return array
    }

    private fun listFiles(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val rootId = HttpSupport.queryParameter(session, "root") ?: ""
        val path = HttpSupport.queryParameter(session, "path") ?: ""
        val showHidden = HttpSupport.queryParameter(session, "hidden") in setOf("1", "true")
        val cursor = HttpSupport.queryParameter(session, "cursor")?.toIntOrNull() ?: 0
        val dir = resolveDirectory(rootId, path, decode = false)
        if (!dir.isDirectory) throw HttpSupport.HttpError(404, "not found")
        val children = dir.listFiles()?.toList().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase(Locale.ROOT) }))
        val page = children.drop(cursor).take(PAGE_SIZE)
        val entries = JSONArray()
        for (file in page) {
            entries.put(
                JSONObject()
                    .put("name", file.name)
                    .put("dir", file.isDirectory)
                    .put("size", if (file.isDirectory) 0 else file.length())
                    .put("mtime", file.lastModified())
                    .put("mime", if (file.isDirectory) "inode/directory" else mimeOfName(file.name)),
            )
        }
        val next = cursor + page.size
        return HttpSupport.json(
            200,
            JSONObject()
                .put("path", path)
                .put("entries", entries)
                .put("cursor", if (next < children.size) next.toString() else JSONObject.NULL),
        )
    }

    private fun mkdir(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val dir = resolveDirectory(body.optString("root"), body.optString("path"), decode = true)
        ensureWritable(dir)
        val name = sanitizeName(body.optString("name"), dir)
        val target = File(dir, name)
        if (target.exists()) return HttpSupport.error(409, "exists")
        if (!target.mkdir()) return HttpSupport.error(500, "mkdir failed")
        return HttpSupport.json(200, JSONObject().put("ok", true))
    }

    private fun rename(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val source = resolveDirectory(body.optString("root"), body.optString("path"), decode = true)
        if (!source.exists()) return HttpSupport.error(404, "not found")
        val parent = source.parentFile ?: return HttpSupport.error(400, "bad path")
        ensureWritable(parent)
        val name = sanitizeName(body.optString("name"), parent)
        val target = File(parent, name)
        if (target.exists()) return HttpSupport.error(409, "exists")
        if (!source.renameTo(target)) return HttpSupport.error(500, "rename failed")
        return HttpSupport.json(200, JSONObject().put("ok", true))
    }

    private fun move(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val rootId = body.optString("root")
        val targetDir = resolveDirectory(rootId, body.optString("to"), decode = true)
        ensureWritable(targetDir)
        val paths = body.optJSONArray("paths") ?: JSONArray()
        if (paths.length() > 1000) return HttpSupport.error(400, "too many items")
        var moved = 0
        for (index in 0 until paths.length()) {
            val source = resolveDirectory(rootId, paths.optString(index), decode = true)
            if (!source.exists()) continue
            val target = File(targetDir, source.name)
            if (target.exists()) continue
            if (source.renameTo(target)) moved++
        }
        return HttpSupport.json(200, JSONObject().put("moved", moved))
    }

    private fun delete(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val rootId = body.optString("root")
        val paths = body.optJSONArray("paths") ?: JSONArray()
        if (paths.length() > 1000) return HttpSupport.error(400, "too many items")
        val results = JSONArray()
        for (index in 0 until paths.length()) {
            val path = paths.optString(index)
            var ok = false
            var error: String? = null
            try {
                val file = resolveDirectory(rootId, path, decode = true)
                if (!file.exists()) {
                    error = "not found"
                } else {
                    ensureWritable(file.parentFile ?: file)
                    ok = file.deleteRecursively()
                    if (!ok) error = "delete failed"
                }
            } catch (e: HttpSupport.HttpError) {
                error = "not found"
            }
            results.put(
                JSONObject()
                    .put("path", path)
                    .put("ok", ok)
                    .put("error", error ?: JSONObject.NULL),
            )
        }
        return HttpSupport.json(200, JSONObject().put("results", results))
    }

    private fun resolveDirectory(rootId: String, path: String, decode: Boolean): File {
        val root = storage.resolveRoot(rootId, callbacks.restrictedMode())
            ?: throw HttpSupport.HttpError(404, "unknown root")
        return when (val resolved = pathGuard.resolve(root, path, decode = decode)) {
            is PathResolution.Ok -> resolved.file
            is PathResolution.BadRequest -> throw HttpSupport.HttpError(400, "bad path")
            is PathResolution.NotFound -> throw HttpSupport.HttpError(404, "not found")
        }
    }

    private fun ensureWritable(dir: File) {
        if (!dir.canWrite()) throw HttpSupport.HttpError(403, "read only")
    }

    private fun sanitizeName(raw: String, dir: File): String {
        val kind = storage.fileSystemKindOf(dir)
        return when (val result = pathGuard.sanitizeName(raw, kind)) {
            is NameResolution.Ok -> result.name
            is NameResolution.Rejected -> throw HttpSupport.HttpError(400, "bad name")
        }
    }

    // ------------------------------------------------------------------ upload
    private data class UploadContext(
        val id: String,
        val sessionId: String,
        val transferId: String,
        val size: Long,
        val dir: File,
        val name: String,
        val push: Boolean,
        val keep: Boolean,
        val action: TransferAction,
        val mime: String,
    )

    private fun beginUpload(
        session: NanoHTTPD.IHTTPSession,
        sessionId: String,
    ): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val size = body.optLong("size", -1)
        if (size < 0) return HttpSupport.error(400, "bad size")
        UploadLimits.checkSingle(size).error?.let { return uploadLimitError(it) }
        val rawName = body.optString("name")
        val modified = body.optLong("mtime", 0L).takeIf { it > 0 }
        val push = body.optString("target") == "push"
        val pushAction = if (push) actionForName(rawName) else TransferAction.NONE
        if (push && pushAction == TransferAction.NONE) {
            return HttpSupport.error(415, context.getString(R.string.organizer_unsupported))
        }

        val dir: File
        val keep: Boolean
        if (push) {
            keep = body.optBoolean("keep", false)
            dir = if (keep) {
                storage.targetDirectory() ?: return HttpSupport.error(500, "no target")
            } else {
                File(context.cacheDir, PUSH_DIR).apply { mkdirs() }
            }
            val used = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
            val volume = storage.volumeOf(dir)
            val decision = UploadLimits.checkTotal(volume.totalBytes - volume.freeBytes, volume.freeBytes, size, used)
            decision.error?.let { return uploadLimitError(it) }
            if (!keep) trimPushArea(dir, size)
        } else {
            keep = true
            val rootDir = resolveDirectory(body.optString("root"), body.optString("path"), decode = true)
            if (!rootDir.isDirectory) return HttpSupport.error(404, "not found")
            ensureWritable(rootDir)
            dir = rootDir
            val volume = storage.volumeOf(dir)
            val decision = UploadLimits.checkTotal(
                volume.totalBytes - volume.freeBytes,
                volume.freeBytes,
                size,
            )
            decision.error?.let { return uploadLimitError(it) }
        }

        var name = sanitizeName(rawName, dir)
        val conflict = body.optString("conflict", "rename")
        if (!push) {
            val existing = File(dir, name)
            if (existing.exists()) {
                when (conflict) {
                    "skip" -> return HttpSupport.error(409, "exists")
                    "overwrite" -> {
                        if (existing.isDirectory) return HttpSupport.error(409, "exists")
                    }
                    else -> name = uniqueName(dir, name)
                }
            }
        } else {
            if (File(dir, name).exists()) name = uniqueName(dir, name)
        }

        val begin = uploads.begin(dir, name, size, modified)
        val created = when (begin) {
            is UploadBeginResult.Created -> begin
            is UploadBeginResult.Rejected -> return uploadLimitError(begin.error)
        }
        val mime = mimeOfName(created.name)
        val action = if (push) actionForName(created.name) else TransferAction.NONE
        val transfer = OrganizerTransfer(
            id = UUID.randomUUID().toString(),
            kind = if (push) TransferKind.PUSH else TransferKind.UPLOAD,
            name = created.name,
            total = size,
            state = TransferState.QUEUED,
            action = action,
            sessionId = sessionId,
            mime = mime,
        )
        addTransfer(transfer)
        val upload = UploadContext(
            id = created.id,
            sessionId = sessionId,
            transferId = transfer.id,
            size = size,
            dir = dir,
            name = created.name,
            push = push,
            keep = keep,
            action = action,
            mime = mime,
        )
        synchronized(lock) { uploadsById[created.id] = upload }
        return HttpSupport.json(
            201,
            JSONObject()
                .put("id", created.id)
                .put("offset", 0)
                .put("name", created.name),
        )
    }

    private fun appendUpload(
        session: NanoHTTPD.IHTTPSession,
        uri: String,
        sessionId: String,
    ): NanoHTTPD.Response {
        val uploadId = uri.substringAfterLast('/')
        val upload = synchronized(lock) { uploadsById[uploadId] }
            ?: return HttpSupport.error(404, "unknown upload")
        if (upload.sessionId != sessionId) return HttpSupport.error(403, "not yours")
        val offset = session.headers["upload-offset"]?.toLongOrNull()
            ?: return HttpSupport.error(400, "missing offset")
        val declared = HttpSupport.contentLength(session)
            ?: return HttpSupport.error(411, "length required")
        if (declared > upload.size) {
            uploads.cancel(upload.id)
            synchronized(lock) { uploadsById.remove(upload.id) }
            updatesTransfer(upload.transferId) {
                it.copy(state = TransferState.FAILED, error = context.getString(R.string.organizer_no_space))
            }
            endUploadSession(upload)
            return HttpSupport.error(413, "too large")
        }
        if (activeUploadSessions.add(upload.id)) {
            pairing.beginTransfer(sessionId)
        }
        updatesTransfer(upload.transferId) { it.copy(state = TransferState.ACTIVE, bytes = offset) }
        val result = try {
            uploads.append(upload.id, offset, session.inputStream, declared)
        } catch (e: IOException) {
            UploadAppendResult.Failed(e.message ?: "io", offset)
        }
        return when (result) {
            is UploadAppendResult.Written -> {
                updatesTransfer(upload.transferId) {
                    it.copy(bytes = result.offset, state = if (result.complete) TransferState.DONE else TransferState.ACTIVE)
                }
                if (result.complete) {
                    endUploadSession(upload)
                    synchronized(lock) { uploadsById.remove(upload.id) }
                    val file = result.path?.let(::File)
                    if (upload.push) {
                        if (file != null) {
                            finishPush(upload, file)
                        }
                        HttpSupport.json(
                            200,
                            JSONObject()
                                .put("offset", result.offset)
                                .put("push", upload.transferId),
                        )
                    } else {
                        HttpSupport.json(
                            200,
                            JSONObject()
                                .put("offset", result.offset)
                                .put("path", result.path ?: ""),
                        )
                    }
                } else {
                    HttpSupport.json(200, JSONObject().put("offset", result.offset))
                }
            }
            is UploadAppendResult.Conflict ->
                HttpSupport.json(409, JSONObject().put("offset", result.actualOffset))
            is UploadAppendResult.OutOfSpace -> {
                synchronized(lock) { uploadsById.remove(upload.id) }
                updatesTransfer(upload.transferId) {
                    it.copy(state = TransferState.FAILED, error = context.getString(R.string.organizer_no_space))
                }
                endUploadSession(upload)
                HttpSupport.error(507, context.getString(R.string.organizer_no_space))
            }
            is UploadAppendResult.UnknownSession -> HttpSupport.error(404, "unknown upload")
            is UploadAppendResult.Failed -> {
                uploads.cancel(upload.id)
                synchronized(lock) { uploadsById.remove(upload.id) }
                updatesTransfer(upload.transferId) {
                    it.copy(state = TransferState.FAILED, error = result.message)
                }
                endUploadSession(upload)
                if (result.message.contains("longer than declared")) {
                    HttpSupport.error(413, "too large")
                } else {
                    HttpSupport.error(400, "write failed")
                }
            }
        }
    }

    private fun headUpload(uri: String): NanoHTTPD.Response {
        val uploadId = uri.substringAfterLast('/')
        val offset = uploads.offset(uploadId) ?: return HttpSupport.error(404, "unknown upload")
        return HttpSupport.fixed(
            200,
            HttpSupport.TEXT_MIME,
            "",
            mapOf("Upload-Offset" to offset.toString()),
        )
    }

    private fun deleteUpload(uri: String): NanoHTTPD.Response {
        val uploadId = uri.substringAfterLast('/')
        val upload = synchronized(lock) { uploadsById.remove(uploadId) }
        uploads.cancel(uploadId)
        upload?.let {
            updatesTransfer(it.transferId) {
                transfer -> transfer.copy(state = TransferState.CANCELLED, result = TransferResult.CANCELLED)
            }
            endUploadSession(it)
        }
        return HttpSupport.json(200, JSONObject().put("ok", true))
    }

    private fun endUploadSession(upload: UploadContext) {
        if (activeUploadSessions.remove(upload.id)) {
            pairing.endTransfer(upload.sessionId)
        }
        if (stopWhenIdle && activeTransfers() == 0) stop()
    }

    private fun finishPush(upload: UploadContext, file: File) {
        val action = upload.action
        if (!pageVisible) {
            updatesTransfer(upload.transferId) {
                it.copy(state = TransferState.PENDING, action = action, filePath = file.absolutePath)
            }
            return
        }
        when (action) {
            TransferAction.INSTALL -> {
                installManager.prepare(file, deleteOnSuccess = true)
                updatesTransfer(upload.transferId) {
                    it.copy(state = TransferState.PENDING, action = action, filePath = file.absolutePath)
                }
            }
            TransferAction.VIEW, TransferAction.PLAY -> {
                callbacks.onOpenFile(file, upload.mime, action)
                updatesTransfer(upload.transferId) {
                    it.copy(
                        state = TransferState.DONE,
                        action = action,
                        filePath = file.absolutePath,
                        result = if (action == TransferAction.PLAY) TransferResult.PLAYED else TransferResult.SHOWN,
                    )
                }
            }
            TransferAction.NONE -> Unit
        }
    }

    private fun trimPushArea(dir: File, incoming: Long) {
        val maxBytes = 4L * 1024 * 1024 * 1024
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total + incoming <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun uploadLimitError(error: UploadError): NanoHTTPD.Response = when (error) {
        UploadError.TOO_LARGE -> HttpSupport.error(413, context.getString(R.string.organizer_fat_limit))
        UploadError.NO_SPACE -> HttpSupport.error(413, context.getString(R.string.organizer_no_space))
        UploadError.NOT_WRITABLE -> HttpSupport.error(403, context.getString(R.string.organizer_read_only))
        UploadError.INVALID_NAME -> HttpSupport.error(400, "bad name")
    }

    private fun uploadLimitError(error: UploadLimitError): NanoHTTPD.Response = when (error) {
        UploadLimitError.TOO_LARGE -> HttpSupport.error(413, context.getString(R.string.organizer_fat_limit))
        UploadLimitError.TOTAL_LIMIT, UploadLimitError.FREE_SPACE ->
            HttpSupport.error(413, context.getString(R.string.organizer_no_space))
        UploadLimitError.TOO_MANY_SEGMENTS ->
            HttpSupport.error(413, context.getString(R.string.organizer_no_space))
        UploadLimitError.NO_VIDEO_TRACK ->
            HttpSupport.error(415, context.getString(R.string.organizer_clip_invalid))
    }

    // ------------------------------------------------------------------ personal videos
    private fun uploadClip(session: NanoHTTPD.IHTTPSession, sessionId: String): NanoHTTPD.Response {
        val declared = HttpSupport.contentLength(session)
            ?: return HttpSupport.error(411, "length required")
        UploadLimits.checkContentLength(declared).error?.let { return uploadLimitError(it) }
        val dir = File(context.filesDir, AMBIENT_DIR).apply { mkdirs() }
        if (!dir.isDirectory) return HttpSupport.error(500, "no directory")
        val existing = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(ResumableUpload.PART_SUFFIX) } ?: emptyList()
        UploadLimits.checkSegments(existing.size).error?.let { return uploadLimitError(it) }
        val volume = storage.volumeOf(dir)
        val used = volume.totalBytes - volume.freeBytes
        UploadLimits.checkTotal(used, volume.freeBytes, declared).error?.let { return uploadLimitError(it) }

        val rawName = session.headers["x-filename"]?.takeIf { it.isNotBlank() } ?: "video.mp4"
        val safeName = when (val sanitized = pathGuard.sanitizeName(rawName, storage.fileSystemKindOf(dir))) {
            is NameResolution.Ok -> sanitized.name
            is NameResolution.Rejected -> return HttpSupport.error(400, "bad name")
        }
        val temp = File(dir, ".${UUID.randomUUID()}.part")
        var written = 0L
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (written < declared) {
                    val want = minOf(buffer.size.toLong(), declared - written).toInt()
                    val read = session.inputStream.read(buffer, 0, want)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
                output.flush()
            }
        } catch (e: Exception) {
            temp.delete()
            return HttpSupport.error(500, "write failed")
        }
        if (written != declared) {
            temp.delete()
            return HttpSupport.error(400, "incomplete body")
        }
        if (!VideoProbe.hasVideoTrack(temp)) {
            temp.delete()
            return HttpSupport.error(415, context.getString(R.string.organizer_clip_invalid))
        }
        val finalFile = uniqueFile(dir, safeName)
        if (!temp.renameTo(finalFile)) {
            temp.delete()
            return HttpSupport.error(500, "rename failed")
        }
        val transfer = OrganizerTransfer(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.UPLOAD,
            name = finalFile.name,
            bytes = written,
            total = written,
            state = TransferState.DONE,
            sessionId = sessionId,
            filePath = finalFile.absolutePath,
            mime = mimeOfName(finalFile.name),
        )
        addTransfer(transfer)
        callbacks.onClipUploaded(finalFile)
        return HttpSupport.json(200, JSONObject().put("id", finalFile.name))
    }

    // ------------------------------------------------------------------ tickets & download
    private data class Ticket(
        val id: String,
        val files: List<File>,
        val name: String,
        val sessionId: String,
        val expiresAt: Long,
    )

    private fun createTicket(session: NanoHTTPD.IHTTPSession, sessionId: String): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val rootId = body.optString("root")
        val paths = body.optJSONArray("paths") ?: JSONArray()
        if (paths.length() == 0) return HttpSupport.error(400, "no paths")
        val files = ArrayList<File>()
        for (index in 0 until paths.length()) {
            files += resolveDirectory(rootId, paths.optString(index), decode = true)
        }
        if (files.any { !it.exists() }) return HttpSupport.error(404, "not found")
        val name = if (files.size == 1) files[0].name else "files.zip"
        val ticket = Ticket(
            id = UUID.randomUUID().toString(),
            files = files,
            name = name,
            sessionId = sessionId,
            expiresAt = System.currentTimeMillis() + TICKET_TTL_MS,
        )
        synchronized(lock) { tickets[ticket.id] = ticket }
        return HttpSupport.json(200, JSONObject().put("url", "/dl/${ticket.id}/$name"))
    }

    private fun handleDownload(
        session: NanoHTTPD.IHTTPSession,
        uri: String,
    ): NanoHTTPD.Response {
        val rest = uri.removePrefix("/dl/")
        val ticketId = rest.substringBefore('/')
        val ticket = synchronized(lock) { tickets.remove(ticketId) }
            ?: return HttpSupport.error(404, "not found")
        if (ticket.expiresAt < System.currentTimeMillis()) return HttpSupport.error(404, "expired")

        if (ticket.files.size == 1 && ticket.files[0].isFile) {
            pairing.beginTransfer(ticket.sessionId)
            return downloadFile(session, ticket.files[0], ticket.sessionId)
        }
        val entries = ArrayList<Pair<File, String>>()
        for (file in ticket.files) {
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.forEach {
                    val relative = it.relativeTo(file.parentFile ?: file).path.replace('\\', '/')
                    entries += it to relative
                }
            } else {
                entries += file to file.name
            }
        }
        if (entries.isEmpty()) return HttpSupport.error(404, "not found")
        pairing.beginTransfer(ticket.sessionId)
        val transfer = OrganizerTransfer(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.DOWNLOAD,
            name = ticket.name,
            total = entries.sumOf { it.first.length() },
            state = TransferState.ACTIVE,
        )
        addTransfer(transfer)
        val input = zipStream(entries) {
            updatesTransfer(transfer.id) {
                it.copy(state = TransferState.DONE, bytes = it.total)
            }
            pairing.endTransfer(ticket.sessionId)
        }
        return HttpSupport.chunked(200, "application/zip", input, mapOf(
            "Content-Disposition" to "attachment; filename=\"${ticket.name}\"",
        ))
    }

    private fun downloadFile(
        session: NanoHTTPD.IHTTPSession,
        file: File,
        sessionId: String,
    ): NanoHTTPD.Response {
        val length = file.length()
        val range = parseRange(session.headers["range"], length)
        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1)
        val count = (end - start + 1).coerceAtLeast(0)
        val transfer = OrganizerTransfer(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.DOWNLOAD,
            name = file.name,
            total = count,
            state = TransferState.ACTIVE,
            filePath = file.absolutePath,
            mime = mimeOfName(file.name),
        )
        addTransfer(transfer)
        val fileInput = FileInputStream(file)
        skipFully(fileInput, start)
        val input = ProgressInputStream(fileInput) { read ->
            updatesTransfer(transfer.id) { it.copy(bytes = read.coerceAtMost(count)) }
        }
        input.onClosed = {
            updatesTransfer(transfer.id) {
                it.copy(state = TransferState.DONE, bytes = count)
            }
            pairing.endTransfer(sessionId)
        }
        val headers = HashMap<String, String>()
        headers["Accept-Ranges"] = "bytes"
        headers["Content-Disposition"] = "attachment; filename=\"${file.name}\""
        val code = if (range != null) 206 else 200
        if (range != null) headers["Content-Range"] = "bytes $start-$end/$length"
        return HttpSupport.stream(code, mimeOfName(file.name), input, count, headers)
    }

    private fun parseRange(header: String?, length: Long): LongRange? {
        if (header.isNullOrBlank()) return null
        val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(header.trim()) ?: return null
        val first = match.groupValues[1]
        val second = match.groupValues[2]
        return when {
            first.isEmpty() && second.isEmpty() -> null
            first.isEmpty() -> {
                val suffix = second.toLongOrNull() ?: return null
                val start = (length - suffix).coerceAtLeast(0)
                start until length
            }
            second.isEmpty() -> {
                val start = first.toLongOrNull() ?: return null
                if (start >= length) return null
                start until length
            }
            else -> {
                val start = first.toLongOrNull() ?: return null
                val end = second.toLongOrNull() ?: return null
                if (start > end || start >= length) return null
                start..end.coerceAtMost(length - 1)
            }
        }
    }

    private fun zipStream(entries: List<Pair<File, String>>, onDone: () -> Unit): InputStream {
        val output = PipedOutputStream()
        val input = PipedInputStream(output, 256 * 1024)
        Thread {
            try {
                ZipOutputStream(BufferedOutputStream(output, 256 * 1024)).use { zip ->
                    val used = HashSet<String>()
                    for ((file, rawName) in entries) {
                        var name = rawName
                        var index = 1
                        while (!used.add(name)) {
                            name = "${rawName.substringBeforeLast('.')} ($index).${rawName.substringAfterLast('.')}"
                            index++
                        }
                        val crc = CRC32()
                        FileInputStream(file).use { source ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                crc.update(buffer, 0, read)
                            }
                        }
                        val entry = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = file.length()
                            compressedSize = file.length()
                            this.crc = crc.value
                            time = file.lastModified()
                        }
                        zip.putNextEntry(entry)
                        FileInputStream(file).use { source -> source.copyTo(zip, 256 * 1024) }
                        zip.closeEntry()
                    }
                }
            } catch (_: Exception) {
                try {
                    output.close()
                } catch (_: Exception) {
                }
            } finally {
                onDone()
            }
        }.apply { isDaemon = true }.start()
        return input
    }

    // ------------------------------------------------------------------ open existing
    private fun openExisting(
        session: NanoHTTPD.IHTTPSession,
        sessionId: String,
    ): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val file = resolveDirectory(body.optString("root"), body.optString("path"), decode = true)
        if (!file.isFile) return HttpSupport.error(404, "not found")
        val action = actionForName(file.name)
        if (action == TransferAction.NONE) {
            return HttpSupport.error(415, context.getString(R.string.organizer_unsupported))
        }
        val transfer = OrganizerTransfer(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.OPEN,
            name = file.name,
            total = file.length(),
            state = TransferState.PENDING,
            action = action,
            sessionId = sessionId,
            filePath = file.absolutePath,
            mime = mimeOfName(file.name),
        )
        addTransfer(transfer)
        if (pageVisible) {
            if (action == TransferAction.INSTALL) {
                installManager.prepare(file)
            } else {
                callbacks.onOpenFile(file, transfer.mime ?: mimeOfName(file.name), action)
                updatesTransfer(transfer.id) {
                    it.copy(
                        state = TransferState.DONE,
                        result = if (action == TransferAction.PLAY) TransferResult.PLAYED else TransferResult.SHOWN,
                    )
                }
            }
        }
        return HttpSupport.json(200, JSONObject().put("transfer", transfer.id))
    }

    // ------------------------------------------------------------------ desktop
    private fun desktopJson(): NanoHTTPD.Response {
        val target = storage.target()
        return HttpSupport.json(
            200,
            JSONObject()
                .put("device", callbacks.deviceName())
                .put("weatherCity", OrganizerPrefs.weatherCity(context))
                .put(
                    "target",
                    JSONObject().put("root", target.root).put("path", target.path),
                )
                .put("storage", storageJson())
                .put("apps", appsJson()),
        )
    }

    private fun setWeatherCity(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = HttpSupport.readJson(session)
        val city = body.optString("city").trim()
        if (city.length > 64) return HttpSupport.error(400, "bad city")
        callbacks.onWeatherCityChanged(city)
        OrganizerPrefs.setWeatherCity(context, city)
        return HttpSupport.json(200, JSONObject().put("city", city))
    }

    private fun uploadWallpaper(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val bytes = readBytes(session, MAX_WALLPAPER_BYTES)
        val extension = imageExtension(bytes) ?: return HttpSupport.error(415, "bad image")
        val dir = File(context.filesDir, "wallpaper").apply { mkdirs() }
        val file = File(dir, "wallpaper-${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)
        callbacks.onWallpaperChanged(file)
        return HttpSupport.json(200, JSONObject().put("name", file.name))
    }

    private fun uploadIcon(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val component = HttpSupport.queryParameter(session, "component") ?: ""
        if (component.isEmpty() || "/" !in component) return HttpSupport.error(400, "bad component")
        val bytes = readBytes(session, MAX_ICON_BYTES)
        val extension = imageExtension(bytes) ?: return HttpSupport.error(415, "bad image")
        val dir = File(context.filesDir, "icons").apply { mkdirs() }
        val safe = component.replace('/', '_').replace(':', '_')
        val file = File(dir, "$safe.$extension")
        file.writeBytes(bytes)
        callbacks.onIconChanged(component, file)
        return HttpSupport.json(200, JSONObject().put("name", file.name))
    }

    private fun readBytes(session: NanoHTTPD.IHTTPSession, maxBytes: Long): ByteArray {
        val declared = HttpSupport.contentLength(session) ?: return ByteArray(0)
        if (declared > maxBytes) throw HttpSupport.HttpError(413, "too large")
        val bytes = ByteArray(declared.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = session.inputStream.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            offset += read
        }
        return bytes.copyOf(offset)
    }

    private fun imageExtension(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg"
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        ) {
            return "webp"
        }
        return null
    }

    // ------------------------------------------------------------------ helpers
    private class BoundHttpServer(
        hostname: String,
        port: Int,
        cacheDir: File,
        private val router: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response,
    ) : NanoHTTPD(hostname, port) {
        init {
            // Android 上 java.io.tmpdir 不可写，POST 正文解析必须落在应用缓存目录
            setTempFileManagerFactory {
                object : TempFileManager {
                    private val files = ArrayList<TempFile>()
                    override fun createTempFile(filenameHint: String?): TempFile =
                        DefaultTempFile(cacheDir).also { files += it }

                    override fun clear() {
                        files.forEach { runCatching { it.delete() } }
                        files.clear()
                    }
                }
            }
        }

        override fun serve(session: IHTTPSession): Response = try {
            router(session)
        } catch (t: Throwable) {
            Log.e(TAG, "serve failed: ${session.uri}", t)
            throw t
        }
    }

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile
        var resolution: PairingResolution? = null
    }

    private fun freePort(host: String): Int? {
        repeat(24) {
            val candidate = MIN_PORT + secureRandom.nextInt(MAX_PORT - MIN_PORT + 1)
            var first: ServerSocket? = null
            var second: ServerSocket? = null
            try {
                first = ServerSocket()
                first.bind(InetSocketAddress(host, candidate))
                second = ServerSocket()
                second.bind(InetSocketAddress("127.0.0.1", candidate))
                return candidate
            } catch (_: Exception) {
            } finally {
                try {
                    first?.close()
                } catch (_: Exception) {
                }
                try {
                    second?.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = onNetworkChanged()
        }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun onNetworkChanged() {
        if (!running || !pageVisible) return
        val current = lanProvider.discover()
        if (current == null) {
            stop()
            return
        }
        if (current.address != lan?.address) {
            val wasVisible = pageVisible
            stop()
            pageVisible = wasVisible
            start()
        }
    }

    private fun publish() {
        val snapshot = pairing.sessions().map {
            OrganizerSessionView(
                id = it.id,
                device = it.device,
                approved = true,
                activeTransfers = it.activeTransfers,
            )
        }
        val pending = pairing.pendingRequests().firstOrNull()
        val transfersSnapshot = synchronized(transfers) {
            transfers.values.sortedByDescending { it.createdAt }
        }
        _state.value = _state.value.copy(
            running = running,
            address = lan?.address,
            port = port,
            token = if (running) pairing.token else "",
            code = if (running) pairing.code else "",
            networkType = when (lan?.transport) {
                LanTransport.ETHERNET -> "ethernet"
                LanTransport.WIFI -> "wifi"
                else -> "none"
            },
            sessions = snapshot,
            transfers = transfersSnapshot,
            target = storage.target(),
            pageVisible = pageVisible,
            acceptingNewSessions = acceptingNew && !stopWhenIdle,
            pendingConnection = pending,
            install = installManager.state.value,
        )
    }

    private fun refreshStorageInternal() {
        val snapshot = try {
            storage.snapshot(callbacks.restrictedMode())
        } catch (_: Exception) {
            StorageState()
        }
        _state.value = _state.value.copy(storage = snapshot)
    }

    private fun addTransfer(transfer: OrganizerTransfer) {
        synchronized(transfers) {
            transfers[transfer.id] = transfer
            while (transfers.size > MAX_TRANSFERS) {
                val first = transfers.entries.firstOrNull() ?: break
                transfers.remove(first.key)
            }
        }
        publish()
    }

    private fun getTransfer(id: String): OrganizerTransfer? =
        synchronized(transfers) { transfers[id] }

    private fun updatesTransfer(id: String, block: (OrganizerTransfer) -> OrganizerTransfer) {
        synchronized(transfers) {
            val current = transfers[id] ?: return
            transfers[id] = block(current)
        }
        publish()
        maybeStopWhenIdle()
    }

    private fun maybeStopWhenIdle() {
        if (stopWhenIdle && running && activeTransfers() == 0) stop()
    }

    private fun abortWaiters() {
        val snapshot = synchronized(lock) {
            val copy = waiters.values.toList()
            waiters.clear()
            copy
        }
        for (waiter in snapshot) {
            waiter.resolution = null
            waiter.latch.countDown()
        }
    }

    private fun activeTransfers(): Int = synchronized(transfers) {
        transfers.values.count { it.state == TransferState.QUEUED || it.state == TransferState.ACTIVE }
    }

    private fun transferJson(transfer: OrganizerTransfer): JSONObject = JSONObject()
        .put("id", transfer.id)
        .put("kind", transfer.kind.wire)
        .put("name", transfer.name)
        .put("bytes", transfer.bytes)
        .put("total", transfer.total)
        .put("state", transfer.state.wire)
        .put("action", transfer.action.wire)
        .put("result", transfer.result?.wire ?: JSONObject.NULL)
        .put("error", transfer.error ?: JSONObject.NULL)

    private fun actionForName(name: String): TransferAction {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in InstallManager.ARCHIVE_SUFFIXES || extension == "apk") {
            return TransferAction.INSTALL
        }
        return when (mimeOfName(name).substringBefore('/')) {
            "image" -> TransferAction.VIEW
            "video", "audio" -> TransferAction.PLAY
            else -> TransferAction.NONE
        }
    }

    private fun mimeOfName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mkv" -> "video/x-matroska"
                "flac" -> "audio/flac"
                "apk" -> "application/vnd.android.package-archive"
                "apks", "apkm", "xapk" -> "application/octet-stream"
                "srt" -> "application/x-subrip"
                "ass" -> "text/x-ssa"
                "vtt" -> "text/vtt"
                else -> "application/octet-stream"
            }
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
            if (!File(dir, candidate).exists()) return candidate
            index++
        }
    }

    private fun uniqueFile(dir: File, name: String): File =
        File(dir, uniqueName(dir, name))

    private fun deviceFromUserAgent(agent: String?): String {
        val ua = agent ?: return "Browser"
        val platform = when {
            ua.contains("iPhone") -> "iPhone"
            ua.contains("iPad") -> "iPad"
            ua.contains("Android") -> "Android"
            ua.contains("Windows") -> "Windows"
            ua.contains("Macintosh") -> "Mac"
            else -> "Browser"
        }
        val browser = when {
            ua.contains("Edg/") -> "Edge"
            ua.contains("Chrome/") -> "Chrome"
            ua.contains("Safari/") -> "Safari"
            ua.contains("Firefox/") -> "Firefox"
            else -> "Browser"
        }
        return "$platform · $browser"
    }

    private fun redact(text: String): String {
        var output = text
        val secrets = ArrayList<String>()
        if (_state.value.token.isNotBlank()) secrets += _state.value.token
        if (_state.value.code.isNotBlank()) secrets += _state.value.code
        secrets += pairing.sessions().map { it.id }
        synchronized(lock) {
            secrets += tickets.keys
            secrets += uploadsById.keys
        }
        for (secret in secrets) {
            if (secret.length >= 4) output = output.replace(secret, "***")
        }
        output = Regex("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])")
            .replace(output) { match ->
                "${match.groupValues[1]}.${match.groupValues[2]}.x.x"
            }
        output = Regex("(?i)([0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2})(?::[0-9a-f]{2}){3}")
            .replace(output) { match -> "${match.groupValues[1]}:xx:xx:xx" }
        output = Regex("(?im)^.*serial.*$").replace(output, "")
        output = Regex("(?im)^.*city.*$").replace(output, "")
        return output.trim()
    }

    private fun onInstallState(install: InstallCardState) {
        when (install.state) {
            "installed" -> {
                val pending = synchronized(transfers) {
                    transfers.values
                        .filter { it.action == TransferAction.INSTALL && it.state == TransferState.PENDING }
                        .maxByOrNull { it.createdAt }
                }
                pending?.let {
                    updatesTransfer(it.id) { transfer ->
                        transfer.copy(state = TransferState.DONE, result = TransferResult.INSTALLED)
                    }
                }
            }
            "cancelled" -> {
                val pending = synchronized(transfers) {
                    transfers.values
                        .filter { it.action == TransferAction.INSTALL && it.state == TransferState.PENDING }
                        .maxByOrNull { it.createdAt }
                }
                pending?.let {
                    updatesTransfer(it.id) { transfer ->
                        transfer.copy(state = TransferState.CANCELLED, result = TransferResult.CANCELLED)
                    }
                }
            }
            "failed", "blocked" -> {
                val pending = synchronized(transfers) {
                    transfers.values
                        .filter { it.action == TransferAction.INSTALL && it.state == TransferState.PENDING }
                        .maxByOrNull { it.createdAt }
                }
                pending?.let {
                    updatesTransfer(it.id) { transfer ->
                        transfer.copy(state = TransferState.FAILED, error = installManager.errorText(install.error))
                    }
                }
            }
        }
    }
}

/** Small Android-side probe for the personal-video rule (§13). */
internal object VideoProbe {
    fun hasVideoTrack(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } catch (_: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}

/** InputStream that reports progress after every read and closes its owner once. */
internal class ProgressInputStream(
    input: InputStream,
    private val onProgress: (Long) -> Unit,
) : FilterInputStream(input) {
    var onClosed: (() -> Unit)? = null
    private var total = 0L
    private var closed = false

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            total++
            onProgress(total)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = super.read(b, off, len)
        if (read > 0) {
            total += read
            onProgress(total)
        }
        return read
    }

    override fun close() {
        if (!closed) {
            closed = true
            try {
                super.close()
            } finally {
                onClosed?.invoke()
            }
        }
    }

}

private fun skipFully(input: InputStream, start: Long) {
    var remaining = start
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped <= 0) break
        remaining -= skipped
    }
}
