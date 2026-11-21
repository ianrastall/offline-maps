package io.github.ianrastall.offlinemaps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun DownloadsDialog(model: MapModel, initialRegion: String?, onDismiss: () -> Unit) {
    var selectedId by remember { mutableStateOf(initialRegion) }
    var showUnfinished by remember { mutableStateOf(false) }
    var bulkScope by remember { mutableStateOf<String?>(null) }
    var showStorage by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(if (initialRegion == null) model.catalog.find {
        it.getString("id") == model.status?.getJSONArray("regions")?.objects()?.firstOrNull()?.getString("id")
    }?.getString("country") ?: "" else "") }
    val selected = model.catalog.find { it.getString("id") == selectedId }
    val installed = model.status?.getJSONArray("regions")?.objects() ?: emptyList()
    val downloads = model.downloads
    val unfinished = downloads.pending.filter { it != downloads.activeRequest }.mapNotNull { request ->
        model.catalog.find { it.getString("id") == request.region }?.let { request to it }
    }
    fun resume(request: DownloadRequest, region: JSONObject) {
        showUnfinished = false
        selectedId = request.region
        model.startDownload(region, request.target)
    }
    val context = LocalContext.current
    val storage by produceState(emptyList<String>(), context) {
        while (true) {
            value = withContext(Dispatchers.IO) { readDownloadStorage(context) }
            delay(3_000)
        }
    }
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
                Text(storage.firstOrNull() ?: "Reading map storage…",
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Show storage volumes") { showStorage = true },
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { bulkScope = selected?.getString("country") ?: ALL_REGIONS },
                        enabled = !model.hasWork && model.catalog.isNotEmpty() && model.status != null) { Text("Bulk download") }
                    downloads.pendingBulk?.takeUnless { downloads.active }?.let { scope ->
                        TextButton(onClick = { bulkScope = scope }, enabled = !model.hasWork && model.status != null) {
                            Text("Resume bulk download")
                        }
                    }
                }
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
                }
                if (!downloads.active && unfinished.size == 1) {
                    val (request, region) = unfinished.single()
                    Row {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("Unfinished: ${region.getString("name")}")
                            Text(downloadTargetName(request.target), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { resume(request, region) }, enabled = !model.hasWork) { Text("Resume") }
                    }
                } else if (unfinished.isNotEmpty()) {
                    TextButton(onClick = { showUnfinished = true }) {
                        Text("Unfinished downloads (${unfinished.size})")
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
    bulkScope?.let { scope ->
        BulkDownloadDialog(model, scope, installed, storage) { bulkScope = null }
    }
    if (showStorage) AlertDialog(
        onDismissRequest = { showStorage = false },
        title = { Text("Storage") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(storage) { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = { showStorage = false }) { Text("Done") } },
    )
    if (showUnfinished) AlertDialog(
        onDismissRequest = { showUnfinished = false },
        title = { Text("Unfinished downloads") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(unfinished, key = { (request, _) -> "${request.region}:${request.target}" }) { (request, region) ->
                    ListItem(
                        headlineContent = { Text(region.getString("name")) },
                        supportingContent = { Text(downloadTargetName(request.target)) },
                        trailingContent = {
                            TextButton(onClick = { resume(request, region) }, enabled = !model.hasWork) { Text("Resume") }
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { showUnfinished = false }) { Text("Close") } },
    )
}

@Composable
private fun BulkDownloadDialog(model: MapModel, initialScope: String, installed: List<JSONObject>, storage: List<String>, onDismiss: () -> Unit) {
    var scope by remember(initialScope) { mutableStateOf(initialScope) }
    var chooseCountry by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val countries = remember(model.catalog) { model.catalog.map { it.getString("country") }.distinct().sorted() }
    val matching = remember(countries, query) { countries.filter { it.contains(query.trim(), ignoreCase = true) } }
    val plan = remember(model.catalog, installed, scope) { planBulkDownload(model.catalog, installed, scope) }
    val resume = model.downloads.pendingBulk == scope
    AlertDialog(
        onDismissRequest = { if (chooseCountry) chooseCountry = false else onDismiss() },
        title = { Text(if (chooseCountry) "Download scope" else "Bulk download") },
        text = {
            if (chooseCountry) {
                Column {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Find a country") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        item {
                            ListItem(headlineContent = { Text(ALL_REGIONS) }, modifier = Modifier.clickable {
                                scope = ALL_REGIONS; chooseCountry = false
                            })
                        }
                        items(matching, key = { it }) { country ->
                            ListItem(headlineContent = { Text(country) }, modifier = Modifier.clickable {
                                scope = country; chooseCountry = false
                            })
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { OutlinedButton(onClick = { query = ""; chooseCountry = true }) { Text("Scope · $scope") } }
                    if (plan.isEmpty()) {
                        item { Text("All available layers are already installed.") }
                    } else {
                        item { Text("${plan.size} downloads · about ${sizeText(plan.sumOf { it.bytes })}") }
                        plan.groupBy { it.request.target }.forEach { (target, layer) ->
                            item { Text("${downloadTargetName(target)} · ${layer.size}") }
                        }
                    }
                    items(storage) { Text(it) }
                    item { Text("Estimates exclude search indexes and temporary files. The batch stops if a download cannot finish.") }
                }
            }
        },
        confirmButton = {
            if (!chooseCountry) TextButton(onClick = { model.startBulkDownload(scope); onDismiss() },
                enabled = !model.hasWork && (plan.isNotEmpty() || resume)) {
                Text(when { plan.isEmpty() -> "Done"; resume -> "Resume"; else -> "Start download" })
            }
        },
        dismissButton = { TextButton(onClick = { if (chooseCountry) chooseCountry = false else onDismiss() }) {
            Text(if (chooseCountry) "Back" else "Cancel")
        } },
    )
}

@Composable
private fun DownloadOption(model: MapModel, region: JSONObject, target: Int, title: String, detail: String, bytes: Long, installed: Boolean, available: Boolean, installedDetail: String? = null) {
    val request = DownloadRequest(region.getString("id"), target)
    Column {
        Row {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text("About ${sizeText(bytes)}${if (installed) " · installed" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { model.startDownload(region, target) }, enabled = available && !model.hasWork) {
                Text(when {
                    model.downloads.activeRequest == request -> "In progress"
                    request in model.downloads.pending -> "Resume"
                    installed -> "Update"
                    else -> "Download"
                })
            }
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
        if (installedDetail != null) Text(installedDetail, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}

internal fun downloadTargetName(target: Int): String = when (target) {
    2 -> "Stations and addresses"
    3 -> "Terrain"
    4 -> "Flood zones and public lands"
    else -> "Road map and search"
}

private fun metadataText(state: JSONObject?, key: String, label: String): String? =
    state?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }?.let { "$label: $it" }

@Composable
fun DownloadProgress(downloads: RegionDownloads) {
    val progress = downloads.fraction
    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}
