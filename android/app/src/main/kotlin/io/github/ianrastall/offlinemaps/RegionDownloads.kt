package io.github.ianrastall.offlinemaps

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class DownloadRequest(val region: String, val target: Int) {
    fun json() = JSONObject().put("region", region).put("target", target)

    companion object {
        fun read(value: JSONObject): DownloadRequest? {
            val region = value.optString("region")
            val target = value.optInt("target")
            return if (region.isNotBlank() && target in 1..4) DownloadRequest(region, target) else null
        }
    }
}

/** One service-owned download at a time. The core owns staging and activation. */
class RegionDownloads(
    private val context: Context,
    private val getCore: () -> CoreStore?,
    private val changed: () -> Unit,
    private val backupDownload: (String, AtomicBoolean, () -> Unit) -> Boolean,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val preferences = context.getSharedPreferences("downloads", 0)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Job(
        val items: List<DownloadItem>,
        val scope: String?,
        val network: Network,
        var started: Boolean = false,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val backingUp: AtomicBoolean = AtomicBoolean(false),
        var pauseReason: String = "Download paused. Choose Resume to continue.",
    ) {
        @Volatile var index = 0
        val item get() = items[index]
        val prefix get() = if (scope == null) "" else
            "${index + 1} of ${items.size} · ${item.name} · ${downloadTargetName(item.request.target)}\n"
    }
    private var job: Job? = null
    private var foreground = false
    var active by mutableStateOf(false)
        private set
    var runId = 0L
        private set
    var title = "Offline Maps download"
        private set
    var message by mutableStateOf("")
        private set
    var fraction by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var connectionText by mutableStateOf("")
        private set
    var wifiOnly by mutableStateOf(preferences.getBoolean("wifiOnly", true))
        private set
    var pending by mutableStateOf(readPending())
        private set
    var pendingBulk by mutableStateOf(preferences.getString("bulkScope", null))
        private set
    val activeRequest: DownloadRequest? get() = job?.item?.request

    private fun readPending(): List<DownloadRequest> {
        val list = runCatching {
            val array = JSONArray(preferences.getString("pendingDownloads", "[]"))
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(DownloadRequest::read) }
        }.getOrDefault(emptyList())
        val legacy = runCatching {
            preferences.getString("pending", null)?.let { DownloadRequest.read(JSONObject(it)) }
        }.getOrNull()
        return (list + listOfNotNull(legacy)).distinct()
    }

    private fun writePending(requests: List<DownloadRequest>): Boolean =
        preferences.edit().putString("pendingDownloads", JSONArray(requests.map(DownloadRequest::json)).toString())
            .remove("pending").commit()

    // Once per session, recover bytes left by apps that remembered only one download.
    // The staged files are durable, so the next download can persist the merged list.
    fun recoverStaged(core: CoreStore) {
        val staged = runCatching {
            core.use { JSONArray(NativeCore.stagedDownloadsJson(it)).objects().mapNotNull(DownloadRequest::read) }
        }.getOrDefault(emptyList())
        if (staged.isNotEmpty()) main.post { pending = (pending + staged).distinct() }
    }

    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refreshConnection() }
        override fun onLost(network: Network) {
            if (job?.network == network) pause("Download paused because the connection was lost. Choose Resume when connected again.")
            refreshConnection()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (job?.network == network && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    || (wifiOnly && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)))) {
                pause("Download paused because Wi-Fi is unavailable. Choose Resume when connected again.")
            }
            refreshConnection()
        }
    }
    init { connectivity.registerDefaultNetworkCallback(networks, main); refreshConnection() }

    private fun refreshConnection() {
        connectionText = runCatching {
            val selected = network()
            if (connectivity.getNetworkCapabilities(selected)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "Wi-Fi connected" else "Internet connection available"
        }.getOrElse { it.message ?: "No connection" }
    }

    // A network callback can precede activeNetwork being updated, particularly
    // across airplane-mode changes. Reconcile the visible label while open.
    private val connectionPoll = object : Runnable {
        override fun run() {
            if (!foreground) return
            refreshConnection()
            main.postDelayed(this, 2_000)
        }
    }

    fun configureWifiOnly(value: Boolean) {
        if (active) return
        wifiOnly = value
        preferences.edit().putBoolean("wifiOnly", value).apply()
        refreshConnection()
    }

    fun foreground(value: Boolean) {
        foreground = value
        main.removeCallbacks(connectionPoll)
        if (value) connectionPoll.run()
    }

    private fun network(): Network {
        val network = connectivity.activeNetwork ?: kotlin.error("Connect to ${if (wifiOnly) "Wi-Fi" else "the internet"} before downloading.")
        val caps = connectivity.getNetworkCapabilities(network) ?: kotlin.error("The connection is unavailable.")
        check(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { "This connection has no internet access." }
        check(!wifiOnly || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { "Connect to Wi-Fi, or turn off Wi-Fi only to use mobile data." }
        return network
    }

    private val poll = object : Runnable {
        override fun run() {
            val current = job ?: return
            runCatching {
                getCore()?.use {
                    // Retry cancellation while entering the native operation, so a
                    // background/network event cannot race the core's busy flag.
                    if (current.cancelled.get()) NativeCore.cancel(it)
                    JSONObject(NativeCore.progressJson(it))
                }
            }.getOrNull()?.let { progress ->
                if (current.cancelled.get()) {
                    message = current.prefix + "Pausing download…"
                } else if (current.backingUp.get()) {
                    message = current.prefix + "Saving the map backup…"
                    fraction = null
                } else if (progress.optBoolean("active")) {
                    val done = progress.optLong("done")
                    val total = progress.optLong("total")
                    fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
                    message = current.prefix + when (progress.optString("phase")) {
                        "Downloading" -> "Downloading ${if (total > 0) "${sizeText(done)} of ${sizeText(total)}" else sizeText(done)}"
                        "Preparing" -> if (total > 0) "Preparing search: $done of $total map tiles" else "Preparing search…"
                        "Verifying" -> "Verifying download…"
                        "Activating" -> "Making the verified download ready…"
                        else -> "Checking for a newer file…"
                    }
                }
            }
            main.postDelayed(this, 250)
        }
    }

    internal fun start(item: DownloadItem) = start(listOf(item), null)

    internal fun startBulk(items: List<DownloadItem>, scope: String) {
        if (active || getCore() == null) return
        if (items.isEmpty() && pendingBulk == scope) {
            preferences.edit().remove("bulkScope").apply()
            pendingBulk = null
            notice = "All available layers for $scope are installed."
        } else if (items.isNotEmpty()) {
            start(items, scope)
        }
    }

    private fun start(items: List<DownloadItem>, scope: String?) {
        if (active || getCore() == null) return
        error = null; notice = null
        val (request, regionName, estimatedBytes) = items.first()
        val selected = runCatching {
            check(foreground) { "Open Offline Maps to start or resume a download." }
            check(request.target in 1..4) { "Unknown download type." }
            if (scope == null) {
                val maps = File(context.noBackupFilesDir, "maps")
                // A resumed transfer already occupies staging space. The core checks its remaining bytes.
                val needed = if (request in pending) 256L shl 20 else
                    Math.addExact(Math.multiplyExact(estimatedBytes.coerceAtLeast(0), if (request.target == 1) 2L else 1L), 256L shl 20)
                check(maps.usableSpace >= needed) { "Not enough free space to download and prepare this file. Allow about ${sizeText(needed)}." }
            }
            network()
        }.getOrElse { error = it.message; return }
        val current = Job(items, scope, selected)
        runId = android.os.SystemClock.elapsedRealtimeNanos()
        title = if (scope == null) "$regionName · ${downloadTargetName(request.target)}" else "$scope · Bulk download"
        job = current; active = true; message = current.prefix + "Checking the download…"; fraction = null
        if (scope == null && request !in pending) pending = pending + request
        runCatching {
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(DownloadService.START).putExtra(DownloadService.RUN, runId))
        }.onFailure { serviceFailed(it) }
    }

    /** Called only after the service has posted its foreground notification. */
    fun begin() {
        val current = job ?: return
        if (current.started) return
        current.started = true
        val savedPending = pending
        main.post(poll)
        worker.execute {
            val previous = connectivity.boundNetworkForProcess
            var remaining = savedPending
            var completed = 0
            var upToDate = false
            var backedUp = false
            var backupFailed = false
            var refreshFailed = false
            var completionRecorded = true
            val scope = current.scope
            val result = runCatching {
                if (scope != null) {
                    check(preferences.edit().putString("bulkScope", scope).commit()) { "Could not save the bulk download scope." }
                    main.post { pendingBulk = scope }
                }
                // Pin sockets/DNS to the selected network. Losing Wi-Fi cannot
                // silently move this transfer onto the phone's mobile data.
                check(connectivity.bindProcessToNetwork(current.network)) { "The selected connection could not be used." }
                current.items.forEachIndexed { index, item ->
                    check(!current.cancelled.get()) { "Download paused." }
                    current.index = index
                    val request = item.request
                    val checking = current.prefix + "Checking the download…"
                    main.post { message = checking; fraction = null }
                    // Persist the current transfer before the core stages bytes.
                    val unfinished = (remaining + request).distinct()
                    check(writePending(unfinished)) { "Could not save the unfinished download list." }
                    main.post { pending = unfinished }
                    check(!current.cancelled.get()) { "Download paused." }
                    val data = getCore()!!.use { JSONObject(NativeCore.updateJson(it, request.region, request.target, false)) }
                    upToDate = data.optBoolean("upToDate")
                    completed++
                    if (runCatching(changed).isFailure) refreshFailed = true
                    val backup = runCatching {
                        backupDownload(request.region, current.cancelled) { current.backingUp.set(true) }
                    }
                    current.backingUp.set(false)
                    if (backup.isFailure) backupFailed = true
                    if (backup.getOrDefault(false)) backedUp = true
                    val finished = unfinished - request
                    completionRecorded = runCatching { writePending(finished) }.getOrDefault(false)
                    if (completionRecorded) {
                        remaining = finished
                        main.post { pending = finished }
                    } else if (scope != null) {
                        kotlin.error("The file is ready. The unfinished download list could not be saved.")
                    }
                }
                if (scope != null) {
                    check(preferences.edit().remove("bulkScope").commit()) { "Could not clear the unfinished bulk download." }
                    main.post { pendingBulk = null }
                }
            }
            runCatching { connectivity.bindProcessToNetwork(previous) }
            main.post {
                if (job !== current) return@post
                main.removeCallbacks(poll)
                job = null; active = false; fraction = null
                result.onSuccess {
                    notice = when {
                        scope != null -> "$scope · $completed downloads complete." +
                            if (backupFailed) " Some backups could not finish. Open Maps to back them up." else ""
                        !completionRecorded -> "The file is ready. The unfinished download list could not be saved."
                        backupFailed -> "The map is ready offline. Its backup could not finish. Open Maps to back it up."
                        refreshFailed -> "The file is ready. Reopen Offline Maps to refresh the display."
                        backedUp -> "The download is ready offline and backed up."
                        upToDate -> "The installed file is up to date."
                        else -> "The download is ready offline."
                    }
                }.onFailure {
                    val progress = if (scope == null) "" else "$scope · $completed of ${current.items.size} complete. "
                    if (current.cancelled.get()) notice = progress + current.pauseReason
                    else error = progress + (it.message ?: "Download failed.").removePrefix("Rust error: ") +
                        if (scope == null) " You can retry or resume this download." else " Choose Resume bulk download to continue."
                }
            }
        }
    }

    fun serviceFailed(failure: Throwable) {
        if (job?.started == true) {
            pause("Download paused because its background service stopped. Choose Resume to continue.")
            return
        }
        job = null; active = false; fraction = null
        error = "The download service could not start: ${failure.message ?: failure.javaClass.simpleName}. Open the app and choose Resume."
    }

    fun pause(reason: String = "Download paused. Choose Resume to continue.") {
        val current = job ?: return
        current.pauseReason = reason
        current.cancelled.set(true)
        message = "Pausing download…"
        getCore()?.use { NativeCore.cancel(it) }
    }

}

fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    else -> "$bytes bytes"
}
