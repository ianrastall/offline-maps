package io.github.ianrastall.offlinemaps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable
fun SavedPlacesDialog(model: MapModel, onShow: (JSONObject) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    val places = model.status?.getJSONArray("places")?.objects() ?: emptyList()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::previewPlaces) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(model::exportPlaces) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Saved places", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { editing = JSONObject() }, enabled = model.busy == null) { Text("Add place") }
                    TextButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !model.hasWork) { Text("Import") }
                    TextButton(onClick = { save.launch("OfflineMaps-saved-places-${java.time.LocalDate.now()}.json") }, enabled = !model.hasWork && places.isNotEmpty()) { Text("Export") }
                }
                Text("Backups include names, notes and coordinates. Imports keep existing places and skip exact duplicates.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Filter saved places") }, singleLine = true)
                Text(countText(places.size, "saved place"), style = MaterialTheme.typography.bodySmall)
                PlaceMessages(model)
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    val filtered = places.filter { "${it.getString("name")} ${it.optString("note")}".contains(query.trim(), ignoreCase = true) }
                    if (places.isEmpty()) item { Text("Add coordinates, use Save GPS, save a search result, or press and hold the map.", Modifier.padding(vertical = 12.dp)) }
                    else if (filtered.isEmpty()) item { Text("No matching saved places.", Modifier.padding(vertical = 12.dp)) }
                    items(filtered, key = { it.getLong("id") }) { place ->
                        ListItem(headlineContent = { Text(place.getString("name")) },
                            supportingContent = { Column {
                                if (place.optString("note").isNotBlank()) Text(place.getString("note"))
                                if (!place.optBoolean("covered")) Text("Outside installed detailed maps")
                            } },
                            trailingContent = { TextButton(onClick = { editing = place }, enabled = model.busy == null) { Text("Edit") } },
                            modifier = Modifier.clickable { onShow(place) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editing?.let { place -> PlaceEditor(model, place, onDismiss = { editing = null }) }
    model.placesPreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (model.busy == null) model.cancelPlacesImport() }, title = { Text("Import saved places?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${countText(preview.getInt("added"), "new place")}. ${countText(preview.getInt("skipped"), "exact duplicate")} will be skipped. Existing places stay unchanged.")
                Text("Entries with a changed name or note are kept separately.", style = MaterialTheme.typography.bodySmall)
                PlaceMessages(model)
            } },
            confirmButton = { TextButton(onClick = model::importPlaces, enabled = model.busy == null) { Text("Import places") } },
            dismissButton = { TextButton(onClick = model::cancelPlacesImport, enabled = model.busy == null) { Text("Cancel") } })
    }
}

fun countText(count: Int, singular: String) = "$count $singular${if (count == 1) "" else "s"}"

@Composable
private fun PlaceMessages(model: MapModel) {
    model.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.bodySmall) }
    model.error?.let { message -> Row {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { model.error = null }) { Text("Dismiss") }
    } }
    model.notice?.let { message -> Row {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { model.notice = null }) { Text("Dismiss") }
    } }
}

@Composable
fun PlaceEditor(model: MapModel, initial: JSONObject, onDismiss: () -> Unit) {
    val id = initial.optLong("id").takeIf { initial.has("id") }
    var name by remember(initial) { mutableStateOf(initial.optString("name")) }
    var note by remember(initial) { mutableStateOf(initial.optString("note")) }
    var latitude by remember(initial) { mutableStateOf(if (initial.has("latitude")) initial.getDouble("latitude").toString() else "") }
    var longitude by remember(initial) { mutableStateOf(if (initial.has("longitude")) initial.getDouble("longitude").toString() else "") }
    var attempted by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val lat = latitude.trim().toDoubleOrNull()
    val lon = longitude.trim().toDoubleOrNull()
    val problem = when {
        name.trim().isEmpty() -> "Enter a name."
        name.trim().codePointCount(0, name.trim().length) > 120 || name.any { it.isISOControl() } -> "Use at most 120 characters without control characters for the name."
        note.trim().codePointCount(0, note.trim().length) > 2_000 -> "Use at most 2,000 characters for the note."
        lat == null || !lat.isFinite() || lat !in -90.0..90.0 -> "Latitude must be between -90 and 90."
        lon == null || !lon.isFinite() || lon !in -180.0..180.0 -> "Longitude must be between -180 and 180."
        else -> null
    }
    Dialog(onDismissRequest = { if (model.busy == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp)) {
                Text(if (id == null) "Save place" else "Edit place", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, minLines = 3)
                    Text("Decimal degrees: north/east positive, south/west negative.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(latitude, { latitude = it }, Modifier.fillMaxWidth(), label = { Text("Latitude") }, singleLine = true, readOnly = id != null)
                    OutlinedTextField(longitude, { longitude = it }, Modifier.fillMaxWidth(), label = { Text("Longitude") }, singleLine = true, readOnly = id != null)
                    if (attempted && problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                    PlaceMessages(model)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (id != null) TextButton(onClick = { removing = true }, enabled = model.busy == null) { Text("Remove") }
                    TextButton(onClick = onDismiss, enabled = model.busy == null) { Text("Cancel") }
                    Button(onClick = {
                        attempted = true
                        if (problem == null) model.save(name.trim(), note.trim(), lat!!, lon!!, id, onDismiss)
                    }, enabled = model.busy == null) { Text("Save") }
                }
            }
        }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove saved place?") },
        text = { Text(initial.getString("name")) },
        confirmButton = { TextButton(onClick = { model.remove(initial); removing = false; onDismiss() }) { Text("Remove place") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep place") } })
}
