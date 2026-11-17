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
import java.io.File
import java.util.concurrent.Executors

class MapModel(application: Application) : AndroidViewModel(application) {
    var status by mutableStateOf<JSONObject?>(null)
    var error by mutableStateOf<String?>(null)
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
    private val main = Handler(Looper.getMainLooper())
    private val prefs = application.getSharedPreferences("map", 0)
    private var importer: MapImport? = null
    private var searchGeneration = 0
    val downloads = RegionDownloads(application, { core }, { refresh(reload = true) })
    val workText: String? get() = if (downloads.active) downloads.message else busy
    val hasWork: Boolean get() = busy != null || downloads.active

    init {
        runCatching { prefs.getString("view", null)?.let { view = JSONObject(it) } }
        task(clearBusy = true) {
            val app = getApplication<Application>()
            val overview = File(app.noBackupFilesDir, "world-overview.mbtiles")
            val size = app.assets.openFd("overview/world-overview.mbtiles").use { it.length }
            if (overview.length() != size) {
                val temp = File(overview.parentFile, "overview.part")
                app.assets.open("overview/world-overview.mbtiles").use { input -> temp.outputStream().use { input.copyTo(it); it.fd.sync() } }
                check(temp.renameTo(overview)) { "Could not prepare the overview." }
            }
            val maps = File(app.noBackupFilesDir, "maps").apply { mkdirs() }
            File(maps, ".imports").deleteRecursively()
            core = CoreStore(maps.absolutePath, overview.absolutePath)
            val regions = core!!.use { JSONArray(NativeCore.catalogJson(it)).objects() }
            main.post { catalog = regions }
            refresh()
        }
    }

    private fun task(clearBusy: Boolean = false, work: () -> Unit) {
        worker.execute {
            runCatching(work).onFailure { e -> main.post { error = e.message ?: e.toString() } }
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
    fun save(name: String, latitude: Double, longitude: Double) {
        task { core!!.use { NativeCore.addPlace(it, name, latitude, longitude) }; refresh() }
    }
    fun remove(place: JSONObject) {
        task { core!!.use { NativeCore.removePlace(it, place.getLong("id")) }; refresh() }
    }
    fun importMap(tree: Uri) {
        if (hasWork || core == null) return
        busy = "Reading transfer…"
        val job = MapImport(getApplication(), core!!).also { importer = it }
        task(clearBusy = true) {
            try {
                job.run(tree) { phase -> main.post { busy = phase } }
                refresh(reload = true)
            } finally { importer = null }
        }
    }
    fun cancelImport() { importer?.cancelled?.set(true); core?.use { NativeCore.cancel(it) } }
    fun cancelWork() { if (downloads.active) downloads.pause() else cancelImport() }
    fun startDownload(region: JSONObject, target: Int) {
        if (busy != null) return
        error = null
        downloads.start(DownloadRequest(region.getString("id"), target), region.optLong(when (target) {
            1 -> "mapBytes"; 2 -> "extrasBytes"; 3 -> "terrainBytes"; else -> "overlayBytes"
        }))
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
        importer?.cancelled?.set(true)
        core?.use { NativeCore.cancel(it) }
        downloads.close {
            worker.shutdown()
            worker.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS)
            core?.close()
        }
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
