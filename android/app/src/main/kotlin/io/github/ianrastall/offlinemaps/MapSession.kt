package io.github.ianrastall.offlinemaps

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

class OfflineMapsApplication : Application() {
    val session by lazy { MapSession(this) }
}

/** One native store per process, shared by map screens and the download service. */
class MapSession(private val app: Application) {
    @Volatile private var core: CoreStore? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var importOwner: Any? = null
    var importing by mutableStateOf(false)
        private set
    val downloads = RegionDownloads(app, { core }, { changed() })

    fun claimImport(owner: Any): Boolean {
        if (importOwner != null) return false
        importOwner = owner; importing = true
        return true
    }
    fun releaseImport(owner: Any) {
        main.post { if (importOwner === owner) { importOwner = null; importing = false } }
    }
    fun changed() { listeners.forEach { listener -> runCatching(listener) } }

    @Synchronized fun open(): CoreStore {
        core?.let { return it }
        val overview = File(app.noBackupFilesDir, "world-overview.mbtiles")
        val size = app.assets.openFd("overview/world-overview.mbtiles").use { it.length }
        if (overview.length() != size) {
            val temp = File(overview.parentFile, "overview.part")
            app.assets.open("overview/world-overview.mbtiles").use { input ->
                temp.outputStream().use { input.copyTo(it); it.fd.sync() }
            }
            check(temp.renameTo(overview)) { "Could not prepare the overview." }
        }
        val maps = File(app.noBackupFilesDir, "maps").apply { mkdirs() }
        // Only on a fresh process: never remove another screen's active import.
        File(maps, ".imports").deleteRecursively()
        return CoreStore(maps.absolutePath, overview.absolutePath).also {
            core = it
            downloads.recoverStaged(it)
        }
    }

    fun listen(listener: () -> Unit) { listeners.add(listener) }
    fun unlisten(listener: () -> Unit) { listeners.remove(listener) }
}
