package io.github.ianrastall.offlinemaps

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

class OfflineMapsApplication : Application() {
    val session by lazy { MapSession(this) }
}

/** One native store per process, shared by map screens and the download service. */
class MapSession(private val app: Application) {
    internal data class BackupStatus(
        val tree: Uri,
        val rootId: String,
        val copies: Map<String, MapLibrary.Copy>,
        val lastSuccess: Long,
        val failed: Set<String>,
        val available: Set<String>? = null,
        val unavailable: Boolean = false,
    )

    @Volatile private var core: CoreStore? = null
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var importOwner: Any? = null
    var importing by mutableStateOf(false)
        private set
    private val backup = app.getSharedPreferences("map-backup", 0)
    internal var backupStatus by mutableStateOf(loadBackup())
        private set
    var backupSetupDismissed by mutableStateOf(backup.getBoolean("dismissed", false))
        private set
    val downloads = RegionDownloads(app, { core }, { changed() }, ::backupDownload)

    private fun loadBackup(): BackupStatus? {
        val tree = backup.getString("tree", null)?.let(Uri::parse) ?: return null
        val root = backup.getString("root", null) ?: return null
        val records = runCatching { JSONObject(backup.getString("copies", "{}")!!) }.getOrDefault(JSONObject())
        val copies = records.keys().asSequence().mapNotNull { id ->
            val record = records.optJSONObject(id) ?: return@mapNotNull null
            id to MapLibrary.Copy(record.optString("version"), record.optString("folder"))
        }.toMap()
        return BackupStatus(tree, root, copies, backup.getLong("lastSuccess", 0),
            backup.getStringSet("failed", emptySet()).orEmpty().toSet())
    }

    private fun saveBackup(value: BackupStatus): Boolean {
        val copies = JSONObject()
        value.copies.forEach { (id, copy) ->
            copies.put(id, JSONObject().put("version", copy.version).put("folder", copy.folderId))
        }
        return backup.edit().putString("tree", value.tree.toString()).putString("root", value.rootId)
            .putString("copies", copies.toString()).putLong("lastSuccess", value.lastSuccess)
            .putStringSet("failed", value.failed).commit()
    }

    fun dismissBackupSetup() {
        backup.edit().putBoolean("dismissed", true).apply()
        backupSetupDismissed = true
    }

    internal fun linkBackup(tree: Uri, finished: MapLibrary.Backup): Boolean {
        val previous = backup.getString("tree", null)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val next = BackupStatus(tree, finished.rootId, finished.maps, System.currentTimeMillis(), emptySet(),
            finished.maps.values.mapTo(hashSetOf()) { it.folderId })
        val linked = runCatching {
            app.contentResolver.takePersistableUriPermission(tree, flags)
            saveBackup(next)
        }.getOrDefault(false)
        if (linked) {
            backupStatus = next
            if (previous != null && previous != tree.toString()) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
            }
        }
        return linked
    }

    fun stopBackups() {
        val tree = backup.getString("tree", null)
        backup.edit().clear().putBoolean("dismissed", true).apply()
        backupStatus = null
        backupSetupDismissed = true
        if (tree != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(tree), flags) }
        }
    }

    private fun backupDownload(region: String, cancelled: AtomicBoolean, progress: () -> Unit): Boolean {
        val current = core ?: return false
        val linked = loadBackup() ?: return false
        val library = MapLibrary(app, current, MapImport(app, current, cancelled))
        progress()
        val copied = runCatching { library.backupDownload(linked.tree, linked.rootId, region) }
        val copy = copied.getOrNull()
        val updated = if (copy != null) linked.copy(copies = linked.copies + (region to copy),
            lastSuccess = System.currentTimeMillis(), failed = linked.failed - region)
        else linked.copy(failed = linked.failed + region)
        val state = checkBackup(updated, library)
        val recorded = saveBackup(updated)
        main.post { backupStatus = state }
        copied.getOrThrow()
        check(recorded) { "Could not save map backup status." }
        return true
    }

    fun refreshBackup() {
        val current = core ?: return
        val linked = backupStatus ?: return
        val state = checkBackup(linked, MapLibrary(app, current, MapImport(app, current)))
        main.post { if (backupStatus === linked) backupStatus = state }
    }

    private fun checkBackup(linked: BackupStatus, library: MapLibrary): BackupStatus = runCatching {
        check(app.contentResolver.persistedUriPermissions.any {
            it.uri == linked.tree && it.isReadPermission && it.isWritePermission
        })
        linked.copy(available = library.availableCopies(linked.tree, linked.rootId, linked.copies.values), unavailable = false)
    }.getOrElse { linked.copy(available = emptySet(), unavailable = true) }

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
