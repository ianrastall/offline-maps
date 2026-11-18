package io.github.ianrastall.offlinemaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun TransferReview(model: MapModel, review: JSONObject) {
    val incoming = review.getJSONObject("incoming")
    val current = review.optJSONObject("current")
    val replacing = review.getBoolean("replacing")
    val date = incoming.optString("date")
    val installedDate = current?.optString("date").orEmpty()
    val knownDate = Regex("\\d{4}-\\d{2}-\\d{2}")
    val older = replacing && knownDate.matches(date) && knownDate.matches(installedDate) && date < installedDate
    AlertDialog(onDismissRequest = model::discardTransfer,
        title = { Text(if (replacing) "Update ${incoming.getString("name")}?" else "Import ${incoming.getString("name") }?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${sizeText(review.getLong("bytes"))} copied and verified. No internet connection is needed.")
                Text("Folder map date: ${date.ifBlank { "Unknown" }}")
                if (replacing) Text("Installed map date: ${installedDate.ifBlank { "Unknown" }}")
                if (older) Text("The folder contains an older map than the installed one.", color = MaterialTheme.colorScheme.error)
                Text("Included: road map and search")
                if (incoming.optBoolean("extras")) Text("Stations and addresses · ${incoming.optString("extrasDate").ifBlank { "date unknown" }}")
                if (incoming.optBoolean("terrain")) Text("Terrain · ${incoming.optString("terrainDate").ifBlank { "date unknown" }}")
                if (incoming.optBoolean("overlays")) Text("Flood zones and public lands · flood effective ${incoming.optString("floodEffective").ifBlank { "date unknown" }}")
                Text("Optional layers not included in this folder are kept. Saved places stay unchanged.", style = MaterialTheme.typography.bodySmall)
                Text("The current region stays available until the verified replacement is committed.", style = MaterialTheme.typography.bodySmall)
                model.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it) }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = model::confirmTransfer, enabled = model.busy == null) {
            Text(if (older) "Replace with older map" else if (replacing) "Update region" else "Import region")
        } },
        dismissButton = { TextButton(onClick = model::discardTransfer) { Text(if (model.busy == null) "Cancel" else "Cancel import") } })
}
