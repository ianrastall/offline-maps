package io.github.ianrastall.offlinemaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable
fun DownloadsDialog(model: MapModel, initialRegion: String?, onDismiss: () -> Unit) {
    var selectedId by remember { mutableStateOf(initialRegion) }
    var query by remember { mutableStateOf(if (initialRegion == null) model.catalog.find {
        it.getString("id") == model.status?.getJSONArray("regions")?.objects()?.firstOrNull()?.getString("id")
    }?.getString("country") ?: "" else "") }
    val selected = model.catalog.find { it.getString("id") == selectedId }
    val installed = model.status?.getJSONArray("regions")?.objects() ?: emptyList()
    val downloads = model.downloads
    val free = LocalContext.current.noBackupFilesDir.usableSpace
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (selected == null) "Download maps" else selected.getString("name"), Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                    if (selected != null) TextButton(onClick = { selectedId = null }) { Text("Regions") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Text("Downloads continue with the screen off or while using other apps. Pause here or in the notification. If the connection is lost, choose Resume when connected again.", style = MaterialTheme.typography.bodySmall)
                DownloadNotifications()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("Wi-Fi only")
                        Text(downloads.connectionText, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(downloads.wifiOnly, { downloads.configureWifiOnly(it) }, enabled = !downloads.active)
                }
                Text("Free storage: ${sizeText(free)}", style = MaterialTheme.typography.bodySmall)
                if (selected == null) {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        label = { Text("Search regions or countries") }, singleLine = true)
                    val regions = remember(model.catalog, query) {
                        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        model.catalog.filter { r -> words.all { word ->
                            "${r.getString("name")} ${r.getString("country")} ${r.getString("continent")}".contains(word, ignoreCase = true)
                        } }.sortedWith(compareBy({ it.getString("country") }, { it.getString("name") }))
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (regions.isEmpty()) item { Text("No matching regions.", Modifier.padding(vertical = 12.dp)) }
                        items(regions, key = { it.getString("id") }) { region ->
                            val isInstalled = installed.any { it.getString("id") == region.getString("id") }
                            ListItem(headlineContent = { Text(region.getString("name")) },
                                supportingContent = { Text("${region.getString("country")} · about ${sizeText(region.getLong("mapBytes"))}${if (isInstalled) " · installed" else ""}") },
                                modifier = Modifier.clickable { selectedId = region.getString("id") })
                            HorizontalDivider()
                        }
                    }
                } else {
                    val state = installed.find { it.getString("id") == selectedId }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        item {
                            Text(selected.getString("country"), style = MaterialTheme.typography.titleMedium)
                            if (state != null) Text("Map data: ${state.getString("date")}", style = MaterialTheme.typography.bodySmall)
                            if (selected.optString("note").isNotBlank()) Text(selected.getString("note"))
                        }
                        item { DownloadOption(model, selected, 1, "Road map and search", "Includes the offline search index, prepared on this phone.", selected.getLong("mapBytes"), state != null, true) }
                        if (selected.getLong("extrasBytes") > 0) item {
                            DownloadOption(model, selected, 2, "Stations and addresses", "Fuel, charging, street addresses and outdoor places.", selected.getLong("extrasBytes"), state?.optBoolean("extras") == true, state != null,
                                metadataText(state, "extrasDate", "Data date"))
                        }
                        if (selected.getLong("terrainBytes") > 0) item {
                            DownloadOption(model, selected, 3, "Terrain", "Hillshading and contour lines.", selected.getLong("terrainBytes"), state?.optBoolean("terrain") == true, state != null,
                                metadataText(state, "terrainDate", "Archive date"))
                        }
                        if (selected.getLong("overlayBytes") > 0) item {
                            DownloadOption(model, selected, 4, "Flood zones and public lands", "Mapped hazard areas and public-access lands; not current flooding.", selected.getLong("overlayBytes"), state?.optBoolean("overlays") == true, state != null,
                                listOfNotNull(metadataText(state, "floodEffective", "Flood effective date"), metadataText(state, "publicLands", "Public lands"), metadataText(state, "overlayBuilt", "Layer build")).joinToString("\n").ifEmpty { null })
                        }
                        if (state == null) item { Text("Download the road map before adding optional layers.", style = MaterialTheme.typography.bodySmall) }
                        item { Text("Maps: © OpenStreetMap contributors, from Geofabrik or this project's releases. Terrain: Mapterhorn. US flood zones and public lands: FEMA and USGS.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (downloads.active) {
                    DownloadProgress(downloads)
                    Row {
                        Text(downloads.message, Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(onClick = { downloads.pause() }) { Text("Pause") }
                    }
                } else downloads.pending?.let { request ->
                    val region = model.catalog.find { it.getString("id") == request.region }
                    if (region != null) Row {
                        Text("Unfinished: ${region.getString("name")}", Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(onClick = { selectedId = request.region; model.startDownload(region, request.target) }) { Text("Resume") }
                    }
                }
                downloads.error?.let { message ->
                    Row { Text(message, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { downloads.error = null }) { Text("Dismiss") } }
                }
                downloads.notice?.let { message ->
                    Row { Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { downloads.notice = null }) { Text("Dismiss") } }
                }
            }
        }
    }
}

@Composable
private fun DownloadOption(model: MapModel, region: JSONObject, target: Int, title: String, detail: String, bytes: Long, installed: Boolean, available: Boolean, installedDetail: String? = null) {
    val resume = model.downloads.pending == DownloadRequest(region.getString("id"), target)
    Column {
        Row {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text("About ${sizeText(bytes)}${if (installed) " · installed" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { model.startDownload(region, target) }, enabled = available && !model.hasWork) {
                Text(if (resume && model.downloads.active) "In progress" else if (resume) "Resume" else if (installed) "Update" else "Download")
            }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
        if (installedDetail != null) Text(installedDetail, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}

private fun metadataText(state: JSONObject?, key: String, label: String): String? =
    state?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { "$label: $it" }

@Composable
fun DownloadProgress(downloads: RegionDownloads) {
    val progress = downloads.fraction
    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}
