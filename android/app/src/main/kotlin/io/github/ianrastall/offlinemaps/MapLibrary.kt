package io.github.ianrastall.offlinemaps

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import io.github.ianrastall.offlinemaps.core.NativeCore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Completed regions are ordinary transfer folders, including when the app is uninstalled. */
internal class MapLibrary(
    private val context: Context,
    private val core: CoreStore,
    private val operation: MapImport,
) {
    private val resolver = context.contentResolver
    private val store = File(context.noBackupFilesDir, "maps")

    private inner class Folder(val tree: Uri) {
        val root = uri(DocumentsContract.getTreeDocumentId(tree))

        fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

        fun children(directory: Uri): List<Child> {
            val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            return resolver.query(query, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(Child(uri(cursor.getString(0)), cursor.getString(1), cursor.getString(2)))
                }
            } ?: error("The selected folder could not be read.")
        }

        fun create(parent: Uri, type: String, name: String, exact: Boolean = true): Uri {
            val document = DocumentsContract.createDocument(resolver, parent, type, name)
                ?: error("Could not create $name in the backup folder.")
            if (exact) {
                val columns = arrayOf(Document.COLUMN_DISPLAY_NAME)
                val actual = resolver.query(document, columns, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                check(actual == name) { "The storage provider changed the file name $name." }
            }
            return document
        }
    }

    private data class Child(val uri: Uri, val name: String, val type: String)
    data class Copy(val version: String, val folderId: String)
    data class Backup(val rootId: String, val maps: Map<String, Copy>) {
        val message: String get() = "Backed up ${countText(maps.size, "map")} and saved places."
    }

    private fun regions(): List<JSONObject> = core.use {
        JSONObject(NativeCore.statusJson(it, true)).getJSONArray("regions").objects()
    }

    fun backup(tree: Uri, progress: (String) -> Unit): Backup {
        val folder = Folder(tree)
        val regions = regions()
        val backup = folder.create(folder.root, Document.MIME_TYPE_DIR, "Offline Maps Backup ${stamp()}", exact = false)
        val buffer = ByteArray(1 shl 20)
        val finished = mutableMapOf<String, Copy>()
        try {
            operation.checkCancelled()
            val saved = core.use { NativeCore.exportPlaces(it) }
            writeText(folder, backup, PLACES, saved)
            for (region in regions) {
                operation.checkCancelled()
                val id = region.getString("id")
                val name = region.getString("name")
                progress("Preparing backup for $name…")
                val manifest = core.use { NativeCore.describeExport(it, id) }
                progress("Backing up $name · ${finished.size + 1} of ${regions.size} maps")
                val target = writeRegion(folder, backup, id, manifest, buffer)
                finished[id] = Copy(region.getJSONArray("backupVersion").toString(), DocumentsContract.getDocumentId(target))
            }
        } catch (failure: Exception) {
            error("Backup stopped after ${countText(finished.size, "map")}. ${failure.message.orEmpty()}")
        }
        return Backup(DocumentsContract.getDocumentId(backup), finished)
    }

    /** Add a changed region to the existing library after a verified download. */
    fun backupDownload(tree: Uri, rootId: String, id: String): Copy {
        val folder = Folder(tree)
        val library = folder.uri(rootId)
        operation.checkCancelled()
        val manifest = core.use { NativeCore.describeExport(it, id) }
        val version = regions().first { it.getString("id") == id }.getJSONArray("backupVersion").toString()
        val recent = folder.children(library)
            .filter { it.type == Document.MIME_TYPE_DIR && (it.name == id || it.name.startsWith("$id ")) }
            .maxByOrNull { it.name }
        if (recent != null) {
            val marker = folder.children(recent.uri).firstOrNull { it.name == MANIFEST }
            if (marker != null && runCatching { readText(marker.uri, 64 * 1024) }.getOrNull() == manifest) {
                return Copy(version, DocumentsContract.getDocumentId(recent.uri))
            }
        }
        val target = writeRegion(folder, library, "$id ${stamp()}", manifest, ByteArray(1 shl 20))
        return Copy(version, DocumentsContract.getDocumentId(target))
    }

    fun availableCopies(tree: Uri, rootId: String, copies: Collection<Copy>): Set<String> {
        val folder = Folder(tree)
        val root = folder.uri(rootId)
        val exists = resolver.query(root, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == Document.MIME_TYPE_DIR
        }
        check(exists == true) { "The backup folder could not be found." }
        val children = folder.children(root)
        val copiedIds = copies.mapTo(hashSetOf()) { it.folderId }
        return children.asSequence().filter { DocumentsContract.getDocumentId(it.uri) in copiedIds }
            .filter { child -> runCatching { folder.children(child.uri).any { it.name == MANIFEST } }.getOrDefault(false) }
            .map { DocumentsContract.getDocumentId(it.uri) }.toSet()
    }

    private fun writeRegion(folder: Folder, root: Uri, name: String, manifest: String, buffer: ByteArray): Uri {
        operation.checkCancelled()
        val files = JSONObject(manifest).getJSONObject("files")
        val target = folder.create(root, Document.MIME_TYPE_DIR, name, exact = false)
        try {
            val directories = mutableMapOf("" to target)
            for (path in files.keys().asSequence().sorted()) {
                operation.checkCancelled()
                val parts = path.split('/')
                var parent = target
                var prefix = ""
                for (part in parts.dropLast(1)) {
                    prefix += "/$part"
                    val directory = parent
                    parent = directories.getOrPut(prefix) { folder.create(directory, Document.MIME_TYPE_DIR, part) }
                }
                val filename = parts.last()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', ""))
                val document = folder.create(parent, mime ?: "application/octet-stream", filename)
                val file = files.getJSONObject(path)
                copyAndVerify(File(store, path), document, file.getLong("bytes"), file.getString("sha256"), buffer)
            }
            // The manifest is the completion marker for readers.
            writeText(folder, target, MANIFEST, manifest)
            return target
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw failure
        }
    }

    private fun stamp(): String = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH-mm-ss", Locale.ROOT))

    fun restore(tree: Uri, progress: (String) -> Unit): String {
        val folder = Folder(tree)
        val installed = regions().mapTo(mutableSetOf()) { it.getString("id") }
        val rootChildren = folder.children(folder.root)
        val rootManifest = rootChildren.firstOrNull { it.name == MANIFEST }
        val candidates = if (rootManifest != null) {
            listOf(folder.root to rootManifest)
        } else {
            rootChildren.filter { it.type == Document.MIME_TYPE_DIR }.sortedByDescending { it.name }.mapNotNull { directory ->
                operation.checkCancelled()
                folder.children(directory.uri).firstOrNull { it.name == MANIFEST }?.let { directory.uri to it }
            }
        }
        val saved = rootChildren.firstOrNull { it.name == PLACES }
        check(candidates.isNotEmpty() || saved != null) { "This folder has no prepared maps or saved places." }
        var restored = 0
        var skipped = 0
        var failed = 0
        for ((directory, marker) in candidates) {
            operation.checkCancelled()
            try {
                val id = JSONObject(readText(marker.uri, 64 * 1024)).getString("region")
                if (id in installed) {
                    skipped++
                    continue
                }
                operation.stage(tree, DocumentsContract.getDocumentId(directory), progress).use { staged ->
                    check(!staged.review.getBoolean("replacing")) { "The region is already installed." }
                    installed += operation.install(staged)
                    restored++
                }
            } catch (_: Exception) {
                operation.checkCancelled()
                failed++
            }
        }
        var places = ""
        if (saved != null) {
            operation.checkCancelled()
            try {
                val json = readText(saved.uri, 16 * 1024 * 1024)
                val added = core.use { JSONObject(NativeCore.importPlaces(it, json, true)).getInt("added") }
                places = " Added ${countText(added, "saved place")}."
            } catch (_: Exception) {
                places = " Saved places could not be restored."
            }
        }
        val unreadable = if (failed > 0) " ${countText(failed, "map folder")} could not be restored." else ""
        return "Restored ${countText(restored, "map")}. $skipped already installed.$places$unreadable"
    }

    private fun copyAndVerify(source: File, document: Uri, bytes: Long, sha: String, buffer: ByteArray) {
        check(source.isFile && source.length() == bytes) { "A map file changed during backup." }
        source.inputStream().use { input ->
            (resolver.openOutputStream(document, "w") ?: error("Could not write a map file.")).use { output ->
                while (true) {
                    operation.checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        var length = 0L
        (resolver.openInputStream(document) ?: error("Could not read back a map file.")).use { input ->
            while (true) {
                operation.checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                length += count
                check(length <= bytes) { "A backup file has an unexpected size." }
                hash.update(buffer, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        check(length == bytes && actual.equals(sha, ignoreCase = true)) { "A backup file failed its checksum." }
    }

    private fun writeText(folder: Folder, parent: Uri, name: String, text: String) {
        val document = folder.create(parent, "application/json", name)
        (resolver.openOutputStream(document, "w") ?: error("Could not write $name.")).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        check(readText(document, text.toByteArray(Charsets.UTF_8).size) == text) { "Could not verify $name." }
    }

    private fun readText(document: Uri, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        (resolver.openInputStream(document) ?: error("A backup file could not be read.")).use { input ->
            while (true) {
                operation.checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                check(out.size() + count <= max) { "A backup record is too large." }
                out.write(buffer, 0, count)
            }
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    }

    companion object {
        private const val MANIFEST = "transfer.json"
        private const val PLACES = "saved-places.json"
    }
}
