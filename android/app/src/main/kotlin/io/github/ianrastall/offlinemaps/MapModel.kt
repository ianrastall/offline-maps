package io.github.ianrastall.offlinemaps

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

class MapModel(application: Application) : AndroidViewModel(application) {
    var status by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var placesPreview by mutableStateOf<JSONObject?>(null)
        private set
    private var placesBackup: String? = null
    var busy by mutableStateOf<String?>("Opening maps…")
    var results by mutableStateOf<List<JSONObject>>(emptyList())
    var searching by mutableStateOf(false)
    var searchHint by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var catalog by mutableStateOf<List<JSONObject>>(emptyList())
    var gpsWanted by mutableStateOf(false)
    var following by mutableStateOf(false)
    var fix by mutableStateOf<android.location.Location?>(null)
    var gpsText by mutableStateOf("GPS off")
    var satellites by mutableStateOf(0)
    var satellitesUsed by mutableStateOf(0)
    var layers by mutableStateOf(true)
    var rendererGeneration by mutableStateOf(0)
    var view: JSONObject? = null
    var page: WebMap? = null
    var core: CoreStore? = null
        private set
    private val worker = Executors.newSingleThreadExecutor()
    private val importWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs = application.getSharedPreferences("map", 0)
    private var importer: MapImport? = null
    private var stagedTransfer: MapImport.Staged? = null
    private var cleared = false
    var transferReview by mutableStateOf<JSONObject?>(null)
        private set
    private val importCancelPoll = object : Runnable {
        override fun run() {
            val job = importer ?: return
            if (!job.cancelled.get()) return
            core?.use { NativeCore.cancel(it) }
            main.postDelayed(this, 100)
        }
    }
    private var searchGeneration = 0
    private val session = (application as OfflineMapsApplication).session
    val downloads = session.downloads
    private val contentChanged: () -> Unit = { runCatching { task { refresh(reload = true) } }; Unit }
    val workText: String? get() = if (downloads.active) downloads.message else busy
        ?: if (session.importing) if (transferReview != null) "Map import awaiting confirmation…" else "Finishing map import…" else null
    val hasWork: Boolean get() = busy != null || downloads.active || session.importing
    val canCancelWork: Boolean get() = downloads.active || importer != null

    init {
        session.listen(contentChanged)
        runCatching { prefs.getString("view", null)?.let { view = JSONObject(it) } }
        task(clearBusy = true) {
            core = session.open()
            val regions = core!!.use { JSONArray(NativeCore.catalogJson(it)).objects() }
            main.post { catalog = regions }
            refresh()
        }
    }

    private fun task(clearBusy: Boolean = false, executor: ExecutorService = worker, work: () -> Unit) {
        executor.execute {
            runCatching(work).onFailure { e -> main.post { error = (e.message ?: e.toString()).removePrefix("Rust error: ") } }
            if (clearBusy) main.post { busy = null }
        }
    }
    private fun refresh(reload: Boolean = false) {
        val data = core!!.use { JSONObject(NativeCore.statusJson(it, true)) }
        main.post {
            status = data
            val problem = listOf(data.optString("problem"), data.optString("placesProblem")).filter { it.isNotBlank() }.joinToString("\n")
            if (problem.isNotEmpty()) error = problem
            if (reload) page?.post(initMessage().put("kind", "reload"))
            page?.post(JSONObject().put("kind", "places").put("items", data.getJSONArray("places")))
        }
    }
    fun moved(lat: Double, lon: Double, zoom: Double) {
        view = JSONObject().put("latitude", lat).put("longitude", lon).put("zoom", zoom)
        prefs.edit().putString("view", view.toString()).apply()
    }
    fun initMessage(): JSONObject = JSONObject(status!!.toString()).apply {
        put("kind", "init"); put("theme", "dark"); put("animate", true); put("units", "imperial")
        put("outdoor", layers); put("flood", layers); put("lands", layers); put("shading", layers); put("contours", layers)
        view?.let { put("view", it) }
    }
    fun search() {
        val text = query.trim()
        if (text.isEmpty()) { results = emptyList(); searchHint = null; return }
        val generation = ++searchGeneration
        searching = true
        searchHint = null
        val at = view
        val lat = at?.optDouble("latitude", 39.8) ?: 39.8
        val lon = at?.optDouble("longitude", -89.6) ?: -89.6
        worker.execute {
            val outcome = runCatching { core!!.use { JSONArray(NativeCore.searchJson(it, text, lat, lon)).objects() } }
            main.post {
                if (generation == searchGeneration) {
                    searching = false
                    outcome.onSuccess { results = it; searchHint = if (it.isEmpty()) "No results in the installed maps." else null }.onFailure { error = it.message }
                }
            }
        }
    }
    fun show(place: JSONObject) {
        following = false
        page?.post(JSONObject().put("kind", "show").put("latitude", place.getDouble("latitude"))
            .put("longitude", place.getDouble("longitude")).put("label", place.optString("name"))
            .put("zoom", when (place.optString("kind")) { "place" -> 11; "address" -> 18; else -> 15 }))
    }
    fun save(name: String, note: String, latitude: Double, longitude: Double, id: Long? = null, done: () -> Unit) {
        if (busy != null) return
        busy = "Saving place…"; error = null; notice = null
        task(clearBusy = true) {
            core!!.use {
                if (id == null) NativeCore.addPlace(it, name, note, latitude, longitude)
                else NativeCore.updatePlace(it, id, name, note)
            }
            refresh()
            main.post { notice = "Saved place."; done() }
        }
    }
    fun remove(place: JSONObject) {
        if (busy != null) return
        busy = "Removing place…"; error = null; notice = null
        task(clearBusy = true) {
            core!!.use { NativeCore.removePlace(it, place.getLong("id")) }
            refresh()
            main.post { notice = "Removed saved place." }
        }
    }
    fun exportPlaces(uri: Uri) {
        if (hasWork || core == null) return
        busy = "Saving backup…"; error = null; notice = null
        task(clearBusy = true) {
            val text = core!!.use { NativeCore.exportPlaces(it) }
            val resolver = getApplication<Application>().contentResolver
            (resolver.openOutputStream(uri, "wt") ?: error("The backup file could not be opened.")).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            main.post { notice = "Saved-place backup saved." }
        }
    }
    fun previewPlaces(uri: Uri) {
        if (hasWork || core == null) return
        busy = "Reading saved places…"; error = null; notice = null
        cancelPlacesImport()
        task(clearBusy = true) {
            val resolver = getApplication<Application>().contentResolver
            val bytes = (resolver.openInputStream(uri) ?: error("The backup file could not be opened.")).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 16 * 1024 * 1024) { "Saved-place backups are limited to 16 MB." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val preview = core!!.use { JSONObject(NativeCore.importPlaces(it, text, false)) }
            main.post { placesBackup = text; placesPreview = preview }
        }
    }
    fun cancelPlacesImport() { placesPreview = null; placesBackup = null }
    fun importPlaces() {
        val backup = placesBackup ?: return
        if (busy != null) return
        busy = "Importing saved places…"; error = null; notice = null
        task(clearBusy = true) {
            val result = core!!.use { JSONObject(NativeCore.importPlaces(it, backup, true)) }
            refresh()
            main.post {
                cancelPlacesImport()
                notice = "Added ${countText(result.getInt("added"), "place")}; skipped ${countText(result.getInt("skipped"), "exact duplicate")}."
            }
        }
    }
    fun importMap(tree: Uri) {
        if (hasWork || core == null) return
        if (!session.claimImport(this)) return
        busy = "Reading transfer…"; error = null; notice = null; transferReview = null
        val job = MapImport(getApplication(), core!!).also { importer = it }
        task(clearBusy = true, executor = importWorker) {
            var readyForReview = false
            try {
                stagedTransfer?.close(); stagedTransfer = null
                val staged = job.stage(tree) { phase -> main.post { busy = phase } }
                stagedTransfer = staged
                readyForReview = true
                main.post { if (!cleared) transferReview = staged.review }
            } finally {
                importer = null; main.removeCallbacks(importCancelPoll)
                if (!readyForReview) session.releaseImport(this)
            }
        }
    }
    fun discardTransfer() {
        if (busy != null) { cancelImport(); return }
        transferReview = null
        task(executor = importWorker) { clearStagedTransfer() }
    }
    private fun clearStagedTransfer() {
        try { stagedTransfer?.close() }
        finally { stagedTransfer = null; session.releaseImport(this) }
    }
    fun confirmTransfer() {
        if (busy != null || transferReview == null || core == null) return
        busy = "Verifying and installing the region…"; error = null; notice = null
        val job = MapImport(getApplication(), core!!).also { importer = it }
        task(clearBusy = true, executor = importWorker) {
            try {
                val staged = stagedTransfer ?: error("Select the transfer folder again.")
                job.install(staged)
                main.post { notice = "The imported region is ready offline." }
            } finally {
                session.changed()
                try { clearStagedTransfer() }
                finally {
                    importer = null; main.removeCallbacks(importCancelPoll)
                    main.post { transferReview = null }
                }
            }
        }
    }
    fun cancelImport() {
        val job = importer ?: return
        job.cancelled.set(true)
        core?.use { NativeCore.cancel(it) }
        main.removeCallbacks(importCancelPoll)
        main.post(importCancelPoll)
    }
    fun cancelWork() { if (downloads.active) downloads.pause() else cancelImport() }
    fun startDownload(region: JSONObject, target: Int) {
        if (busy != null || session.importing) return
        error = null
        downloads.start(DownloadRequest(region.getString("id"), target), region.optLong(when (target) {
            1 -> "mapBytes"; 2 -> "extrasBytes"; 3 -> "terrainBytes"; else -> "overlayBytes"
        }), region.getString("name"))
    }
    fun position(stale: Boolean = false) {
        val current = fix
        val message = JSONObject().put("kind", "position")
        if (current != null) {
            message.put("latitude", current.latitude).put("longitude", current.longitude)
                .put("accuracy", current.accuracy.toDouble()).put("stale", stale)
                .put("follow", following && !stale)
            if (current.hasBearing()) message.put("bearing", current.bearing.toDouble())
        }
        page?.post(message)
    }
    fun toggleLayers(visible: Boolean) {
        layers = visible
        page?.post(JSONObject().put("kind", "terrain").put("shading", visible).put("contours", visible))
        page?.post(JSONObject().put("kind", "overlays").put("flood", visible).put("lands", visible))
        page?.post(JSONObject().put("kind", "outdoor").put("visible", visible))
    }
    override fun onCleared() {
        cleared = true
        session.unlisten(contentChanged)
        cancelImport()
        importWorker.execute { clearStagedTransfer() }
        importWorker.shutdown()
        // The application session owns the native handle and download worker.
        // Closing a screen must not cancel a service-owned download.
        worker.shutdown()
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
