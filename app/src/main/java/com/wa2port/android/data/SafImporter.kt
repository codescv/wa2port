package com.wa2port.android.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Import progress snapshot (emitted at most every ~100 ms). */
data class ImportProgress(
    val phase: Phase,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val current: String = "",
    val message: String? = null,
) {
    enum class Phase { SCANNING, COPYING, DONE, FAILED, CANCELLED }

    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
    val running: Boolean get() = phase == Phase.SCANNING || phase == Phase.COPYING
}

/**
 * Copies a user-picked SAF folder (`ACTION_OPEN_DOCUMENT_TREE`) into app storage.
 *
 * Resumable: files whose size already matches are skipped, and partially copied files continue
 * from `<name>.part` (seeking the source descriptor) before being renamed into place. PC-only files
 * (`*.exe`, `*.chm`, `*.dll`) are not copied.
 */
class SafImporter(private val context: Context) {
    private data class Entry(val uri: Uri, val relPath: String, val size: Long)

    suspend fun import(treeUri: Uri, dest: File, onProgress: (ImportProgress) -> Unit): ImportProgress =
        withContext(Dispatchers.IO) {
            try {
                onProgress(ImportProgress(ImportProgress.Phase.SCANNING))
                val entries = ArrayList<Entry>()
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                walk(treeUri, rootId, "", entries)
                val total = entries.sumOf { it.size }
                val alreadyThere = entries.sumOf { e -> existingBytes(File(dest, e.relPath), e.size) }
                val needed = total - alreadyThere
                dest.mkdirs()
                if (dest.usableSpace in 1 until needed) {
                    return@withContext ImportProgress(
                        ImportProgress.Phase.FAILED, 0, entries.size, 0, total,
                        message = "空间不足 Not enough space: need %.1f GB, free %.1f GB".format(needed / 1e9, dest.usableSpace / 1e9),
                    ).also(onProgress)
                }
                var done = 0L
                var lastEmit = 0L
                entries.forEachIndexed { index, e ->
                    coroutineContext.ensureActive()
                    val target = File(dest, e.relPath)
                    if (target.isFile && target.length() == e.size) {
                        done += e.size
                        return@forEachIndexed
                    }
                    target.parentFile?.mkdirs()
                    val part = File(target.parentFile, target.name + ".part")
                    var offset = if (part.isFile && part.length() <= e.size) part.length() else 0L
                    if (offset == 0L) part.delete()
                    done += offset
                    openAt(e.uri, offset).use { input ->
                        FileOutputStream(part, offset > 0).use { out ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                offset += n
                                done += n
                                val now = SystemClock.uptimeMillis()
                                if (now - lastEmit > 100) {
                                    lastEmit = now
                                    onProgress(ImportProgress(ImportProgress.Phase.COPYING, index, entries.size, done, total, e.relPath))
                                }
                            }
                        }
                    }
                    if (part.length() != e.size) throw IOException("size mismatch for ${e.relPath}: ${part.length()} != ${e.size}")
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("rename failed: ${target.path}")
                }
                ImportProgress(ImportProgress.Phase.DONE, entries.size, entries.size, total, total).also(onProgress)
            } catch (c: kotlinx.coroutines.CancellationException) {
                onProgress(ImportProgress(ImportProgress.Phase.CANCELLED, message = "已暂停，可继续 Paused — resume later"))
                throw c
            } catch (t: Throwable) {
                ImportProgress(ImportProgress.Phase.FAILED, message = t.message ?: t.javaClass.simpleName).also(onProgress)
            }
        }

    private fun existingBytes(target: File, size: Long): Long = when {
        target.isFile && target.length() == size -> size
        else -> File(target.parentFile, target.name + ".part").takeIf { it.isFile && it.length() <= size }?.length() ?: 0L
    }

    /** Opens [uri] positioned at [offset] (seeks the file descriptor when possible). */
    private fun openAt(uri: Uri, offset: Long): InputStream {
        if (offset > 0) {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fis = ParcelInput(pfd)
                fis.channel.position(offset)
                return fis
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        var skipped = 0L
        while (skipped < offset) {
            val s = input.skip(offset - skipped)
            if (s <= 0) throw IOException("cannot seek $uri")
            skipped += s
        }
        return input
    }

    private class ParcelInput(private val pfd: android.os.ParcelFileDescriptor) : FileInputStream(pfd.fileDescriptor) {
        override fun close() {
            super.close()
            pfd.close()
        }
    }

    /** Recursively lists the tree with one `DocumentsContract` query per directory (much faster than DocumentFile). */
    private fun walk(tree: Uri, docId: String, prefix: String, out: MutableList<Entry>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        context.contentResolver.query(children, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id to name
                } else if (!skip(name)) {
                    out += Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id), prefix + name, size)
                }
            }
        }
        for ((id, name) in dirs) walk(tree, id, "$prefix$name/", out)
    }

    private fun skip(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".exe") || n.endsWith(".chm") || n.endsWith(".dll") || n.endsWith(".part")
    }
}
