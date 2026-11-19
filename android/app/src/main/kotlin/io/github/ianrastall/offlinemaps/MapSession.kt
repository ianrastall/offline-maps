package io.github.ianrastall.offlinemaps

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

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
    private val backup = app.getSharedPreferences("map-backup", 0)
    var backupEnabled by mutableStateOf(backup.contains("tree") && backup.contains("root"))
        private set
    val downloads = RegionDownloads(app, { core }, { changed() }, ::backupDownload)

    fun linkBackup(tree: Uri, rootId: String): Boolean {
        val previous = backup.getString("tree", null)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val linked = runCatching {
            app.contentResolver.takePersistableUriPermission(tree, flags)
            backup.edit().putString("tree", tree.toString()).putString("root", rootId).commit()
        }.getOrDefault(false)
        if (linked) {
            backupEnabled = true
            if (previous != null && previous != tree.toString()) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
            }
        }
        return linked
    }

    fun stopBackups() {
        val tree = backup.getString("tree", null)
        backup.edit().clear().apply()
        backupEnabled = false
        if (tree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(tree), flags) }
        }
    }

    private fun backupDownload(region: String, cancelled: AtomicBoolean, progress: () -> Unit): Boolean {
        val current = core ?: return false
        val tree = backup.getString("tree", null)?.let(Uri::parse) ?: return false
        val root = backup.getString("root", null) ?: return false
        progress()
        MapLibrary(app, current, MapImport(app, current, cancelled)).backupDownload(tree, root, region)
        return true
    }

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
