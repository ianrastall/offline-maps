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

    /**
     * Stations and outdoor places inside the area as a GeoJSON FeatureCollection;
     * every installed one when the bounds are NaN.
     */
    @JvmStatic external fun stations(handle: Long, west: Double, south: Double, east: Double, north: Double): String

    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun searchJson(handle: Long, query: String, latitude: Double, longitude: Double): String
    @JvmStatic external fun addPlace(handle: Long, name: String, note: String, latitude: Double, longitude: Double)
    @JvmStatic external fun updatePlace(handle: Long, id: Long, name: String, note: String)
    @JvmStatic external fun exportPlaces(handle: Long): String
    @JvmStatic external fun importPlaces(handle: Long, backup: String, apply: Boolean): String
    @JvmStatic external fun removePlace(handle: Long, id: Long)
    @JvmStatic external fun previewTransfer(handle: Long, staged: String): String
    @JvmStatic external fun describeExport(handle: Long, region: String): String
    @JvmStatic external fun importTransfer(handle: Long, staged: String, replace: Boolean): String
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun catalogJson(handle: Long): String
    @JvmStatic external fun stagedDownloadsJson(handle: Long): String
    @JvmStatic external fun progressJson(handle: Long): String
    @JvmStatic external fun updateJson(handle: Long, region: String, target: Int, checkOnly: Boolean): String
}
