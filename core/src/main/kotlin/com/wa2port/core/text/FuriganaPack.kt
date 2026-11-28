package com.wa2port.core.text

import com.wa2port.core.ai.JpSentence
import com.wa2port.core.ai.OfflineAiPack
import com.wa2port.core.ai.PackRuby
import com.wa2port.core.archive.Cp932
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Display mode for automatic Japanese kanji furigana (日文汉字注音):
 * - [ALL]: show furigana above all annotated kanji in Japanese lines (default).
 * - [TAP]: keep auto-injected furigana veiled until the user taps a kanji (0 px layout shift);
 *   author script `<Rbase|reading>` rubies remain always visible.
 * - [OFF]: disable auto-injected furigana (author script `<Rbase|reading>` rubies still render).
 */
@Serializable
enum class FuriganaMode { ALL, TAP, OFF }

/**
 * Encodes Japanese reading strings (hiragana, katakana, kana marks, ASCII) into WA2 Shift-JIS
 * engine glyph codes, verifying that every code has a real glyph cell in [Wa2FontMap].
 */
object SjisKanaEncoder {
    /**
     * Maps a single Unicode character [ch] to its WA2 engine code, or -1 if unencodable or absent
     * from [Wa2FontMap].
     */
    fun encodeChar(ch: Char): Int {
        val direct = when (ch) {
            in '\u3041'..'\u3093' -> 0x829F + (ch.code - 0x3041) // Hiragana ぁ..ん
            in '\u30A1'..'\u30DF' -> 0x8340 + (ch.code - 0x30A1) // Katakana ァ..ミ (trail 0x40..0x7E)
            in '\u30E0'..'\u30F6' -> 0x8380 + (ch.code - 0x30E0) // Katakana ム..ヶ (skips trail 0x7F)
            '\u30FC' -> 0x815B // ー
            '\u30FB' -> 0x8145 // ・
            '\u30FD' -> 0x8152 // ヽ
            '\u30FE' -> 0x8153 // ヾ
            '\u309D' -> 0x8154 // ゝ
            '\u309E' -> 0x8155 // ゞ
            '\u3000' -> 0x8140 // full-width space
            in '\u0020'..'\u007D' -> ch.code // printable ASCII in Wa2FontMap
            else -> -1
        }
        if (direct >= 0) {
            return if (Wa2FontMap.cellOf(direct) >= 0) direct else -1
        }
        val s = ch.toString()
        val bytes = s.toByteArray(Cp932.charset)
        val code = when (bytes.size) {
            1 -> bytes[0].toInt() and 0xFF
            2 -> ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            else -> return -1
        }
        // Reject Cp932 substitution ('?') for characters that don't actually map to '?'.
        if (Cp932.decode(bytes) != s) return -1
        return if (Wa2FontMap.cellOf(code) >= 0) code else -1
    }

    /**
     * Encodes [reading] into WA2 engine codes, returning null if [reading] is empty/blank or
     * contains any character without a valid glyph in [Wa2FontMap].
     */
    fun encode(reading: String): IntArray? {
        val trimmed = reading.trim()
        if (trimmed.isEmpty()) return null
        val out = IntArray(trimmed.length)
        for (i in trimmed.indices) {
            val code = encodeChar(trimmed[i])
            if (code < 0) return null
            out[i] = code
        }
        return out
    }
}

/**
 * Clean-room, read-only binary furigana index (`WA2FURI1`).
 *
 * Stores ONLY `(sentenceHash64, sentenceUtf16Len, [(start, end, readingId)])` and a deduplicated
 * kana reading table — zero copyrighted script text. At runtime, [lookup] hashes the player's local
 * `script.pak` Japanese sentence via [OfflineAiPack.hash64], verifies [sentenceUtf16Len] and span
 * bounds, and returns non-overlapping [PackRuby] spans.
 *
 * Binary format (big-endian header + zlib/DEFLATE payload):
 * ```
 * 8B  magic          "WA2FURI1"
 * u32 formatVersion  1
 * u32 rawLen
 * u32 compLen
 * compLen B          zlib stream (java.util.zip.Inflater)
 * ```
 * Uncompressed payload:
 * - `varint readingCount`; repeated `readingCount` times: `varint byteLen` + UTF-8 reading
 * - `varint entryCount`; repeated `entryCount` times (strictly ascending signed big-endian i64 hash):
 *   - `i64 sentenceHash`
 *   - `varint sentenceUtf16Len`
 *   - `varint spanCount`
 *   - repeated `spanCount` times: `varint startDelta`, `varint spanLen`, `varint readingId`
 */
class FuriganaPack private constructor(
    private val readings: Array<String>,
    private val hashes: LongArray,
    private val utf16Lens: IntArray,
    private val spanOffsets: IntArray,
    private val spanStarts: IntArray,
    private val spanEnds: IntArray,
    private val spanReadingIds: IntArray,
) {
    /** Entry specification used by [encode]. */
    data class Entry(
        val sentenceHash: Long,
        val sentenceUtf16Len: Int,
        val spans: List<PackRuby>,
    )

    val readingCount: Int get() = readings.size
    val entryCount: Int get() = hashes.size
    val isEmpty: Boolean get() = hashes.isEmpty()

    /** Looks up validated, non-overlapping furigana spans for [jpSentence] (empty when absent). */
    fun lookup(jpSentence: String): List<PackRuby> = lookup(jpSentence, fallbackAiPack = null)

    /**
     * Looks up furigana spans for [jpSentence] in this pack; if no entry matches and
     * [fallbackAiPack] is provided (and open), falls back to the offline AI pack's kanji spans.
     */
    fun lookup(
        jpSentence: String,
        fallbackAiPack: OfflineAiPack?,
        scene: String? = null,
        token: Int? = null,
    ): List<PackRuby> {
        val len = jpSentence.length
        if (len == 0) return emptyList()
        val hash = OfflineAiPack.hash64(jpSentence)
        val idx = Arrays.binarySearch(hashes, hash)
        if (idx >= 0 && utf16Lens[idx] == len) {
            val from = spanOffsets[idx]
            val to = spanOffsets[idx + 1]
            if (from == to) return emptyList()
            val out = ArrayList<PackRuby>(to - from)
            var prevEnd = 0
            for (k in from until to) {
                val start = spanStarts[k]
                val end = spanEnds[k]
                val rid = spanReadingIds[k]
                if (start < prevEnd || start >= end || end > len || rid !in readings.indices) {
                    return emptyList()
                }
                val reading = readings[rid]
                if (reading.isEmpty()) return emptyList()
                out += PackRuby(start, end, reading)
                prevEnd = end
            }
            return out
        }
        if (fallbackAiPack != null && !fallbackAiPack.isClosed) {
            val aiIdx = fallbackAiPack.entryIndexFor(scene, token, jpSentence)
            if (aiIdx >= 0) {
                val entry = fallbackAiPack.entry(aiIdx)
                if (entry != null &&
                    entry.sentenceLength == len &&
                    entry.sentenceHash == hash &&
                    entry.spansValidFor(len) &&
                    entry.ruby.isNotEmpty()
                ) {
                    val out = ArrayList<PackRuby>(entry.ruby.size)
                    var prevEnd = 0
                    for (r in entry.ruby.sortedBy { it.start }) {
                        if (r.start >= prevEnd && r.start < r.end && r.end <= len && r.reading.isNotBlank()) {
                            out += r
                            prevEnd = r.end
                        }
                    }
                    return out
                }
            }
        }
        return emptyList()
    }

    companion object {
        const val MAGIC = "WA2FURI1"
        const val FORMAT_VERSION = 1
        const val RESOURCE_PATH = "/wa2/wa2_furigana.bin"
        private const val HEADER_SIZE = 20
        private const val MAX_RAW_LEN = 64 shl 20

        val EMPTY = FuriganaPack(
            readings = emptyArray(),
            hashes = LongArray(0),
            utf16Lens = IntArray(0),
            spanOffsets = IntArray(1),
            spanStarts = IntArray(0),
            spanEnds = IntArray(0),
            spanReadingIds = IntArray(0),
        )

        /** Process-wide bundled furigana pack loaded lazily from [RESOURCE_PATH] (fail-safe empty). */
        val shared: FuriganaPack by lazy { loadFromResource() }

        /** Loads a [FuriganaPack] from classpath resource [path], returning [EMPTY] if absent or corrupt. */
        fun loadFromResource(path: String = RESOURCE_PATH): FuriganaPack {
            val bytes = FuriganaPack::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: return EMPTY
            return runCatching { decode(bytes) }.getOrDefault(EMPTY)
        }

        /** Decodes a `WA2FURI1` binary buffer; throws [IllegalArgumentException]/[IllegalStateException] on malformed input. */
        fun decode(bytes: ByteArray): FuriganaPack {
            require(bytes.size >= HEADER_SIZE) { "WA2FURI1: buffer too small (${bytes.size})" }
            val magic = String(bytes, 0, 8, Charsets.US_ASCII)
            require(magic == MAGIC) { "WA2FURI1: bad magic '$magic'" }
            val bb = ByteBuffer.wrap(bytes, 8, 12).order(ByteOrder.BIG_ENDIAN)
            val version = bb.int
            require(version == FORMAT_VERSION) { "WA2FURI1: unsupported version $version" }
            val rawLen = bb.int
            val compLen = bb.int
            require(rawLen in 0..MAX_RAW_LEN) { "WA2FURI1: invalid rawLen $rawLen" }
            require(compLen >= 0 && HEADER_SIZE.toLong() + compLen.toLong() <= bytes.size.toLong()) {
                "WA2FURI1: invalid compLen $compLen for size ${bytes.size}"
            }
            val raw = inflate(bytes, HEADER_SIZE, compLen, rawLen)
            val reader = Reader(raw)
            val readingCount = reader.varint()
            val remAfterRc = raw.size - reader.pos
            require(readingCount in 0..remAfterRc) {
                "WA2FURI1: readingCount $readingCount exceeds remaining bytes $remAfterRc"
            }
            val readings = Array(readingCount) { reader.utf8() }
            val entryCount = reader.varint()
            val remAfterEc = raw.size - reader.pos
            // Minimum entry footprint: 8B hash + 1B slen varint + 1B spanCount varint = 10B.
            require(entryCount in 0..(remAfterEc / 10)) {
                "WA2FURI1: entryCount $entryCount exceeds remaining bytes $remAfterEc"
            }

            val hashes = LongArray(entryCount)
            val utf16Lens = IntArray(entryCount)
            val spanOffsets = IntArray(entryCount + 1)
            val initCap = minOf(entryCount.toLong() * 3L, (remAfterEc / 3).toLong()).toInt().coerceAtLeast(16)
            val starts = IntBuf(initCap)
            val ends = IntBuf(initCap)
            val rids = IntBuf(initCap)

            for (i in 0 until entryCount) {
                val h = reader.longBE()
                if (i > 0) {
                    require(h > hashes[i - 1]) { "WA2FURI1: hashes not strictly ascending at $i" }
                }
                hashes[i] = h
                val slen = reader.varint()
                require(slen >= 0) { "WA2FURI1: negative sentenceUtf16Len $slen" }
                utf16Lens[i] = slen
                val sc = reader.varint()
                val remForSpans = raw.size - reader.pos
                // Minimum span footprint: 3 varints (startDelta, spanLen, readingId) = 3B.
                require(sc in 0..(remForSpans / 3)) {
                    "WA2FURI1: spanCount $sc exceeds remaining bytes $remForSpans"
                }
                spanOffsets[i] = starts.size
                var prevEnd = 0L
                val slenLong = slen.toLong()
                for (s in 0 until sc) {
                    val startDelta = reader.varint().toLong()
                    val spanLen = reader.varint().toLong()
                    val rid = reader.varint()
                    require(startDelta >= 0L && spanLen > 0L) {
                        "WA2FURI1: invalid span delta=$startDelta len=$spanLen"
                    }
                    val start = prevEnd + startDelta
                    val end = start + spanLen
                    require(start >= prevEnd && end > start && end <= slenLong) {
                        "WA2FURI1: span [$start,$end) exceeds len $slen"
                    }
                    require(rid in 0 until readingCount) { "WA2FURI1: readingId $rid out of range" }
                    starts.add(start.toInt())
                    ends.add(end.toInt())
                    rids.add(rid)
                    prevEnd = end
                }
            }
            spanOffsets[entryCount] = starts.size
            require(reader.pos == raw.size) { "WA2FURI1: trailing bytes (${reader.pos}/${raw.size})" }
            return FuriganaPack(
                readings = readings,
                hashes = hashes,
                utf16Lens = utf16Lens,
                spanOffsets = spanOffsets,
                spanStarts = starts.toIntArray(),
                spanEnds = ends.toIntArray(),
                spanReadingIds = rids.toIntArray(),
            )
        }

        /** Encodes [entries] into the `WA2FURI1` binary format (sorts by hash; rejects duplicates). */
        fun encode(entries: List<Entry>): ByteArray {
            val sorted = entries.sortedBy { it.sentenceHash }
            for (i in 1 until sorted.size) {
                require(sorted[i].sentenceHash != sorted[i - 1].sentenceHash) {
                    "WA2FURI1: duplicate hash ${sorted[i].sentenceHash}"
                }
            }
            val readingToId = LinkedHashMap<String, Int>()
            val readings = ArrayList<String>()
            for (e in sorted) {
                for (sp in e.spans) {
                    if (sp.reading !in readingToId) {
                        readingToId[sp.reading] = readings.size
                        readings += sp.reading
                    }
                }
            }
            val rawOut = ByteArrayOutputStream(sorted.size * 24 + 64)
            fun writeVarint(v: Int) {
                require(v >= 0) { "WA2FURI1: negative varint $v" }
                var x = v
                while (x and 0x7F.inv() != 0) {
                    rawOut.write((x and 0x7F) or 0x80)
                    x = x ushr 7
                }
                rawOut.write(x)
            }
            fun writeLongBE(v: Long) {
                for (k in 7 downTo 0) rawOut.write(((v ushr (k * 8)) and 0xFF).toInt())
            }
            writeVarint(readings.size)
            for (r in readings) {
                val rb = r.toByteArray(Charsets.UTF_8)
                writeVarint(rb.size)
                rawOut.write(rb)
            }
            writeVarint(sorted.size)
            for (e in sorted) {
                require(e.sentenceUtf16Len >= 0) { "WA2FURI1: negative sentenceUtf16Len" }
                writeLongBE(e.sentenceHash)
                writeVarint(e.sentenceUtf16Len)
                val spans = e.spans.sortedBy { it.start }
                writeVarint(spans.size)
                var prevEnd = 0
                for (sp in spans) {
                    require(sp.start >= prevEnd && sp.end > sp.start && sp.end <= e.sentenceUtf16Len) {
                        "WA2FURI1: invalid span [${sp.start},${sp.end}) for len ${e.sentenceUtf16Len}"
                    }
                    writeVarint(sp.start - prevEnd)
                    writeVarint(sp.end - sp.start)
                    writeVarint(readingToId.getValue(sp.reading))
                    prevEnd = sp.end
                }
            }
            val raw = rawOut.toByteArray()
            val comp = deflate(raw)
            val out = ByteBuffer.allocate(HEADER_SIZE + comp.size).order(ByteOrder.BIG_ENDIAN)
            out.put(MAGIC.toByteArray(Charsets.US_ASCII))
            out.putInt(FORMAT_VERSION)
            out.putInt(raw.size)
            out.putInt(comp.size)
            out.put(comp)
            return out.array()
        }

        private fun deflate(raw: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            try {
                d.setInput(raw)
                d.finish()
                val out = ByteArrayOutputStream(raw.size / 2 + 64)
                val buf = ByteArray(16 * 1024)
                while (!d.finished()) {
                    val n = d.deflate(buf)
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                d.end()
            }
        }

        private fun inflate(src: ByteArray, off: Int, len: Int, rawLen: Int): ByteArray {
            val inf = Inflater()
            try {
                inf.setInput(src, off, len)
                val out = ByteArray(rawLen)
                var n = 0
                while (n < rawLen) {
                    val k = inf.inflate(out, n, rawLen - n)
                    if (k == 0) {
                        if (inf.finished() || inf.needsInput() || inf.needsDictionary()) break
                    }
                    n += k
                }
                if (n != rawLen) throw IllegalStateException("WA2FURI1: inflate got $n of $rawLen bytes")
                if (!inf.finished()) {
                    val probe = ByteArray(1)
                    val extra = inf.inflate(probe, 0, 1)
                    if (extra > 0 || !inf.finished()) {
                        throw IllegalStateException("WA2FURI1: inflate stream has extra data beyond $rawLen bytes")
                    }
                }
                return out
            } finally {
                inf.end()
            }
        }

        private class Reader(val b: ByteArray) {
            var pos = 0

            fun byte(): Int {
                if (pos >= b.size) throw IllegalStateException("WA2FURI1: unexpected EOF")
                return b[pos++].toInt() and 0xFF
            }

            fun varint(): Int {
                var shift = 0
                var r = 0
                while (true) {
                    val x = byte()
                    if (shift == 28 && x > 0x07) {
                        throw IllegalStateException("WA2FURI1: varint overflow")
                    }
                    r = r or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
            }

            fun utf8(): String {
                val n = varint()
                if (n < 0 || n > b.size - pos) throw IllegalStateException("WA2FURI1: bad string length $n")
                val s = String(b, pos, n, Charsets.UTF_8)
                pos += n
                return s
            }

            fun longBE(): Long {
                if (pos + 8 > b.size) throw IllegalStateException("WA2FURI1: unexpected EOF reading long")
                var v = 0L
                repeat(8) { v = (v shl 8) or (b[pos++].toInt() and 0xFF).toLong() }
                return v
            }
        }

        private class IntBuf(initialCap: Int) {
            private var data = IntArray(initialCap.coerceAtLeast(16))
            var size = 0
                private set

            fun add(v: Int) {
                if (size == data.size) {
                    data = data.copyOf(data.size * 2)
                }
                data[size++] = v
            }

            fun toIntArray(): IntArray = data.copyOf(size)
        }
    }
}

/**
 * Overlays clean-room [PackRuby] furigana spans onto a parsed WA2 [RichText] without ever
 * changing [RichText.baseCodes], glyph count, `<Fnn>` font sizes, `<S>` styles, or control items
 * (`NewLine`, `PageBreak`, `Wait`).
 *
 * Author script `<Rbase|reading>` ([TextItem.Ruby] with `auto == false`) is authoritative:
 * always preserved untouched in every [FuriganaMode], and always wins over any overlapping
 * [PackRuby] span.
 */
object FuriganaOverlay {
    fun apply(
        rich: RichText,
        pack: FuriganaPack = FuriganaPack.shared,
        decoder: BilingualTextDecoder = BilingualTextDecoder(),
        mode: FuriganaMode = FuriganaMode.ALL,
        fallbackAiPack: OfflineAiPack? = null,
        scene: String? = null,
        token: Int? = null,
    ): RichText {
        if (mode == FuriganaMode.OFF || rich.isEmpty) return rich
        val sentence = JpSentence.fromRich(rich, decoder).text
        if (sentence.isEmpty()) return rich
        val spans = pack.lookup(sentence, fallbackAiPack, scene, token)
        if (spans.isEmpty()) return rich
        return applySpans(rich, spans, decoder)
    }

    /**
     * Pure overlay of explicit [spans] (UTF-16 offsets in `JpSentence.fromRich(rich, decoder).text`)
     * onto [rich]. Returns [rich] unchanged if no valid auto-ruby span can be applied.
     */
    fun applySpans(
        rich: RichText,
        spans: List<PackRuby>,
        decoder: BilingualTextDecoder = BilingualTextDecoder(),
    ): RichText {
        if (rich.isEmpty || spans.isEmpty()) return rich

        // Normalize spans: valid bounds, non-blank reading, sorted, non-overlapping.
        val cleanSpans = ArrayList<PackRuby>(spans.size)
        var lastEnd = 0
        for (sp in spans.sortedBy { it.start }) {
            if (sp.start < lastEnd || sp.end <= sp.start || sp.reading.isBlank()) continue
            cleanSpans += sp
            lastEnd = sp.end
        }
        if (cleanSpans.isEmpty()) return rich

        val out = ArrayList<TextItem>(rich.items.size + cleanSpans.size * 2)
        var utf16Offset = 0
        var spanCursor = 0
        var modified = false

        for (item in rich.items) {
            when (item) {
                is TextItem.Ruby -> {
                    // Authoritative script ruby: preserve untouched and advance past its UTF-16 length.
                    for (code in item.base) {
                        utf16Offset += decoder.jpChar(code).length
                    }
                    out += item
                    while (spanCursor < cleanSpans.size && cleanSpans[spanCursor].start < utf16Offset) {
                        spanCursor++
                    }
                }
                TextItem.NewLine, TextItem.PageBreak, is TextItem.Wait -> {
                    // Control items advance UTF-16 offset by 0 and are never crossed or altered.
                    out += item
                }
                is TextItem.Run -> {
                    val codes = item.codes
                    val runStart = utf16Offset
                    val codeStarts = IntArray(codes.size)
                    val codeEnds = IntArray(codes.size)
                    for (k in codes.indices) {
                        codeStarts[k] = utf16Offset
                        utf16Offset += decoder.jpChar(codes[k]).length
                        codeEnds[k] = utf16Offset
                    }
                    val runEnd = utf16Offset

                    // Advance past any spans that started before this Run (e.g. crossed a NewLine/Ruby).
                    while (spanCursor < cleanSpans.size && cleanSpans[spanCursor].start < runStart) {
                        spanCursor++
                    }

                    var emittedUpTo = 0
                    var runModified = false
                    while (spanCursor < cleanSpans.size) {
                        val sp = cleanSpans[spanCursor]
                        if (sp.start >= runEnd) break
                        spanCursor++
                        // Skip spans that cross past the end of this Run (into a NewLine, Wait, Ruby, or <Fnn>).
                        if (sp.end > runEnd) continue

                        // Locate exact glyph boundaries matching [sp.start, sp.end), excluding 0-length control codes.
                        var gStart = -1
                        for (k in emittedUpTo until codes.size) {
                            if (codeStarts[k] == sp.start && codeEnds[k] > sp.start) {
                                gStart = k
                                break
                            }
                            if (codeStarts[k] > sp.start) break
                        }
                        if (gStart < 0) continue

                        var gEndExcl = -1
                        for (k in gStart until codes.size) {
                            if (codeEnds[k] == sp.end && codeStarts[k] < sp.end) {
                                gEndExcl = k + 1
                                break
                            }
                            if (codeEnds[k] > sp.end) break
                        }
                        if (gEndExcl <= gStart) continue

                        val readingCodes = SjisKanaEncoder.encode(sp.reading) ?: continue
                        if (gStart > emittedUpTo) {
                            out += TextItem.Run(codes.copyOfRange(emittedUpTo, gStart), item.size, item.style)
                        }
                        out += TextItem.Ruby(
                            base = codes.copyOfRange(gStart, gEndExcl),
                            reading = readingCodes,
                            size = item.size,
                            auto = true,
                        )
                        emittedUpTo = gEndExcl
                        runModified = true
                        modified = true
                    }

                    if (!runModified) {
                        out += item
                    } else if (emittedUpTo < codes.size) {
                        out += TextItem.Run(codes.copyOfRange(emittedUpTo, codes.size), item.size, item.style)
                    }
                }
            }
        }
        return if (modified) RichText(out) else rich
    }
}
