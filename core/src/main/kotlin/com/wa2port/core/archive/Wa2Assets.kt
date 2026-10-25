package com.wa2port.core.archive

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A byte range inside a file on disk (stored payload inside a LAC archive, or a whole loose file). */
data class FileSlice(val file: File, val offset: Long, val length: Long, val name: String)

/**
 * Engine-faithful asset file system over a WA2 install directory.
 *
 * Lookup order mirrors `WA2.exe`: loose `<root>/<dir>/<file>` → `<root>/patch.pak` (optional) →
 * `<root>/<dir>.pak` → `<root>/IC/<dir>.pak`. All file-name matching is case-insensitive because
 * the PC install mixes `BGM.PAK` and `bak.pak` while Android file systems are case-sensitive.
 */
class Wa2Assets(val root: File) : Closeable {
    private val rootListing: Map<String, File> = listing(root)
    private val icDir: File? = rootListing["ic"]?.takeIf { it.isDirectory }
    private val icListing: Map<String, File> = icDir?.let { listing(it) } ?: emptyMap()
    private val kcapCache = ConcurrentHashMap<String, KcapArchive?>()
    private val lacCache = ConcurrentHashMap<String, LacArchive?>()
    private val looseCache = ConcurrentHashMap<String, Map<String, File>>()

    /** True when this directory looks like a WA2 install (has at least the JP or CN scenario archive). */
    val isValid: Boolean get() = file("script.pak") != null || file("ck-gal.pak") != null

    /** Resolves a top-level file case-insensitively. */
    fun file(name: String): File? = rootListing[name.lowercase()]

    fun icFile(name: String): File? = icListing[name.lowercase()]

    /** Every archive (KCAP/LAC/ASF `.pak`) present in the root and `IC/` directories. */
    fun allPakFiles(): List<File> =
        (rootListing.values + icListing.values).filter { it.isFile && it.name.lowercase().endsWith(".pak") }
            .sortedBy { it.path }

    /** KCAP archive `<name>.pak` in the root (`ic = false`) or the `IC/` folder (`ic = true`). */
    fun kcap(name: String, ic: Boolean = false): KcapArchive? {
        val key = (if (ic) "ic/" else "") + name.lowercase()
        return kcapCache.getOrPut(key) {
            val f = (if (ic) icFile("$name.pak") else file("$name.pak")) ?: return@getOrPut null
            if (KcapArchive.isKcap(f)) KcapArchive(f) else null
        }
    }

    /** LAC archive `<name>.PAK` in the root (`ic = false`) or the `IC/` folder (`ic = true`). */
    fun lac(name: String, ic: Boolean = false): LacArchive? {
        val key = (if (ic) "ic/" else "") + name.lowercase()
        return lacCache.getOrPut(key) {
            val f = (if (ic) icFile("$name.pak") else file("$name.pak")) ?: return@getOrPut null
            if (LacArchive.isLac(f)) LacArchive(f) else null
        }
    }

    private fun looseDir(dir: String): Map<String, File> = looseCache.getOrPut(dir.lowercase()) {
        rootListing[dir.lowercase()]?.takeIf { it.isDirectory }?.let { listing(it) } ?: emptyMap()
    }

    /**
     * Reads `<dir>/<name>` following the engine search order. [dir] is the archive stem
     * (`char`, `bak`, `grp`, `script`, `ck-gal`, `fnt`, `fon`).
     */
    fun read(dir: String, name: String): ByteArray? {
        looseDir(dir)[name.lowercase()]?.let { return it.readBytes() }
        kcap("patch")?.let { p -> p.find(name)?.let { return p.read(it) } }
        kcap(dir)?.let { a -> a.find(name)?.let { return a.read(it) } }
        kcap(dir, ic = true)?.let { a -> a.find(name)?.let { return a.read(it) } }
        return null
    }

    /** True when [read] would find the file. */
    fun exists(dir: String, name: String): Boolean =
        looseDir(dir).containsKey(name.lowercase()) ||
            kcap("patch")?.contains(name) == true ||
            kcap(dir)?.contains(name) == true ||
            kcap(dir, ic = true)?.contains(name) == true

    /** Locates an audio payload (`BGM`, `SE`, `VOICE`) as a streamable slice, root archive first. */
    fun audio(group: String, name: String): FileSlice? {
        looseDir(group)[name.lowercase()]?.let { return FileSlice(it, 0, it.length(), it.name) }
        for (ic in listOf(false, true)) {
            val a = lac(group, ic) ?: continue
            val e = a.find(name) ?: continue
            return FileSlice(a.file, e.offset, e.size.toLong(), e.name)
        }
        return null
    }

    fun readAudio(group: String, name: String): ByteArray? = audio(group, name)?.let { s ->
        PositionalFile(s.file).use { it.read(s.offset, s.length.toInt()) }
    }

    /** Movie container `mvNN{variant}.pak` (raw ASF), root first then `IC/`. */
    fun movie(stem: String, variant: Int = 0): File? =
        file("$stem$variant.pak") ?: icFile("$stem$variant.pak")

    override fun close() {
        kcapCache.values.forEach { it?.close() }
        lacCache.values.forEach { it?.close() }
    }

    private companion object {
        fun listing(dir: File): Map<String, File> =
            (dir.listFiles() ?: emptyArray()).associateBy { it.name.lowercase() }
    }
}
