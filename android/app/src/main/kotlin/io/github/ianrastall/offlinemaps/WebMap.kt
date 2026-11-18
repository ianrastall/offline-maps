package io.github.ianrastall.offlinemaps

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * The Windows app's map page (web/) in Android's WebView, unchanged. As in the Windows shell,
 * everything is answered locally for https://map.local/: the page and its assets from the APK,
 * tiles and stations from the core. Every other address is refused.
 *
 * The page talks to its host through `window.chrome.webview` (WebView2). A script added at
 * document start provides the same object over a WebMessageListener.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class WebMap(
    context: Context,
    private val core: CoreStore,
    private val init: () -> JSONObject,
    private val listener: Listener,
) : FrameLayout(context) {
    interface Listener {
        fun onView(latitude: Double, longitude: Double, zoom: Double)
        fun onIdle(milliseconds: Long)
        fun onError(message: String)
        fun onGesture()
        fun onSave(latitude: Double, longitude: Double)
        fun onPlace(id: Long)
        fun onRendererGone()
        fun onRegion(id: String)
    }

    private val view = WebView(context)
    private var proxy: JavaScriptReplyProxy? = null
    private var started = 0L
    private val assets = context.assets

    init {
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Stop following before the next satellite update can interrupt a pan.
        // Return false so WebView keeps its normal touch/accessibility handling.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) listener.onGesture()
            false
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(webView: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                close()
                listener.onRendererGone()
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = answer(request.url)

            // Navigation away from the map page is cancelled.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                request.url.scheme != "https" || request.url.host != HOST || request.url.port !in listOf(-1, 443)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(TAG, "page: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        val origins = setOf("https://$HOST")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "omHost", origins) { _, message, _, mainFrame, reply ->
                if (!mainFrame) return@addWebMessageListener
                proxy = reply
                received(message)
            }
        } else error("Update Android System WebView to display the map.")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, SHIM, origins)
        } else error("Update Android System WebView to display the map.")
    }

    fun load() {
        started = SystemClock.elapsedRealtime()
        view.loadUrl("https://$HOST/index.html")
    }

    fun close() {
        if (view.parent !== this) return
        proxy = null
        removeView(view)
        view.destroy()
    }

    fun post(message: JSONObject) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy?.postMessage(message.toString())
    }

    private fun received(message: WebMessageCompat) {
        val data = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        when (data.optString("kind")) {
            "ready" -> post(init())
            "view" -> listener.onView(data.getDouble("latitude"), data.getDouble("longitude"), data.getDouble("zoom"))
            "idle" -> listener.onIdle(SystemClock.elapsedRealtime() - started)
            "error" -> listener.onError(data.optString("message"))
            "gesture" -> listener.onGesture()
            "contextmenu" -> listener.onSave(data.getDouble("latitude"), data.getDouble("longitude"))
            "place" -> listener.onPlace(data.getLong("id"))
            "region" -> listener.onRegion(data.getString("id"))
        }
    }

    private fun answer(uri: Uri): WebResourceResponse? {
        if (uri.scheme == "blob" || uri.scheme == "data") return null
        if (uri.scheme != "https" || uri.host != HOST || uri.port !in listOf(-1, 443)) return empty(403, "Offline")
        val path = uri.path ?: "/"
        return try {
            when {
                path.startsWith("/tiles/") -> tile(NativeCore.TILE_MAP, path, "application/x-protobuf")
                path.startsWith("/overview/") -> tile(NativeCore.TILE_OVERVIEW, path, "application/x-protobuf")
                path.startsWith("/overlays/") -> tile(NativeCore.TILE_OVERLAY, path, "application/x-protobuf")
                path.startsWith("/terrain/") -> tile(NativeCore.TILE_TERRAIN, path, "image/webp")
                path == "/extras/stations.geojson" -> body(core.use { NativeCore.stations(it) }.toByteArray(), "application/geo+json")
                else -> file(path)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "$path failed: ${e.message}")
            empty(500, "Error")
        }
    }

    private fun tile(kind: Int, path: String, type: String): WebResourceResponse {
        val parts = path.substringAfter('/', "").substringAfter('/').substringBeforeLast('.').split('/')
        val (z, x, y) = parts.mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return empty(204, "No Content")
        return body(core.use { NativeCore.tile(it, kind, z, x, y) }, type)
    }

    /** A file of the page from the APK's assets/web; nothing outside it. */
    private fun file(path: String): WebResourceResponse {
        val name = path.trimStart('/')
        if (name.isEmpty() || name.split('/').any { it == ".." || it.isEmpty() }) return empty(404, "Not Found")
        val bytes = try {
            assets.open("web/$name").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            return empty(404, "Not Found")
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "png" -> "image/png"
            "pbf" -> "application/x-protobuf"
            else -> "application/octet-stream"
        }
        return body(bytes, type, cache = "no-cache")
    }

    private fun body(bytes: ByteArray, type: String, cache: String = "no-store"): WebResourceResponse {
        if (bytes.isEmpty()) return empty(204, "No Content")
        val encoding = if (type.startsWith("text/") || type.endsWith("json")) "utf-8" else null
        return WebResourceResponse(type, encoding, 200, "OK", mapOf("Cache-Control" to cache), ByteArrayInputStream(bytes))
    }

    private fun empty(status: Int, reason: String) =
        WebResourceResponse("text/plain", null, status, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val HOST = "map.local"
        private const val TAG = "OfflineMaps"

        /** `window.chrome.webview` as WebView2 provides it, carried over the omHost listener. */
        private val SHIM = """
            (() => {
              const listeners = [];
              // WebView may first lay out inside Compose with zero height. Its
              // percentage/vh heights can retain that value after native layout.
              const size = () => {
                if (document.documentElement && innerHeight > 0)
                  document.documentElement.style.height = innerHeight + 'px';
              };
              addEventListener('DOMContentLoaded', size);
              addEventListener('resize', size);
              omHost.addEventListener('message', (e) => {
                size();
                const data = JSON.parse(e.data);
                for (const f of listeners) f({ data });
              });
              window.chrome = window.chrome || {};
              window.chrome.webview = {
                postMessage: (m) => omHost.postMessage(JSON.stringify(m)),
                addEventListener: (type, f) => { if (type === 'message') listeners.push(f); },
              };
            })();
        """.trimIndent()
    }
}
