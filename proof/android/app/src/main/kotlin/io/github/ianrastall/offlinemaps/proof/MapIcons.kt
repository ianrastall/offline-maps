package io.github.ianrastall.offlinemaps.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.maplibre.android.maps.ImageContent
import org.maplibre.android.maps.ImageStretches
import org.maplibre.android.maps.Style

/**
 * The images the map page draws at runtime (web/map.js): route shields, the charging-station
 * icon and the outdoor-place icons. Map content colors, as on the page. Drawn at twice the
 * size and marked as 2x (bitmap density 320).
 */
object MapIcons {
    private const val RATIO = 2f

    private class Shield(val fill: String, val border: String, val radius: Float, val cap: String? = null)

    private val SHIELDS = mapOf(
        "route-interstate" to Shield("#1d4f9c", "#ffffff", 6f, "#c8102e"),
        "route-us" to Shield("#ffffff", "#111111", 6f),
        "route-state" to Shield("#ffffff", "#111111", 1f),
        "route-mex" to Shield("#ffffff", "#111111", 3f, "#111111"),
        "route-other" to Shield("#eeeeee", "#777777", 3f),
    )

    private val OUTDOOR = mapOf(
        "outdoor-shelter" to "#92400e",
        "outdoor-water" to "#0369a1",
        "outdoor-camp" to "#166534",
        "outdoor-toilets" to "#475569",
    )

    val ids: Set<String> = SHIELDS.keys + OUTDOOR.keys + "station-charging"

    /** Adds [id] to [style] if it is one of these images; false otherwise. */
    fun add(style: Style, id: String): Boolean {
        SHIELDS[id]?.let { addShield(style, id, it); return true }
        OUTDOOR[id]?.let { style.addImage(id, disc(Color.parseColor(it)) { c, p -> drawOutdoor(id, c, p, Color.parseColor(it)) }); return true }
        if (id == "station-charging") {
            style.addImage(id, disc(Color.parseColor("#15803d"), ::drawBolt))
            return true
        }
        return false
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { density = 320 }

    private fun addShield(style: Style, id: String, spec: Shield) {
        val r = RATIO
        val w = (24 * r).toInt()
        val h = (20 * r).toInt()
        val line = 1.5f * r
        val bitmap = bitmap(w, h)
        val canvas = Canvas(bitmap)
        val rect = RectF(line / 2, line / 2, w - line / 2, h - line / 2)
        val radius = spec.radius * r
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor(spec.fill)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (spec.cap != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            paint.color = Color.parseColor(spec.cap)
            canvas.drawRect(0f, 0f, w.toFloat(), 5 * r, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        paint.color = Color.parseColor(spec.border)
        canvas.drawRoundRect(rect, radius, radius, paint)
        val top = (if (spec.cap != null) 6 else 3) * r
        style.addImage(
            id,
            bitmap,
            false,
            listOf(ImageStretches(8 * r, 16 * r)),
            listOf(ImageStretches(top + r, h - 4 * r)),
            ImageContent(4 * r, top, w - 4 * r, h - 3 * r),
        )
    }

    /** A 32-unit disc with a white edge; [draw] adds the symbol in white. */
    private fun disc(fill: Int, draw: (Canvas, (Float, Float) -> Pair<Float, Float>) -> Unit): Bitmap {
        val size = (32 * RATIO).toInt()
        val bitmap = bitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill
        val centre = size / 2f
        canvas.drawCircle(centre, centre, centre - 2 * RATIO, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * RATIO
        paint.color = Color.WHITE
        canvas.drawCircle(centre, centre, centre - 2 * RATIO, paint)
        draw(canvas) { x, y -> x * RATIO to y * RATIO }
        return bitmap
    }

    private fun path(p: (Float, Float) -> Pair<Float, Float>, vararg points: Pair<Float, Float>, close: Boolean = true) = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val (px, py) = p(x, y)
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        if (close) close()
    }

    private fun drawBolt(canvas: Canvas, p: (Float, Float) -> Pair<Float, Float>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawPath(path(p, 18f to 6f, 9f to 18f, 15f to 18f, 13f to 26f, 23f to 13f, 17f to 13f, 19f to 6f), paint)
    }

    private fun drawOutdoor(id: String, canvas: Canvas, p: (Float, Float) -> Pair<Float, Float>, fill: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 2 * RATIO
        }
        when (id) {
            "outdoor-shelter" -> {
                paint.style = Paint.Style.STROKE
                canvas.drawPath(path(p, 8f to 17f, 16f to 9f, 24f to 17f, close = false), paint)
                canvas.drawPath(path(p, 11f to 15f, 11f to 23f, 21f to 23f, 21f to 15f, close = false), paint)
            }
            "outdoor-water" -> {
                val (x0, y0) = p(16f, 7f)
                val drop = Path().apply {
                    moveTo(x0, y0)
                    val c = { x: Float, y: Float -> p(x, y) }
                    val (a1, b1) = c(9f, 15f); val (a2, b2) = c(9f, 19f)
                    cubicTo(x0, y0, a1, b1, a2, b2)
                    val (d1, e1) = c(9f, 23f); val (d2, e2) = c(12f, 25f); val (d3, e3) = c(16f, 25f)
                    cubicTo(d1, e1, d2, e2, d3, e3)
                    val (f1, g1) = c(20f, 25f); val (f2, g2) = c(23f, 23f); val (f3, g3) = c(23f, 19f)
                    cubicTo(f1, g1, f2, g2, f3, g3)
                    val (h1, i1) = c(23f, 15f)
                    cubicTo(h1, i1, x0, y0, x0, y0)
                    close()
                }
                canvas.drawPath(drop, paint)
            }
            "outdoor-camp" -> {
                canvas.drawPath(path(p, 16f to 8f, 25f to 24f, 7f to 24f), paint)
                paint.color = fill
                canvas.drawPath(path(p, 16f to 15f, 19f to 24f, 13f to 24f), paint)
            }
            "outdoor-toilets" -> {
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = 11 * RATIO
                paint.textAlign = Paint.Align.CENTER
                val (x, y) = p(16f, 16.5f)
                canvas.drawText("WC", x, y - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
    }
}
