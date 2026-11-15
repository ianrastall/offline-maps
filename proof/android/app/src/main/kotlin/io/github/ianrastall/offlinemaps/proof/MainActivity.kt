package io.github.ianrastall.offlinemaps.proof

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Renderer proof: the installed maps drawn by the Windows app's map page in Android's WebView,
 * or by MapLibre Native, switched with the two buttons and kept on the same view.
 *
 * Maps are read from the app's private storage (files/maps), laid out as the Windows app's map
 * folder, and copied there for testing with `adb push` to /data/local/tmp and `run-as ... cp`.
 * (Files `adb` writes into Android/data/<package> belong to the shell user, and the app cannot
 * read them.) Launch extras, for repeatable comparisons:
 * `mode` (web or native), `lat`, `lon`, `zoom` and `theme` (light or dark).
 */
class MainActivity : Activity() {
    private lateinit var frame: FrameLayout
    private lateinit var info: TextView
    private var core = 0L
    private var status: JSONObject? = null
    private var web: WebMap? = null
    private var native: NativeMap? = null
    private var mode = "web"
    private var theme = "dark"
    private var view: Triple<Double, Double, Double>? = null
    private val timings = linkedMapOf<String, String>()
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        readExtras(intent)
        // A map in use stays on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frame = FrameLayout(this)
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(200, 28, 27, 25))
            setPadding(16, 8, 16, 8)
            textSize = 12f
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(28, 27, 25))
            addView(Button(context).apply { text = "WebView"; setOnClickListener { show("web") } })
            addView(Button(context).apply { text = "MapLibre Native"; setOnClickListener { show("native") } })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(info, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        report("Opening the map folder…")
        worker.execute {
            val result = runCatching { openCore() }
            runOnUiThread {
                result.onSuccess { show(mode) }.onFailure { report("The map core could not be opened: ${it.message}") }
            }
        }
    }

    /** Copies the overview out of the APK (the core reads it as a file) and opens the map folder. */
    private fun openCore() {
        val overview = File(noBackupFilesDir, "world-overview.mbtiles")
        val size = assets.openFd("overview/world-overview.mbtiles").use { it.length }
        if (overview.length() != size) {
            assets.open("overview/world-overview.mbtiles").use { input -> overview.outputStream().use { input.copyTo(it) } }
        }
        val maps = File(filesDir, "maps")
        core = NativeCore.open(maps.absolutePath, overview.absolutePath)
        status = JSONObject(NativeCore.statusJson(core, true))
        Log.i(TAG, "maps: ${maps.absolutePath} status: $status")
    }

    private fun readExtras(intent: Intent) {
        intent.getStringExtra("mode")?.let { mode = it }
        intent.getStringExtra("theme")?.let { theme = it }
        if (intent.hasExtra("lat") && intent.hasExtra("lon") && intent.hasExtra("zoom")) {
            view = Triple(
                intent.getFloatExtra("lat", 0f).toDouble(),
                intent.getFloatExtra("lon", 0f).toDouble(),
                intent.getFloatExtra("zoom", 0f).toDouble(),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
        if (core == 0L) return
        show(mode)
        view?.let { (lat, lon, zoom) ->
            native?.moveTo(lat, lon, zoom)
            web?.post(JSONObject().put("kind", "center").put("latitude", lat).put("longitude", lon).put("zoom", zoom))
        }
    }

    private fun show(target: String) {
        mode = target
        val status = status ?: return
        if (target == "native") {
            val map = native ?: NativeMap(this, core, object : NativeMap.Listener {
                override fun onView(latitude: Double, longitude: Double, zoom: Double) = moved("native", latitude, longitude, zoom)
                override fun onIdle(milliseconds: Long) = timing("MapLibre Native first idle", "$milliseconds ms")
            }).also {
                native = it
                frame.addView(it.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                it.view.onStart()
                it.view.onResume()
            }
            if (!map.loaded) {
                val style = NativeStyle.build(assets, theme, status)
                map.load(style, status.getJSONObject("metadata").getJSONObject("bounds"), view?.let { NativeMap.position(it.first, it.second, it.third) })
            } else {
                view?.let { (lat, lon, zoom) -> map.moveTo(lat, lon, zoom) }
            }
            map.view.visibility = View.VISIBLE
            web?.view?.visibility = View.GONE
        } else {
            val page = web ?: WebMap(this, core, ::initMessage, object : WebMap.Listener {
                override fun onView(latitude: Double, longitude: Double, zoom: Double) = moved("web", latitude, longitude, zoom)
                override fun onIdle(milliseconds: Long) = timing("WebView first idle", "$milliseconds ms")
                override fun onError(message: String) = timing("WebView error", message)
            }).also {
                web = it
                frame.addView(it.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                it.load()
            }
            view?.let { (lat, lon, zoom) ->
                page.post(JSONObject().put("kind", "center").put("latitude", lat).put("longitude", lon).put("zoom", zoom))
            }
            page.view.visibility = View.VISIBLE
            native?.view?.visibility = View.GONE
        }
        report()
    }

    /** The page's init message, as the Windows shell sends it. */
    private fun initMessage(): JSONObject {
        val status = status!!
        val dark = theme == "dark"
        val message = JSONObject()
            .put("kind", "init")
            .put("metadata", status.getJSONObject("metadata"))
            .put("theme", theme)
            .put("colors", JSONObject()
                .put("window", if (dark) "#1C1B19" else "#F9F8F6")
                .put("surface", if (dark) "#24221F" else "#FFFFFF")
                .put("separator", if (dark) "#3B3732" else "#DAD6CE")
                .put("text", if (dark) "#E8E4DC" else "#1C1B19")
                .put("secondary", if (dark) "#B8B2A8" else "#5E5A54")
                .put("accent", "#0078D4")
                .put("accent-text", "#FFFFFF"))
            .put("animate", true)
            .put("units", "imperial")
            .put("terrain", status.opt("terrain"))
            .put("outdoor", true)
            .put("overlays", status.opt("overlays"))
            .put("flood", true)
            .put("lands", true)
            .put("shading", true)
            .put("contours", true)
        view?.let { (lat, lon, zoom) -> message.put("view", JSONObject().put("latitude", lat).put("longitude", lon).put("zoom", zoom)) }
        return message
    }

    private fun moved(source: String, latitude: Double, longitude: Double, zoom: Double) {
        if (source == mode) view = Triple(latitude, longitude, zoom)
        report()
    }

    private fun timing(name: String, value: String) {
        Log.i(TAG, "$name: $value")
        timings[name] = value
        report()
    }

    private fun report(text: String? = null) {
        val lines = mutableListOf(text ?: "Showing: ${if (mode == "native") "MapLibre Native" else "WebView"} ($theme)")
        view?.let { (lat, lon, zoom) -> lines += "View: %.5f, %.5f, zoom %.2f".format(lat, lon, zoom) }
        timings.forEach { (k, v) -> lines += "$k: $v" }
        info.text = lines.joinToString("\n")
    }

    override fun onStart() { super.onStart(); native?.view?.onStart() }
    override fun onResume() { super.onResume(); native?.view?.onResume() }
    override fun onPause() { native?.view?.onPause(); super.onPause() }
    override fun onStop() { native?.view?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); native?.view?.onLowMemory() }

    override fun onDestroy() {
        native?.view?.onDestroy()
        web?.view?.destroy()
        worker.shutdown()
        if (core != 0L) NativeCore.close(core)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OMProof"
    }
}
