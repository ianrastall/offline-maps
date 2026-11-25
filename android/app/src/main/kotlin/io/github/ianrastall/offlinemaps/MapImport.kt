package io.github.ianrastall.offlinemaps

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** SAF reads only the folder selected by the user. Partial copies remain outside active regions. */
class MapImport(
    private val context: Context,
    private val core: CoreStore,
    val cancelled: AtomicBoolean = AtomicBoolean(false),
) {
    internal fun checkCancelled() { check(!cancelled.get()) { "Map operation cancelled." } }

    class Staged(val directory: File, val review: JSONObject) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    fun stage(
        tree: Uri,
        rootId: String = DocumentsContract.getTreeDocumentId(tree),
        progress: (String) -> Unit,
    ): Staged {
        val cache = mutableMapOf<String, Map<String, String>>()
        fun children(id: String): Map<String, String> = cache.getOrPut(id) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            } ?: error("The selected folder could not be read.")
        }
        fun document(path: String): Uri {
            var id = rootId
            for (part in path.split('/')) id = children(id)[part] ?: error("Missing transfer file: $path")
            return DocumentsContract.buildDocumentUriUsingTree(tree, id)
        }
        val manifestBytes = context.contentResolver.openInputStream(document("transfer.json"))!!.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        check(manifestBytes.size <= 65536) { "The transfer manifest is too large." }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val region = manifest.getString("region")
        check(manifest.getInt("schema") == 1 && region.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*"))) { "Unknown transfer format." }
        val files = manifest.getJSONObject("files")
        val names = files.keys().asSequence().toList().sorted()
        // A whole region, or separate layers (such as terrain) for an installed one.
        check(names.size in 3..13) { "Select a prepared region folder." }
        var total = 0L
        for (name in names) {
            check(name.startsWith("$region/") && name.split('/').all {
                it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+"))
            }) { "Invalid transfer file name." }
            val size = files.getJSONObject(name).getLong("bytes")
            check(size in 1..(64L shl 30)) { "Invalid transfer size." }
            total = Math.addExact(total, size)
        }
        val maps = File(context.noBackupFilesDir, "maps")
        check(maps.usableSpace > total + (64L shl 20)) { "Not enough free space. This import needs ${total / 1_000_000} MB plus 64 MB." }
        val staging = File(maps, ".imports/${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            File(staging, "transfer.json").writeBytes(manifestBytes)
            var done = 0L
            val buffer = ByteArray(1 shl 20)
            for (name in names) {
                checkCancelled()
                val expected = files.getJSONObject(name).getLong("bytes")
                val target = File(staging, name).apply { parentFile!!.mkdirs() }
                context.contentResolver.openInputStream(document(name))!!.use { input ->
                    target.outputStream().use { output ->
                        var copied = 0L
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= expected) { "A transfer file is larger than listed." }
                            output.write(buffer, 0, count)
                            done += count
                            progress("Copying: ${done / 1_000_000} of ${total / 1_000_000} MB")
                        }
                        check(copied == expected) { "A transfer file is incomplete." }
                        output.fd.sync()
                    }
                }
            }
            checkCancelled()
            progress("Verifying map, search and layers…")
            val review = core.use { JSONObject(NativeCore.previewTransfer(it, staging.absolutePath)) }
            checkCancelled()
            return Staged(staging, review)
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    fun install(staged: Staged): String {
        checkCancelled()
        return core.use { NativeCore.importTransfer(it, staged.directory.absolutePath, staged.review.getBoolean("replacing")) }
    }
}
