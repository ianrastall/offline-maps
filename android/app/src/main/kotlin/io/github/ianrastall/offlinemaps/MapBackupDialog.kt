package io.github.ianrastall.offlinemaps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun MapBackupDialog(model: MapModel, onBackUp: () -> Unit, onClose: () -> Unit) {
    val backup = model.mapBackup
    val regions = model.status?.optJSONArray("regions")?.objects().orEmpty()
    val backedUp = regions.count(model::isBackedUp)
    val hasFailures = regions.any { it.getString("id") in backup?.failed.orEmpty() }
    val summary = when {
        backup == null -> "Automatic backups are off."
        backup.unavailable -> "Backup folder unavailable. Reconnect its storage or choose a folder."
        backup.available == null -> "Checking the backup folder…"
        backup.lastSuccess == 0L -> "Backup status is unknown. Run a backup to check your maps."
        hasFailures -> "A map backup could not finish. Run a backup to try again."
        backedUp < regions.size -> "Some installed maps or layers still need a backup."
        else -> "Automatic backups are on."
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Map backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary)
                if (backup != null && backup.lastSuccess > 0) {
                    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(Date(backup.lastSuccess))
                    Text("Last successful backup · $time")
                }
                if (regions.isNotEmpty() && backup?.available != null && !backup.unavailable && backup.lastSuccess > 0) {
                    Text("$backedUp of ${regions.size} maps backed up")
                    for (region in regions) {
                        val state = if (model.isBackedUp(region)) "Backed up" else "Backup needed"
                        Text("${region.getString("name")} · $state", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Choose a folder in Documents or on an SD card to keep maps after uninstalling. Future downloads are copied there. Run a backup again to include changes to saved places.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (backup != null) {
                    TextButton(onClick = model::stopMapBackups, enabled = !model.hasWork) {
                        Text("Stop automatic map backups")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onBackUp, enabled = !model.hasWork) { Text("Back up maps and places") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
