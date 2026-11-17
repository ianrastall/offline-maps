package io.github.ianrastall.offlinemaps

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun DownloadNotifications() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember { context.getSharedPreferences("downloads", 0) }
    var enabled by remember { mutableStateOf(DownloadService.notificationsEnabled(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = DownloadService.notificationsEnabled(context)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = DownloadService.notificationsEnabled(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!enabled) Column {
        Text("Notifications are off. Enable them for download progress and Pause controls outside the app.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationsAsked", false)) {
                prefs.edit().putBoolean("notificationsAsked", true).apply()
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("Enable notifications") }
    }
}
