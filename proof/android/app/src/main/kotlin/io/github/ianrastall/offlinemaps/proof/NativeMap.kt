package io.github.ianrastall.offlinemaps.proof

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.ianrastall.offlinemaps.core.NativeCore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * The same map drawn by MapLibre Native. Glyphs and sprites are read from the APK
 * (asset://web/...); tiles and stations are answered for https://map.local/ from the core in
 * MapLibre's own HTTP client, which refuses every other address.
 */
class NativeMap(context: Context, core: Long, private val listener: Listener) {
    interface Listener {
        fun onView(latitude: Double, longitude: Double, zoom: Double)
        fun onIdle(milliseconds: Long)
    }

    val view: MapView
    private var map: MapLibreMap? = null
    private var started = 0L
    private var idleReported = false

    init {
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addInterceptor(MapLocalInterceptor(core)).build())
        view = MapView(context)
        view.onCreate(null)
        view.addOnDidBecomeIdleListener {
            if (!idleReported && started != 0L) {
                idleReported = true
                listener.onIdle(SystemClock.elapsedRealtime() - started)
            }
        }
    }

    val loaded get() = started != 0L

    fun load(styleJson: String, bounds: JSONObject, camera: CameraPosition?) {
        started = SystemClock.elapsedRealtime()
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.setMaxZoomPreference(19.0)
            map.addOnCameraIdleListener {
                val c = map.cameraPosition
                c.target?.let { listener.onView(it.latitude, it.longitude, c.zoom) }
            }
            view.addOnStyleImageMissingListener { id -> map.style?.let { MapIcons.add(it, id) } }
            if (camera != null) {
                map.cameraPosition = camera
            } else {
                val box = LatLngBounds.from(
                    bounds.getDouble("north"), bounds.getDouble("east"), bounds.getDouble("south"), bounds.getDouble("west"),
                )
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(box, 48))
            }
            map.setStyle(Style.Builder().fromJson(styleJson)) { style -> MapIcons.ids.forEach { MapIcons.add(style, it) } }
        }
    }

    fun moveTo(latitude: Double, longitude: Double, zoom: Double) {
        map?.cameraPosition = position(latitude, longitude, zoom)
    }

    companion object {
        fun position(latitude: Double, longitude: Double, zoom: Double): CameraPosition =
            CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).build()
    }
}

private class MapLocalInterceptor(private val core: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme != "https" || request.url.host != WebMap.HOST) {
            Log.w("OMProof", "native: refused ${request.url}")
            return respond(request, 403, ByteArray(0), "text/plain")
        }
        val path = request.url.encodedPath
        val segments = request.url.pathSegments
        fun tile(kind: Int, type: String): Pair<ByteArray, String> {
            val numbers = segments.drop(1).map { it.substringBefore('.').toIntOrNull() }
            if (numbers.size != 3 || numbers.any { it == null }) return ByteArray(0) to type
            return NativeCore.tile(core, kind, numbers[0]!!, numbers[1]!!, numbers[2]!!) to type
        }
        val (bytes, type) = try {
            when {
                path.startsWith("/tiles/") -> tile(NativeCore.TILE_MAP, "application/x-protobuf")
                path.startsWith("/overview/") -> tile(NativeCore.TILE_OVERVIEW, "application/x-protobuf")
                path.startsWith("/overlays/") -> tile(NativeCore.TILE_OVERLAY, "application/x-protobuf")
                path.startsWith("/terrain/") -> tile(NativeCore.TILE_TERRAIN, "image/webp")
                path == "/extras/stations.geojson" -> NativeCore.stations(core).toByteArray() to "application/geo+json"
                else -> return respond(request, 404, ByteArray(0), "text/plain")
            }
        } catch (e: RuntimeException) {
            Log.w("OMProof", "native: $path failed: ${e.message}")
            return respond(request, 500, ByteArray(0), "text/plain")
        }
        return if (bytes.isEmpty()) respond(request, 204, bytes, type) else respond(request, 200, bytes, type)
    }

    private fun respond(request: Request, code: Int, bytes: ByteArray, type: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Status $code")
            .header("Cache-Control", "no-store")
            .body(bytes.toResponseBody(type.toMediaType()))
            .build()
}
