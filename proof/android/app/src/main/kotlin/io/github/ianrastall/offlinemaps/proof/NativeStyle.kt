package io.github.ianrastall.offlinemaps.proof

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The map style for MapLibre Native: the same style file the map page loads, with the layers the
 * page adds at runtime (web/map.js: overview, flood zones and public lands, terrain shading),
 * written into the style before it is loaded, since MapLibre Native on Android cannot add a
 * layer from JSON afterwards.
 *
 * Left out, compared with the page: contour lines (maplibre-contour computes them in a browser
 * worker; there is no native equivalent), the region highlight under the pointer, and the
 * terrain credit, which the page computes for the area in view.
 */
object NativeStyle {
    private const val ORIGIN = "https://map.local"
    private const val TERRAIN_BEFORE = "water-river"
    private const val LABELS_BEFORE = "label-address-housenumber"
    private const val FILLS_BEFORE = "slot-below-fills"
    private const val OVERVIEW_ONLY_BELOW = 6

    fun build(assets: AssetManager, theme: String, status: JSONObject): String {
        val style = JSONObject(assets.open("web/styles/offline-maps-$theme.json").use { it.readBytes().decodeToString() })
        val metadata = status.getJSONObject("metadata")
        val overview = metadata.optBoolean("overview")
        val b = metadata.getJSONObject("bounds")
        val bounds = JSONArray(listOf(b.getDouble("west"), b.getDouble("south"), b.getDouble("east"), b.getDouble("north")))

        style.put("glyphs", "asset://web/glyphs/{fontstack}/{range}.pbf")
        val sprites = style.getJSONArray("sprite")
        for (i in 0 until sprites.length()) {
            val sprite = sprites.getJSONObject(i)
            sprite.put("url", "asset://web/sprites/" + sprite.getString("url").substringAfterLast('/'))
        }
        val sources = style.getJSONObject("sources")
        for (key in sources.keys()) {
            val source = sources.getJSONObject(key)
            when (source.getString("type")) {
                "geojson" -> source.put("data", ORIGIN + source.getString("data"))
                "vector" -> {
                    val tiles = source.getJSONArray("tiles")
                    source.put("tiles", JSONArray((0 until tiles.length()).map { ORIGIN + tiles.getString(it) }))
                    val minZoom = metadata.getInt("minZoom")
                    source.put("minzoom", if (overview) maxOf(minZoom, OVERVIEW_ONLY_BELOW) else minZoom)
                    source.put("maxzoom", metadata.getInt("maxZoom"))
                    source.put("bounds", bounds)
                }
            }
        }
        val layers = Layers(style.getJSONArray("layers"))
        if (overview) addOverview(sources, layers, metadata.optJSONArray("regions") ?: JSONArray())
        status.optJSONObject("overlays")?.let { addOverlays(sources, layers, it.getJSONObject("bounds"), theme) }
        status.optJSONObject("terrain")?.let { addTerrain(sources, layers, it.getJSONObject("bounds"), theme) }
        style.put("layers", layers.toJson())
        return style.toString()
    }

    private class Layers(array: JSONArray) {
        val list = (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()

        fun find(id: String) = list.firstOrNull { it.getString("id") == id }

        fun addBefore(before: String?, layer: JSONObject) {
            val index = list.indexOfFirst { it.getString("id") == before }
            if (index < 0) list.add(layer) else list.add(index, layer)
        }

        fun paint(id: String, property: String, fallback: Any): Any =
            find(id)?.optJSONObject("paint")?.opt(property) ?: fallback

        fun labelsBefore() = if (find(LABELS_BEFORE) != null) LABELS_BEFORE else null

        fun toJson() = JSONArray(list)
    }

    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, wrap(v)) }

    /** Lists and nested lists become JSON arrays (expressions). */
    private fun wrap(value: Any?): Any? = when (value) {
        is List<*> -> JSONArray(value.map { wrap(it) })
        else -> value
    }

    private fun vectorSource(path: String, minZoom: Int, maxZoom: Int, bounds: List<Double>, attribution: String? = null) =
        json(
            "type" to "vector",
            "tiles" to listOf("$ORIGIN/$path/{z}/{x}/{y}.pbf"),
            "minzoom" to minZoom,
            "maxzoom" to maxZoom,
            "bounds" to bounds,
        ).also { if (attribution != null) it.put("attribution", attribution) }

    private fun box(b: JSONObject) = listOf(b.getDouble("west"), b.getDouble("south"), b.getDouble("east"), b.getDouble("north"))

    /** web/map.js `covers`: a feature's `region` names the installed region [id] or one inside it. */
    private fun covers(id: String): List<Any> {
        val padded = listOf("concat", " ", listOf("get", "region"), " ")
        return listOf("any", listOf("in", " $id ", padded), listOf("in", " $id-", padded))
    }

    private fun addOverview(sources: JSONObject, layers: Layers, regions: JSONArray) {
        sources.put("overview", vectorSource("overview", 0, 7, listOf(-180.0, -85.0511, 180.0, 85.0511)))
        val water = layers.paint("water-ocean", "fill-color", "#bfd9f2")
        val river = layers.paint("water-river", "line-color", "#a6caed")
        val border = layers.paint("boundary-country", "line-color", "#a6a6c8")
        val motorway = layers.paint("street-motorway", "line-color", "#ffcc88")
        val trunk = layers.paint("street-trunk", "line-color", "#ffeeaa")
        val text = layers.paint("label-place-city", "text-color", "#333344")
        val halo = layers.paint("label-place-city", "text-halo-color", "rgba(255,255,255,0.8)")
        val stateText = layers.paint("label-boundary-state", "text-color", "#3d3d4d")
        val installed = (0 until regions.length()).map { regions.getString(it) }
        val outside = listOf("any", listOf("<", listOf("zoom"), OVERVIEW_ONLY_BELOW), listOf("all") + installed.map { listOf("!", covers(it)) })
        // State and country names come from the overview alone.
        for (layer in layers.list) {
            if (layer.getString("id").startsWith("label-boundary-")) {
                layer.put("layout", (layer.optJSONObject("layout") ?: JSONObject()).put("visibility", "none"))
            }
        }
        fun add(layer: JSONObject) {
            layer.put("source", "overview")
            val label = layer.getString("type") in setOf("symbol", "circle")
            layers.addBefore(if (label) layers.labelsBefore() else FILLS_BEFORE, layer)
        }
        val kind = listOf("get", "kind")
        val major = listOf("interstate", "motorway")
        add(json("id" to "overview-water", "type" to "fill", "source-layer" to "water", "filter" to outside, "paint" to json("fill-color" to water)))
        add(json("id" to "overview-rivers", "type" to "line", "source-layer" to "rivers", "filter" to outside,
            "paint" to json("line-color" to river, "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 3, 0.5, 7, 1.2, 10, 2))))
        add(json("id" to "overview-state-lines", "type" to "line", "source-layer" to "boundaries",
            "filter" to listOf("all", listOf("==", kind, "state"), outside),
            "paint" to json(
                "line-color" to border,
                "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 2, 0.5, 7, 1, 10, 2),
                "line-dasharray" to listOf(3, 1, 1, 1),
            )))
        add(json("id" to "overview-country-lines", "type" to "line", "source-layer" to "boundaries",
            "filter" to listOf("==", kind, "country"),
            "paint" to json("line-color" to border, "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 2, 1, 7, 2, 10, 3))))
        add(json("id" to "overview-roads", "type" to "line", "source-layer" to "roads", "filter" to outside,
            "layout" to json("line-join" to "round", "line-cap" to "round"),
            "paint" to json(
                "line-color" to listOf("match", kind, major, motorway, trunk),
                "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"),
                    4, listOf("match", kind, major, 1, 0.5),
                    7, listOf("match", kind, major, 2, 1.2),
                    10, listOf("match", kind, major, 3.5, 2)),
            )))
        add(json("id" to "overview-place-dots", "type" to "circle", "source-layer" to "places", "filter" to outside,
            "paint" to json(
                "circle-radius" to listOf("match", listOf("get", "capital"), "none", 2.5, 3.5),
                "circle-color" to text,
                "circle-stroke-color" to halo,
                "circle-stroke-width" to 1,
            )))
        val rank = listOf("min", listOf("get", "rank"), 6)
        add(json("id" to "overview-places", "type" to "symbol", "source-layer" to "places", "filter" to outside,
            "layout" to json(
                "text-field" to listOf("get", "name"),
                "text-font" to listOf("noto_sans_regular"),
                "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 3, listOf("-", 13, rank), 8, listOf("-", 16, rank)),
                "text-variable-anchor" to listOf("left", "right", "top", "bottom"),
                "text-radial-offset" to 0.5,
                "symbol-sort-key" to listOf("get", "rank"),
            ),
            "paint" to json("text-color" to text, "text-halo-color" to halo, "text-halo-width" to 1.5)))
        add(json("id" to "overview-labels", "type" to "symbol", "source-layer" to "labels",
            "layout" to json(
                "text-field" to listOf("get", "name"),
                "text-font" to listOf("noto_sans_regular"),
                "text-size" to listOf("interpolate", listOf("linear"), listOf("zoom"), 3, 9, 6, 13, 9, 16),
                "text-transform" to "uppercase",
                "text-letter-spacing" to 0.1,
                "text-max-width" to 8,
                "symbol-sort-key" to listOf("match", kind, "country", 0, 1),
            ),
            "paint" to json("text-color" to stateText, "text-halo-color" to halo, "text-halo-width" to 1.5, "text-opacity" to 0.8)))
    }

    private fun addOverlays(sources: JSONObject, layers: Layers, bounds: JSONObject, theme: String) {
        sources.put("overlays", vectorSource("overlays", 5, 14, box(bounds), "Flood: FEMA · Lands: USGS"))
        val dark = theme == "dark"
        val access = listOf("get", "access")
        val landColors = listOf("match", access, "open", "#2f9e44", "restricted", "#d9a400", "closed", "#868e96", "#adb5bd")
        val floodKind = listOf("get", "kind")
        val floodColors = listOf("match", floodKind, "floodway", "#1c4fa0", "high", "#2f7fd8", "moderate", "#74b4f0", "#9aa8c8")
        val before = if (layers.find(TERRAIN_BEFORE) != null) TERRAIN_BEFORE else null
        layers.addBefore(before, json("id" to "lands-fill", "type" to "fill", "source" to "overlays", "source-layer" to "public_lands",
            "paint" to json("fill-color" to landColors, "fill-opacity" to if (dark) 0.22 else 0.18)))
        layers.addBefore(before, json("id" to "lands-line", "type" to "line", "source" to "overlays", "source-layer" to "public_lands", "minzoom" to 9,
            "paint" to json(
                "line-color" to landColors,
                "line-opacity" to 0.7,
                "line-width" to listOf("interpolate", listOf("linear"), listOf("zoom"), 9, 0.5, 14, 1.5),
            )))
        layers.addBefore(before, json("id" to "flood-fill", "type" to "fill", "source" to "overlays", "source-layer" to "flood_zones",
            "paint" to json(
                "fill-color" to floodColors,
                "fill-opacity" to listOf("match", floodKind, "floodway", 0.4, "high", 0.28, 0.16),
            )))
        layers.addBefore(layers.labelsBefore(), json("id" to "lands-label", "type" to "symbol", "source" to "overlays",
            "source-layer" to "public_land_labels", "minzoom" to 11,
            "layout" to json(
                "text-field" to listOf("get", "name"),
                "text-font" to listOf("noto_sans_regular"),
                "text-size" to 11,
                "text-max-width" to 8,
            ),
            "paint" to json(
                "text-color" to if (dark) "#8fd19e" else "#2b6b3a",
                "text-halo-color" to if (dark) "#000000" else "#ffffff",
                "text-halo-width" to 1,
            )))
    }

    private fun addTerrain(sources: JSONObject, layers: Layers, bounds: JSONObject, theme: String) {
        sources.put("terrain-dem", json(
            "type" to "raster-dem",
            "tiles" to listOf("$ORIGIN/terrain/{z}/{x}/{y}.webp"),
            "encoding" to "terrarium",
            "tileSize" to 512,
            "maxzoom" to 12,
            "bounds" to box(bounds),
        ))
        val dark = theme == "dark"
        val before = if (layers.find(TERRAIN_BEFORE) != null) TERRAIN_BEFORE else null
        layers.addBefore(before, json("id" to "terrain-shading", "type" to "hillshade", "source" to "terrain-dem",
            "paint" to json(
                "hillshade-shadow-color" to if (dark) "#000000" else "#5c4a32",
                "hillshade-highlight-color" to if (dark) "#9c9c9c" else "#ffffff",
                "hillshade-accent-color" to if (dark) "#1a1a1a" else "#6b5a3e",
                "hillshade-exaggeration" to if (dark) 0.45 else 0.35,
            )))
    }
}
