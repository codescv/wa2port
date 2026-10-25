package com.wa2port.core.archive

import java.io.Closeable
import java.io.File

/** One entry of a `LAC\0` audio archive: payload bytes `[offset, offset + size)` of the archive file. */
data class LacEntry(val name: String, val offset: Long, val size: Int)

/**
 * Reader for Leaf `LAC\0` audio archives (`BGM.PAK`, `SE.PAK`, `VOICE.PAK`, and their `IC/` copies).
 *
 * Layout: `"LAC\0" u32 count`, then `count` × 40-byte entries
 * `{char name[32] (each byte XOR 0xFF, padding = raw 0x00), u32 size, u32 offset}`.
 * Payloads are stored uncompressed (OGG Vorbis / RIFF WAV), so callers can stream them directly
 * from the archive file via [entry] offsets (e.g. `MediaExtractor.setDataSource(fd, off, len)`).
 */
class LacArchive(val file: File) : Closeable {
    private val pf = PositionalFile(file)
    val entries: List<LacEntry>
    private val byName: Map<String, LacEntry>

    init {
        val head = pf.readLe(0, 8)
        val magic = ByteArray(4).also { head.get(it) }
        require(magic.contentEquals(byteArrayOf(0x4C, 0x41, 0x43, 0))) { "${file.name}: not a LAC archive" }
        val count = head.int
        require(count in 0..1_000_000) { "${file.name}: bad entry count $count" }
        val idx = pf.readLe(8, count * ENTRY_SIZE)
        val nameRaw = ByteArray(32)
        val list = ArrayList<LacEntry>(count)
        repeat(count) {
            idx.get(nameRaw)
            // Terminate on the first *raw* 0x00 (padding), then undo the XOR on the name bytes.
            var n = 0
            while (n < 32 && nameRaw[n].toInt() != 0) n++
            val plain = ByteArray(n) { (nameRaw[it].toInt() xor 0xFF).toByte() }
            val size = idx.int
            val off = idx.int.toLong() and 0xFFFFFFFFL
            list += LacEntry(Cp932.decode(plain), off, size)
        }
        entries = list
        byName = list.associateBy { it.name.lowercase() }
    }

    fun find(name: String): LacEntry? = byName[name.lowercase()]

    operator fun contains(name: String): Boolean = find(name) != null

    fun read(entry: LacEntry): ByteArray = pf.read(entry.offset, entry.size)

    fun read(name: String): ByteArray? = find(name)?.let { read(it) }

    override fun close() = pf.close()

    companion object {
        const val ENTRY_SIZE = 40

        fun isLac(file: File): Boolean = runCatching {
            file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                .contentEquals(byteArrayOf(0x4C, 0x41, 0x43, 0))
        }.getOrDefault(false)
    }
}
