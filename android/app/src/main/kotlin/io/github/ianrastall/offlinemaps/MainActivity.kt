package io.github.ianrastall.offlinemaps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: MapModel by viewModels()
    private val handler = Handler(Looper.getMainLooper())
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private var resumed = false
    private val satelliteStatus = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            model.satellites = status.satelliteCount
            model.satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
            if (model.gpsWanted) updateFix()
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (precise()) { model.gpsWanted = true; model.following = true; startGps() }
        else { model.gpsWanted = false; model.gpsText = "Precise location permission is needed. Tap here for app settings." }
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!model.gpsWanted || fixAge(location) > 30_000 || !location.hasAccuracy()
                || !location.accuracy.isFinite() || location.accuracy < 0
                || !location.latitude.isFinite() || !location.longitude.isFinite()
                || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
            model.fix = Location(location)
            updateFix()
        }
        override fun onProviderDisabled(provider: String) { model.gpsText = "Device location is off. Open location settings."; model.position(stale = true) }
        override fun onProviderEnabled(provider: String) { model.gpsText = "Waiting for satellites…" }
    }
    private val tick = object : Runnable {
        override fun run() { if (model.gpsWanted) updateFix(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen() } }
    }

    private fun precise() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun startGps() {
        if (!resumed || !model.gpsWanted) return
        if (!precise()) {
            model.gpsWanted = false; model.following = false; model.fix = null; model.position()
            model.gpsText = "Precise location permission is needed."
            return
        }
        locations.removeUpdates(listener)
        locations.unregisterGnssStatusCallback(satelliteStatus)
        model.satellites = 0; model.satellitesUsed = 0
        if (LocationManager.GPS_PROVIDER !in locations.allProviders) { model.gpsText = "This device has no satellite GPS provider."; return }
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) { model.gpsText = "Device location is off. Open location settings."; return }
        model.gpsText = "Waiting for satellites… First fix may take several minutes offline."
        try {
            // The satellite provider works without Play Services or a network location service.
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            locations.registerGnssStatusCallback(satelliteStatus, handler)
        } catch (e: SecurityException) {
            model.gpsWanted = false; model.gpsText = "Location permission was revoked."
        }
    }
    private fun updateFix() {
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            model.gpsText = "Device location is off. Open location settings."
            model.position(stale = true)
            return
        }
        val fix = model.fix ?: run {
            model.gpsText = "Waiting for satellites · ${model.satellites} visible, ${model.satellitesUsed} used. A first fix may take several minutes offline."
            return
        }
        val ageMs = fixAge(fix)
        val age = ageMs / 1000
        model.gpsText = if (ageMs > 30_000) "No current GPS fix · last fix ${age}s ago"
        else String.format(Locale.US, "%.5f, %.5f · ±%.0f m · %ds ago%s", fix.latitude, fix.longitude,
            fix.accuracy, age, if (fix.hasBearing()) " · travel ${fix.bearing.toInt()}°" else "")
        model.position(stale = ageMs > 30_000)
    }
    private fun toggleGps() {
        if (model.gpsWanted) {
            locations.removeUpdates(listener)
            locations.unregisterGnssStatusCallback(satelliteStatus)
            model.gpsWanted = false; model.following = false; model.fix = null; model.gpsText = "GPS off"; model.position()
        } else if (precise()) { model.gpsWanted = true; model.following = true; startGps() }
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    override fun onResume() { super.onResume(); resumed = true; model.downloads.foreground(true); startGps(); handler.post(tick) }
    override fun onPause() {
        resumed = false; handler.removeCallbacks(tick); locations.removeUpdates(listener)
        model.downloads.foreground(false)
        locations.unregisterGnssStatusCallback(satelliteStatus)
        model.position(stale = true)
        super.onPause()
    }
    private fun currentFix() = model.fix?.takeIf {
        model.gpsWanted && fixAge(it) <= 30_000 && locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    @Composable
    private fun Screen() {
        var panel by remember { mutableStateOf<String?>(null) }
        var saveAt by remember { mutableStateOf<JSONObject?>(null) }
        var saveName by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<JSONObject?>(null) }
        var downloadRegion by remember { mutableStateOf<String?>(null) }
        val keyboard = LocalSoftwareKeyboardController.current
        val picker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) model.importMap(uri)
        }
        val ready = model.status != null
        val current = currentFix()
        SideEffect {
            if ((model.gpsWanted && model.following) || model.busy != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Offline Maps", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { panel = "maps" }, enabled = ready) { Text("Maps") }
                    TextButton(onClick = { panel = "saved" }, enabled = ready) { Text("Saved") }
                    TextButton(onClick = { panel = "about" }) { Text("About") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(model.query, { model.query = it.take(200) }, Modifier.weight(1f),
                        label = { Text("Search offline") }, singleLine = true, enabled = ready,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { model.search(); keyboard?.hide() }))
                    TextButton(onClick = { model.search(); keyboard?.hide() }, enabled = ready && !model.searching) { Text("Find") }
                    if (model.results.isNotEmpty()) TextButton(onClick = { model.results = emptyList() }) { Text("Close") }
                }
                if (model.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                model.searchHint?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                if (model.results.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    items(model.results) { result ->
                        ListItem(headlineContent = { Text(result.getString("name")) },
                            supportingContent = { Text(listOf(result.optString("category").replace('_', ' '), result.optString("near"), result.optString("detail")).filter { it.isNotBlank() }.joinToString(" · ")) },
                            modifier = Modifier.clickable { model.show(result); selected = result; model.results = emptyList() })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (ready && !model.rendererRetryRequired) key(model.rendererGeneration) { AndroidView(factory = {
                        WebMap(this@MainActivity, model.core!!, model::initMessage, object : WebMap.Listener {
                            override fun onView(latitude: Double, longitude: Double, zoom: Double) = model.moved(latitude, longitude, zoom)
                            override fun onIdle(milliseconds: Long) {
                                model.rendererReady()
                                model.page?.post(JSONObject().put("kind", "places").put("items", model.status!!.getJSONArray("places")))
                                model.position(stale = !resumed || currentFix() == null)
                            }
                            override fun onError(message: String) { model.error = message }
                            override fun onGesture() { model.following = false }
                            override fun onRegion(id: String) { downloadRegion = id; panel = "downloads" }
                            override fun onRendererGone() = model.rendererStopped()
                            override fun onSave(latitude: Double, longitude: Double) {
                                model.following = false
                                saveAt = JSONObject().put("latitude", latitude).put("longitude", longitude)
                                saveName = ""
                            }
                            override fun onPlace(id: Long) {
                                selected = model.status!!.getJSONArray("places").objects().find { it.getLong("id") == id }
                            }
                        }).also { model.page = it; it.load() }
                    }, modifier = Modifier.fillMaxSize(), onRelease = { page ->
                        if (model.page === page) model.page = null
                        page.close()
                    }) }
                    if (model.rendererRecovering) {
                        Surface(Modifier.align(Alignment.Center)) {
                            Text("Reloading map…", Modifier.padding(12.dp))
                        }
                    }
                    if (model.rendererRetryRequired) {
                        Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("The map renderer stopped.")
                            TextButton(onClick = model::retryRenderer) { Text("Reload map") }
                        }
                    }
                }
                selected?.let { place ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(place.optString("name"), Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(onClick = { saveAt = place; saveName = place.optString("name") }) { Text(if (place.has("id")) "Edit" else "Save") }
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }
                }
                (model.error ?: model.downloads.error)?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(message, Modifier.weight(1f).padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { model.error = null; model.downloads.error = null }) { Text("Dismiss") }
                    }
                }
                model.workText?.let { phase ->
                    if (model.downloads.active) DownloadProgress(model.downloads) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(phase, Modifier.weight(1f).padding(vertical = 12.dp))
                        if (ready && model.canCancelWork) TextButton(onClick = { model.cancelWork() }) { Text(if (model.downloads.active) "Pause" else "Cancel") }
                    }
                }
                (model.notice ?: model.downloads.notice)?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.notice = null; model.downloads.notice = null }) { Text("Dismiss") }
                    }
                }
                Text(model.gpsText, Modifier.fillMaxWidth().clickable {
                    if (model.gpsWanted && !locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    else if (!precise()) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = ::toggleGps, enabled = ready) { Text(if (model.gpsWanted) "GPS off" else "GPS on") }
                    TextButton(onClick = { model.following = !model.following; model.position() }, enabled = current != null) { Text(if (model.following) "Following" else "Follow") }
                    TextButton(onClick = {
                        current?.let { saveAt = JSONObject().put("latitude", it.latitude).put("longitude", it.longitude); saveName = "" }
                    }, enabled = current != null) { Text("Save GPS") }
                    TextButton(onClick = { model.page?.post(JSONObject().put("kind", "fit")) }, enabled = ready) { Text("Fit") }
                }
            }
        }
        if (panel == "maps") AlertDialog(onDismissRequest = { panel = null }, title = { Text("Offline maps") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val regions = model.status!!.getJSONArray("regions").objects()
                    if (regions.isEmpty()) Text("No detailed maps installed. Download a region while online, or import a prepared folder from the desktop.")
                    regions.forEach { region ->
                        Text("${region.getString("name")} · ${region.optString("date")}\n" +
                            listOfNotNull("Roads and search", "Stations and addresses".takeIf { region.getBoolean("extras") },
                                "Terrain".takeIf { region.getBoolean("terrain") }, "Flood zones and public lands".takeIf { region.getBoolean("overlays") }).joinToString(" · "),
                            Modifier.fillMaxWidth().clickable { downloadRegion = region.getString("id"); panel = "downloads" }.padding(vertical = 6.dp))
                    }
                    TextButton(onClick = { panel = null; picker.launch(null) }, enabled = !model.hasWork) { Text("Import map folder") }
                    Row { Checkbox(model.layers, { model.toggleLayers(it) }); Text("Show optional map layers", Modifier.padding(top = 12.dp)) }
                }
            }, confirmButton = { TextButton(onClick = { downloadRegion = null; panel = "downloads" }) { Text("Download maps") } },
            dismissButton = { TextButton(onClick = { panel = null }) { Text("Close") } })
        if (panel == "downloads") DownloadsDialog(model, downloadRegion) { panel = null }
        model.transferReview?.let { TransferReview(model, it) }
        if (panel == "saved") SavedPlacesDialog(model,
            onShow = { place -> model.show(place); selected = place; panel = null },
            onDismiss = { model.cancelPlacesImport(); panel = null })
        saveAt?.let { at ->
            val initial = remember(at) { JSONObject(at.toString()).put("name", saveName) }
            PlaceEditor(model, initial) { saveAt = null; if (initial.has("id")) selected = null }
        }
        if (panel == "about") AlertDialog(onDismissRequest = { panel = null }, title = { Text("Offline Maps ${BuildConfig.VERSION_NAME}") },
            text = { Text(remember { assets.open("notices/THIRD-PARTY-NOTICES.txt").bufferedReader().use { it.readText() } }, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { panel = null }) { Text("Close") } })
    }
}

fun fixAge(location: Location): Long = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
