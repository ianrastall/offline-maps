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
        val request: DownloadRequest,
        val network: Network,
        var started: Boolean = false,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val backingUp: AtomicBoolean = AtomicBoolean(false),
        var pauseReason: String = "Download paused. Choose Resume to continue.",
    )
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
    val activeRequest: DownloadRequest? get() = job?.request

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
                    message = "Pausing download…"
                } else if (current.backingUp.get()) {
                    message = "Saving the map backup…"
                    fraction = null
                } else if (progress.optBoolean("active")) {
                    val done = progress.optLong("done")
                    val total = progress.optLong("total")
                    fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
                    message = when (progress.optString("phase")) {
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

    fun start(request: DownloadRequest, estimatedBytes: Long, regionName: String) {
        if (active || getCore() == null) return
        error = null; notice = null
        val selected = runCatching {
            check(foreground) { "Open Offline Maps to start or resume a download." }
            check(request.target in 1..4) { "Unknown download type." }
            val maps = File(context.noBackupFilesDir, "maps")
            // A resumed transfer already occupies staging space. Let the core
            // check the exact remaining bytes against the server's current file.
            val needed = if (request in pending) 256L shl 20 else
                Math.addExact(Math.multiplyExact(estimatedBytes.coerceAtLeast(0), if (request.target == 1) 2L else 1L), 256L shl 20)
            check(maps.usableSpace >= needed) { "Not enough free space to download and prepare this file. Allow about ${sizeText(needed)}." }
            network()
        }.getOrElse { error = it.message; return }
        val current = Job(request, selected)
        runId = android.os.SystemClock.elapsedRealtimeNanos()
        title = "$regionName · ${when (request.target) { 1 -> "map and search"; 2 -> "stations and addresses"; 3 -> "terrain"; else -> "flood zones and public lands" }}"
        job = current; active = true; message = "Checking the download…"; fraction = null
        if (request !in pending) pending = pending + request
        runCatching {
            context.startForegroundService(Intent(context, DownloadService::class.java).setAction(DownloadService.START).putExtra(DownloadService.RUN, runId))
        }.onFailure { serviceFailed(it) }
    }

    /** Called only after the service has posted its foreground notification. */
    fun begin() {
        val current = job ?: return
        if (current.started) return
        current.started = true
        val request = current.request
        val selected = current.network
        val queued = pending
        main.post(poll)
        worker.execute {
            val previous = connectivity.boundNetworkForProcess
            val result = runCatching {
                // Record the request before native work stages any bytes. Only
                // this worker writes the list, and only one transfer owns it.
                check(writePending(queued)) { "Could not save the unfinished download list." }
                check(!current.cancelled.get()) { "Download paused." }
                // Pin sockets/DNS to the selected network. Losing Wi-Fi cannot
                // silently move this transfer onto the phone's mobile data.
                check(connectivity.bindProcessToNetwork(selected)) { "The selected connection could not be used." }
                check(!current.cancelled.get()) { "Download paused." }
                getCore()!!.use { JSONObject(NativeCore.updateJson(it, request.region, request.target, false)) }
            }
            runCatching { connectivity.bindProcessToNetwork(previous) }
            // Cancellation can arrive just as atomic activation completes. Report
            // successful activation as success rather than a fictitious pause.
            val refreshFailure = if (result.isSuccess) runCatching(changed).exceptionOrNull() else null
            val backup = if (result.isSuccess) runCatching {
                backupDownload(request.region, current.cancelled) {
                    current.backingUp.set(true)
                }
            } else Result.success(false)
            val remaining = queued - request
            val completionRecorded = result.isSuccess && runCatching { writePending(remaining) }.getOrDefault(false)
            main.post {
                if (job !== current) return@post
                main.removeCallbacks(poll)
                job = null; active = false; fraction = null
                result.onSuccess { data ->
                    if (completionRecorded) pending = remaining
                    notice = when {
                        !completionRecorded -> "The file is ready. The unfinished download list could not be saved."
                        backup.isFailure -> "The map is ready offline. Its backup could not finish. Open Maps to back it up."
                        refreshFailure != null -> "The file is ready. Reopen Offline Maps to refresh the display."
                        backup.getOrDefault(false) -> "The download is ready offline and backed up."
                        data.optBoolean("upToDate") -> "The installed file is up to date."
                        else -> "The download is ready offline."
                    }
                }.onFailure {
                    if (current.cancelled.get()) notice = current.pauseReason
                    else error = (it.message ?: "Download failed.").removePrefix("Rust error: ") + " You can retry or resume this download."
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
