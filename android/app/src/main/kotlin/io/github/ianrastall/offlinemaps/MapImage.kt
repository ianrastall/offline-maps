package io.github.ianrastall.offlinemaps

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val MAP_IMAGE_FOLDER = "Pictures/Offline Maps"

internal fun writeMapImage(resolver: ContentResolver, bitmap: Bitmap) {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH-mm-ss", Locale.ROOT))
    val metadata = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Offline Map $stamp.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, MAP_IMAGE_FOLDER)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, metadata) ?: error("Android could not create the PNG.")
    var published = false
    try {
        val output = resolver.openOutputStream(uri) ?: error("Android could not open the PNG.")
        output.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The PNG could not be written." } }
        val complete = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        check(resolver.update(uri, complete, null, null) == 1) { "Android could not finish saving the PNG." }
        published = true
    } finally {
        if (!published) runCatching { resolver.delete(uri, null, null) }
    }
}
