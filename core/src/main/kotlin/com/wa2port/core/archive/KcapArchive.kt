package com.wa2port.core.archive

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Shift-JIS (CP932) charset, with a fallback for runtimes that lack the Microsoft variant. */
object Cp932 {
    val charset: Charset = runCatching { Charset.forName("windows-31j") }
        .recoverCatching { Charset.forName("Shift_JIS") }
        .getOrThrow()

    fun decode(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): String =
        String(bytes, off, len, charset)
}

/** Thread-safe positional reader over a file (no shared file pointer). */
class PositionalFile(val file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val channel: FileChannel = raf.channel
    val length: Long = raf.length()

    /** Reads exactly [len] bytes at absolute [pos] (short reads at EOF are zero-filled). */
    fun read(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        val buf = ByteBuffer.wrap(out)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return out
    }

    fun readLe(pos: Long, len: Int): ByteBuffer = ByteBuffer.wrap(read(pos, len)).order(ByteOrder.LITTLE_ENDIAN)

    override fun close() = raf.close()
}

/** One entry of a Leaf `KCAP` archive. */
data class KcapEntry(
    val flag: Int,
    val name: String,
    val fileTime: Long,
    val offset: Long,
    val size: Int,
) {
    /** `0xCCCCCCCC` (and anything other than 0/1) marks directory / junk index rows. */
    val isFile: Boolean get() = flag == 0 || flag == 1
    val isCompressed: Boolean get() = flag == 1
}

/**
 * Reader for Leaf `KCAP` `.pak` archives (`char.pak`, `bak.pak`, `grp.pak`, `script.pak`,
 * `ck-gal.pak`, `fnt.pak`, `fon.pak`).
 *
 * Layout: `"KCAP" u32 u32 u32 count`, then `count` × 44-byte entries
 * `{u32 flag, char name[24] (cp932), u64 FILETIME, u32 offset, u32 size}`.
 * Compressed payloads (`flag == 1`) start with `u32 csize, u32 usize` followed by LZSS data;
 * we always decompress `raw[8 : entry.size]` until `usize` bytes are produced (never trusting
 * `csize`, which CK-GAL's repacker wrote 8 bytes short).
 */
class KcapArchive(val file: File) : Closeable {
    private val pf = PositionalFile(file)
    val entries: List<KcapEntry>
    private val byName: Map<String, KcapEntry>

    init {
        val head = pf.readLe(0, 16)
        val magic = ByteArray(4).also { head.get(it) }
        require(String(magic, Charsets.ISO_8859_1) == "KCAP") { "${file.name}: not a KCAP archive" }
        head.int
        head.int
        val count = head.int
        require(count in 0..1_000_000) { "${file.name}: bad entry count $count" }
        val idx = pf.readLe(16, count * ENTRY_SIZE)
        val list = ArrayList<KcapEntry>(count)
        val nameBuf = ByteArray(24)
        repeat(count) {
            val flag = idx.int
            idx.get(nameBuf)
            val nul = nameBuf.indexOf(0).let { if (it < 0) 24 else it }
            val name = Cp932.decode(nameBuf, 0, nul)
            val ft = idx.long
            val off = idx.int.toLong() and 0xFFFFFFFFL
            val size = idx.int
            list += KcapEntry(flag, name, ft, off, size)
        }
        entries = list
        byName = list.filter { it.isFile }.associateBy { it.name.lowercase() }
    }

    val files: List<KcapEntry> get() = entries.filter { it.isFile }

    fun find(name: String): KcapEntry? = byName[name.lowercase()]

    operator fun contains(name: String): Boolean = find(name) != null

    /** Returns the decompressed payload of [entry]. */
    fun read(entry: KcapEntry): ByteArray {
        val raw = pf.read(entry.offset, entry.size)
        if (!entry.isCompressed) return raw
        require(raw.size >= 8) { "${entry.name}: truncated LZSS header" }
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // csize: deliberately ignored (see class doc)
        val usize = bb.int
        require(usize in 0..(256 shl 20)) { "${entry.name}: bad usize $usize" }
        return OkumuraLzss.decompress(raw, 8, raw.size - 8, usize)
    }

    /** Reads only the first [len] decompressed bytes (cheap header peeks). */
    fun readHead(entry: KcapEntry, len: Int): ByteArray {
        if (!entry.isCompressed) return pf.read(entry.offset, minOf(len, entry.size))
        val raw = pf.read(entry.offset, minOf(entry.size, 8 + len * 2 + 64))
        val usize = ByteBuffer.wrap(raw, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return OkumuraLzss.decompress(raw, 8, raw.size - 8, minOf(len, usize))
    }

    fun read(name: String): ByteArray? = find(name)?.let { read(it) }

    override fun close() = pf.close()

    companion object {
        const val ENTRY_SIZE = 44

        fun isKcap(file: File): Boolean = runCatching {
            file.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                .contentEquals("KCAP".toByteArray())
        }.getOrDefault(false)
    }
}
