package io.github.ianrastall.offlinemaps

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import org.json.JSONObject
import java.io.File

internal const val ALL_REGIONS = "All regions"

internal data class DownloadItem(val request: DownloadRequest, val name: String, val bytes: Long)

internal fun downloadItem(region: JSONObject, target: Int) = DownloadItem(
    DownloadRequest(region.getString("id"), target), region.getString("name"), region.optLong(when (target) {
        1 -> "mapBytes"
        2 -> "extrasBytes"
        4 -> "overlayBytes"
        else -> "terrainBytes"
    }),
)

internal fun planBulkDownload(regions: List<JSONObject>, installed: List<JSONObject>, scope: String): List<DownloadItem> {
    val existing = installed.associateBy { it.getString("id") }
    val selected = regions.filter { scope == ALL_REGIONS || it.getString("country") == scope }
    return listOf(1, 2, 4, 3).flatMap { target ->
        selected.mapNotNull { region ->
            val item = downloadItem(region, target)
            val state = existing[item.request.region]
            val ready = when (target) {
                1 -> state != null
                2 -> state?.optBoolean("extras") == true
                4 -> state?.optBoolean("overlays") == true
                else -> state?.optBoolean("terrain") == true
            }
            item.takeIf { !ready && it.bytes > 0 && (target == 1 || state != null || region.optLong("mapBytes") > 0) }
        }
    }
}

internal fun readDownloadStorage(context: Context): List<String> {
    fun describe(label: String, folder: File?): String {
        val size = folder?.totalSpace ?: 0L
        return if (folder != null && size > 0) "$label · ${sizeText(folder.usableSpace)} free of ${sizeText(size)}" else "$label · Unavailable"
    }
    val manager = context.getSystemService(StorageManager::class.java)
    val maps = File(context.noBackupFilesDir, "maps")
    return buildList {
        add(describe("Map storage", maps.takeIf { it.isDirectory } ?: context.noBackupFilesDir))
        val appFolders = if (Build.VERSION.SDK_INT < 30) context.getExternalFilesDirs(null).filterNotNull() else emptyList()
        manager.storageVolumes.filterNot { it.isPrimary && it.isEmulated }.forEach { volume ->
            val path = if (Build.VERSION.SDK_INT >= 30) volume.directory else
                appFolders.firstOrNull { manager.getStorageVolume(it) == volume }
            add(describe(volume.getDescription(context), path))
        }
    }
}
