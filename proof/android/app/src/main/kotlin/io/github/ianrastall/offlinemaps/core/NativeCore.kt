package io.github.ianrastall.offlinemaps.core

/**
 * The Offline Maps core (Rust, `crates/offline-maps-android`). A core is opened on a map folder
 * and identified by the handle `open` returns. Calls may come from several threads; `close`
 * must not overlap another call. Failures arrive as RuntimeExceptions.
 */
object NativeCore {
    const val TILE_MAP = 0
    const val TILE_OVERVIEW = 1
    const val TILE_TERRAIN = 2
    const val TILE_OVERLAY = 3

    init {
        System.loadLibrary("offline_maps_android")
    }

    /** Opens the map folder [store] (created if missing); [overview] is the overview file or "". */
    @JvmStatic external fun open(store: String, overview: String): Long

    /** What is installed, as the map page's init members: metadata, terrain, overlays. */
    @JvmStatic external fun statusJson(handle: Long, overview: Boolean): String

    /** A tile's bytes, empty where there is none. [kind] is one of the TILE_ constants. */
    @JvmStatic external fun tile(handle: Long, kind: Int, z: Int, x: Int, y: Int): ByteArray

    /** Stations and outdoor places as a GeoJSON FeatureCollection. */
    @JvmStatic external fun stations(handle: Long): String

    @JvmStatic external fun close(handle: Long)
}
