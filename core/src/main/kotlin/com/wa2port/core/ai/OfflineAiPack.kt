package com.wa2port.core.ai

import com.wa2port.core.archive.PositionalFile
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * Method 3A full-game offline AI explanation pack (`.wa2ai`).
 *
 * A random-access, DEFLATE-blocked archive of pre-generated Kotori-style sentence breakdowns for
 * every playable WA2 dialogue / narration line. STRICTLY CLEAN-ROOM: the pack never stores the
 * copyrighted script text — no sentence, no CK-GAL line, no speaker names, no aligned chunk /
 * vocab surfaces, no script furigana, no unchanged textbook forms. Only AI-written Chinese prose,
 * kanji furigana spans, character spans and dictionary data are stored; at lookup time the
 * explanation is re-hydrated over the caller's own [ExplainRequest] (the player's local
 * `script.pak` / `ck-gal.pak` text), see [PackEntry.toExplanation].
 *
 * File layout (big-endian unless noted):
 * ```
 * "WA2AIPK1"                     8-byte magic
 * u32 formatVersion              = 1
 * u32 metaLen, metaLen × u8      UTF-8 JSON [AiPackMeta]
 * u32 indexRawLen, u32 indexCompLen, indexCompLen × u8   DEFLATE'd index section
 * data blocks …                  DEFLATE'd blocks of ≤ [OfflineAiPack.ENTRIES_PER_BLOCK] entries
 * ```
 * Index section: `u32 sceneCount { UTF name(upper), u32 n, i32 token[n] (sorted), i32 entry[n] }`,
 * `u32 hashCount, i64 hash[hashCount] (sorted), i32 entry[hashCount]`,
 * `u32 blockCount, u32 entriesPerBlock { u64 offset (relative to the first data byte), u32 compLen, u32 rawLen }`.
 * Block payload: `varint n, varint len[n], entry bytes…` (see [PackCodec]).
 */

/** JSON header of a `.wa2ai` pack. */
@Serializable
data class AiPackMeta(
    val packVersion: String,
    val promptVersion: String,
    val model: String,
    val createdAt: Long,
    val sceneCount: Int,
    val occurrenceCount: Int,
    val entryCount: Int,
    val formatVersion: Int = OfflineAiPack.FORMAT_VERSION,
    val cleanRoom: Boolean = true,
    val description: String? = null,
)

/** Summary shown in settings (`覆盖 N 幕 · M 句 · X.X MB`). */
data class AiPackStats(
    val packVersion: String,
    val sceneCount: Int,
    val occurrenceCount: Int,
    val entryCount: Int,
    val fileBytes: Long,
    val model: String,
    val createdAt: Long,
)

// ------------------------------------------------------------------------------------------- entry model

/** Kanji-only furigana span over the sentence (script furigana is never stored). */
data class PackRuby(val start: Int, val end: Int, val reading: String)

/** Vocabulary item of a [PackChunk]; [unalignedSurface] only when the model surface is not in the sentence. */
data class PackVocab(
    val start: Int,
    val end: Int,
    val alignment: SpanAlignment,
    val unalignedSurface: String,
    /** "" = same as the (sliced) surface. */
    val lemma: String,
    val lemmaReading: String,
    val pos: String,
    val meaning: String,
    val conjugation: String? = null,
    val jlpt: String? = null,
    val pitchAccent: Int? = null,
    val notes: String? = null,
)

/** Semantic chunk; surfaces are re-sliced from the request sentence at lookup. */
data class PackChunk(
    val start: Int,
    val end: Int,
    val alignment: SpanAlignment,
    val unalignedSurface: String,
    /** [READING_EXPLICIT] / [READING_SURFACE] / [READING_HIRAGANA]. */
    val readingMode: Int,
    /** Only for [READING_EXPLICIT]. */
    val reading: String,
    /** "" when the textbook form equals the surface. */
    val textbookDiff: String,
    val spokenEvolution: SpokenEvolution? = null,
    val role: String,
    val meaning: String,
    val nuance: String? = null,
    val vocab: List<PackVocab> = emptyList(),
) {
    companion object {
        const val READING_EXPLICIT = 0
        const val READING_SURFACE = 1
        const val READING_HIRAGANA = 2
    }
}

data class PackGrammar(val pattern: String, val chunkIndices: IntArray, val explanation: String, val jlpt: String? = null) {
    override fun equals(other: Any?): Boolean = other is PackGrammar && pattern == other.pattern &&
        chunkIndices.contentEquals(other.chunkIndices) && explanation == other.explanation && jlpt == other.jlpt
    override fun hashCode(): Int = (pattern.hashCode() * 31 + chunkIndices.contentHashCode()) * 31 + explanation.hashCode()
}

/**
 * One clean-room breakdown, valid for exactly one plain Japanese sentence (identified by
 * [sentenceHash] = [OfflineAiPack.hash64] and [sentenceLength]).
 */
data class PackEntry(
    val sentenceHash: Long,
    val sentenceLength: Int,
    val translation: String,
    val literalTranslation: String? = null,
    val officialTranslationNote: String? = null,
    /** [OfflineAiPack.hash32] of the CK-GAL line the note compares against; 0 = applies to any. */
    val noteCnHash: Int = 0,
    val register: RegisterTag? = null,
    val tone: String? = null,
    val ruby: List<PackRuby> = emptyList(),
    val chunks: List<PackChunk> = emptyList(),
    val grammar: List<PackGrammar> = emptyList(),
) {
    /** All spans lie in `0 <= start <= end <= len`. */
    fun spansValidFor(len: Int): Boolean {
        fun ok(s: Int, e: Int) = s in 0..e && e <= len
        if (ruby.any { !ok(it.start, it.end) || it.start == it.end }) return false
        for (c in chunks) {
            if (!ok(c.start, c.end)) return false
            if (c.vocab.any { !ok(it.start, it.end) }) return false
        }
        return grammar.all { g -> g.chunkIndices.all { it in chunks.indices } }
    }

    /**
     * Re-hydrates the full [SentenceExplanation] over [req]`.sentence`: surfaces are sliced, the
     * sentence ruby is rebuilt from the stored kanji spans and overlaid with the authoritative
     * [ExplainRequest.scriptRuby], CK-GAL line / speakers come from [req]. Null when [req]'s
     * sentence does not match this entry or any span is out of bounds.
     */
    fun toExplanation(req: ExplainRequest, id: String, model: String, promptVersion: String, createdAt: Long): SentenceExplanation? {
        val sentence = req.sentence
        if (sentence.length != sentenceLength || OfflineAiPack.hash64(sentence) != sentenceHash) return null
        if (!spansValidFor(sentence.length)) return null

        // Sentence ruby: stored kanji spans + plain runs in the gaps, then the script overlay.
        val base = ArrayList<RubyToken>()
        var pos = 0
        for (r in ruby.sortedBy { it.start }) {
            if (r.start < pos) continue
            if (r.start > pos) base += RubyBuilder.plainRuns(sentence.substring(pos, r.start), pos)
            base += RubyToken(sentence.substring(r.start, r.end), r.reading, CharSpan(r.start, r.end))
            pos = r.end
        }
        if (pos < sentence.length) base += RubyBuilder.plainRuns(sentence.substring(pos), pos)
        val fullRuby = RubyBuilder.overlayScriptRuby(sentence, base, req.scriptRuby)

        val vocab = ArrayList<VocabEntry>()
        val chunks = chunks.mapIndexed { ci, c ->
            val span = CharSpan(c.start, c.end)
            val surface = if (c.alignment == SpanAlignment.UNALIGNED) c.unalignedSurface else sentence.substring(c.start, c.end)
            val reading = when (c.readingMode) {
                PackChunk.READING_SURFACE -> surface
                PackChunk.READING_HIRAGANA -> JapaneseText.katakanaToHiragana(surface)
                else -> c.reading
            }
            val ids = c.vocab.map { v ->
                val vSurface = if (v.alignment == SpanAlignment.UNALIGNED) v.unalignedSurface else sentence.substring(v.start, v.end)
                val lemma = v.lemma.ifEmpty { vSurface }
                val id = "v${vocab.size}"
                vocab += VocabEntry(
                    id = id,
                    span = CharSpan(v.start, v.end),
                    alignment = v.alignment,
                    surface = vSurface,
                    lemma = lemma,
                    lemmaReading = v.lemmaReading,
                    lemmaRuby = RubyBuilder.computeWordRuby(lemma, v.lemmaReading),
                    partOfSpeech = v.pos,
                    meaning = v.meaning,
                    conjugation = v.conjugation,
                    jlpt = v.jlpt,
                    pitchAccent = v.pitchAccent,
                    notes = v.notes,
                )
                id
            }
            SemanticChunk(
                id = "c$ci",
                span = span,
                alignment = c.alignment,
                surface = surface,
                reading = reading,
                ruby = RubyBuilder.sliceRuby(fullRuby, span),
                textbookForm = c.textbookDiff.ifEmpty { surface },
                spokenEvolution = c.spokenEvolution,
                role = c.role,
                meaning = c.meaning,
                nuance = c.nuance,
                vocabIds = ids,
            )
        }
        val grammar = grammar.mapIndexed { gi, g ->
            GrammarPoint(id = "g$gi", pattern = g.pattern, chunkIds = g.chunkIndices.map { "c$it" }.distinct(), explanation = g.explanation, jlpt = g.jlpt)
        }
        val official = req.officialCnTranslation?.takeIf { it.isNotBlank() }
        val note = officialTranslationNote?.takeIf {
            noteCnHash == 0 || (official != null && OfflineAiPack.noteScopeHash(official) == noteCnHash)
        }
        return SentenceExplanation(
            id = id,
            sentence = sentence,
            ruby = fullRuby,
            translation = translation,
            literalTranslation = literalTranslation,
            officialCnTranslation = official,
            officialTranslationNote = note,
            chunks = chunks,
            vocab = vocab,
            grammar = grammar,
            register = register,
            tone = tone,
            speakerJp = req.speakerJp,
            speakerCn = req.speakerCn,
            model = model,
            promptVersion = promptVersion,
            createdAt = createdAt,
            fromCache = true,
        )
    }

    companion object {
        /**
         * Strips a full [SentenceExplanation] of [req] down to its clean-room form: no sentence /
         * CK-GAL / speaker text, no aligned surfaces, no script furigana (spans overlapping
         * [ExplainRequest.scriptRuby] are dropped; the overlay re-adds them from the player's
         * script), no unchanged textbook forms. [noteCnHash] scopes the note to one CK-GAL line.
         */
        fun fromExplanation(expl: SentenceExplanation, req: ExplainRequest, noteCnHash: Int = 0): PackEntry {
            val sentence = expl.sentence
            val script = req.scriptRuby.filter { it.span.start >= 0 && it.span.end <= sentence.length && it.span.end > it.span.start }
            val ruby = expl.ruby.filter { t ->
                t.ruby != null && JapaneseText.hasKanji(t.base) && !t.span.isEmpty &&
                    script.none { it.span.start < t.span.end && t.span.start < it.span.end }
            }.map { PackRuby(it.span.start, it.span.end, it.ruby!!) }
            val vocabById = expl.vocab.associateBy { it.id }
            val chunkIndex = expl.chunks.mapIndexed { i, c -> c.id to i }.toMap()
            val chunks = expl.chunks.map { c ->
                val readingMode = when (c.reading) {
                    c.surface -> PackChunk.READING_SURFACE
                    JapaneseText.katakanaToHiragana(c.surface) -> PackChunk.READING_HIRAGANA
                    else -> PackChunk.READING_EXPLICIT
                }
                PackChunk(
                    start = c.span.start,
                    end = c.span.end,
                    alignment = c.alignment,
                    unalignedSurface = if (c.alignment == SpanAlignment.UNALIGNED) c.surface else "",
                    readingMode = readingMode,
                    reading = if (readingMode == PackChunk.READING_EXPLICIT) c.reading else "",
                    textbookDiff = if (c.textbookForm == c.surface) "" else c.textbookForm,
                    spokenEvolution = c.spokenEvolution,
                    role = c.role,
                    meaning = c.meaning,
                    nuance = c.nuance,
                    vocab = c.vocabIds.mapNotNull { vocabById[it] }.map { v ->
                        PackVocab(
                            start = v.span.start,
                            end = v.span.end,
                            alignment = v.alignment,
                            unalignedSurface = if (v.alignment == SpanAlignment.UNALIGNED) v.surface else "",
                            lemma = if (v.lemma == v.surface) "" else v.lemma,
                            lemmaReading = v.lemmaReading,
                            pos = v.partOfSpeech,
                            meaning = v.meaning,
                            conjugation = v.conjugation,
                            jlpt = v.jlpt,
                            pitchAccent = v.pitchAccent,
                            notes = v.notes,
                        )
                    },
                )
            }
            val grammar = expl.grammar.map { g ->
                PackGrammar(g.pattern, g.chunkIds.mapNotNull { chunkIndex[it] }.toIntArray(), g.explanation, g.jlpt)
            }
            return PackEntry(
                sentenceHash = OfflineAiPack.hash64(sentence),
                sentenceLength = sentence.length,
                translation = expl.translation,
                literalTranslation = expl.literalTranslation,
                officialTranslationNote = expl.officialTranslationNote,
                noteCnHash = if (expl.officialTranslationNote != null) noteCnHash else 0,
                register = expl.register,
                tone = expl.tone,
                ruby = ruby,
                chunks = chunks,
                grammar = grammar,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------- binary codec

/** Compact varint / UTF-8 entry serialisation used inside pack blocks. */
object PackCodec {
    private class Out {
        val bytes = ByteArrayOutputStream(512)
        fun varint(v: Int) {
            var x = v
            while (x and 0x7F.inv() != 0) {
                bytes.write((x and 0x7F) or 0x80)
                x = x ushr 7
            }
            bytes.write(x)
        }
        fun str(s: String?) {
            val b = (s ?: "").toByteArray(Charsets.UTF_8)
            varint(b.size)
            bytes.write(b, 0, b.size)
        }
        fun byte(v: Int) = bytes.write(v)
        fun long(v: Long) {
            for (k in 7 downTo 0) bytes.write(((v ushr (k * 8)) and 0xFF).toInt())
        }
        fun int32(v: Int) {
            for (k in 3 downTo 0) bytes.write((v ushr (k * 8)) and 0xFF)
        }
    }

    private class In(val b: ByteArray, var p: Int, val end: Int) {
        fun byte(): Int {
            if (p >= end) throw IllegalStateException("truncated entry")
            return b[p++].toInt() and 0xFF
        }
        fun varint(): Int {
            var shift = 0
            var r = 0
            while (true) {
                val x = byte()
                r = r or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 28) throw IllegalStateException("bad varint")
            }
        }
        fun str(): String {
            val n = varint()
            if (n < 0 || p + n > end) throw IllegalStateException("bad string length")
            val s = String(b, p, n, Charsets.UTF_8)
            p += n
            return s
        }
        fun optStr(): String? = str().ifEmpty { null }
        fun long(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or byte().toLong() }
            return v
        }
        fun int32(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or byte() }
            return v
        }
    }

    private fun alignCode(a: SpanAlignment) = a.ordinal
    private fun alignOf(c: Int) = SpanAlignment.entries.getOrElse(c) { SpanAlignment.UNALIGNED }

    fun encode(e: PackEntry): ByteArray {
        val o = Out()
        o.long(e.sentenceHash)
        o.varint(e.sentenceLength)
        o.str(e.translation)
        o.str(e.literalTranslation)
        o.str(e.officialTranslationNote)
        o.int32(e.noteCnHash)
        o.str(e.register?.wire)
        o.str(e.tone)
        o.varint(e.ruby.size)
        for (r in e.ruby) {
            o.varint(r.start); o.varint(r.end); o.str(r.reading)
        }
        o.varint(e.chunks.size)
        for (c in e.chunks) {
            o.varint(c.start); o.varint(c.end); o.byte(alignCode(c.alignment)); o.str(c.unalignedSurface)
            o.byte(c.readingMode); o.str(c.reading); o.str(c.textbookDiff)
            val evo = c.spokenEvolution
            if (evo == null) {
                o.byte(0)
            } else {
                o.byte(1); o.str(evo.kind); o.str(evo.derivation)
                o.varint(evo.formality?.let { (it.coerceIn(0f, 1f) * 1000f).toInt() + 1 } ?: 0)
            }
            o.str(c.role); o.str(c.meaning); o.str(c.nuance)
            o.varint(c.vocab.size)
            for (v in c.vocab) {
                o.varint(v.start); o.varint(v.end); o.byte(alignCode(v.alignment)); o.str(v.unalignedSurface)
                o.str(v.lemma); o.str(v.lemmaReading); o.str(v.pos); o.str(v.meaning)
                o.str(v.conjugation); o.str(v.jlpt); o.varint(v.pitchAccent?.let { it + 1 }?.coerceAtLeast(0) ?: 0); o.str(v.notes)
            }
        }
        o.varint(e.grammar.size)
        for (g in e.grammar) {
            o.str(g.pattern)
            o.varint(g.chunkIndices.size)
            g.chunkIndices.forEach { o.varint(it) }
            o.str(g.explanation); o.str(g.jlpt)
        }
        return o.bytes.toByteArray()
    }

    fun decode(b: ByteArray, off: Int = 0, len: Int = b.size - off): PackEntry {
        val i = In(b, off, off + len)
        val hash = i.long()
        val slen = i.varint()
        val tr = i.str()
        val lt = i.optStr()
        val nt = i.optStr()
        val ntHash = i.int32()
        val reg = RegisterTag.parse(i.optStr())
        val tone = i.optStr()
        val ruby = List(i.varint()) { PackRuby(i.varint(), i.varint(), i.str()) }
        val chunks = List(i.varint()) {
            val s = i.varint(); val e = i.varint(); val a = alignOf(i.byte()); val us = i.str()
            val rm = i.byte(); val rd = i.str(); val tb = i.str()
            val evo = if (i.byte() == 1) {
                val k = i.str(); val d = i.str(); val f = i.varint()
                SpokenEvolution(k, d, if (f == 0) null else (f - 1) / 1000f)
            } else {
                null
            }
            val role = i.str(); val meaning = i.str(); val nuance = i.optStr()
            val vocab = List(i.varint()) {
                PackVocab(
                    start = i.varint(), end = i.varint(), alignment = alignOf(i.byte()), unalignedSurface = i.str(),
                    lemma = i.str(), lemmaReading = i.str(), pos = i.str(), meaning = i.str(),
                    conjugation = i.optStr(), jlpt = i.optStr(), pitchAccent = i.varint().let { if (it == 0) null else it - 1 }, notes = i.optStr(),
                )
            }
            PackChunk(s, e, a, us, rm, rd, tb, evo, role, meaning, nuance, vocab)
        }
        val grammar = List(i.varint()) {
            val p = i.str()
            val idx = IntArray(i.varint()) { i.varint() }
            PackGrammar(p, idx, i.str(), i.optStr())
        }
        return PackEntry(hash, slen, tr, lt, nt, ntHash, reg, tone, ruby, chunks, grammar)
    }

    internal fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 64)
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    internal fun inflate(comp: ByteArray, rawLen: Int): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(comp)
            val out = ByteArray(rawLen)
            var n = 0
            while (n < rawLen) {
                val k = inf.inflate(out, n, rawLen - n)
                if (k == 0) {
                    if (inf.finished() || inf.needsInput() || inf.needsDictionary()) break
                }
                n += k
            }
            if (n != rawLen) throw IllegalStateException("inflate: got $n of $rawLen bytes")
            return out
        } finally {
            inf.end()
        }
    }
}

// ------------------------------------------------------------------------------------------- writer

/** Builds a `.wa2ai` file. */
object OfflineAiPackWriter {
    /** One `(scene, token) → entry` row of the primary index. */
    data class TokenRef(val scene: String, val token: Int, val entry: Int)

    /**
     * Writes [entries] (entry index = list index) with the primary [tokens] index to [file]
     * atomically (`.tmp` + rename). Every entry is reachable through the secondary hash index;
     * duplicate sentence hashes are rejected.
     */
    fun write(file: File, meta: AiPackMeta, entries: List<PackEntry>, tokens: List<TokenRef>) {
        val hashes = entries.mapIndexed { i, e -> e.sentenceHash to i }.sortedWith(compareBy({ it.first }, { it.second }))
        for (k in 1 until hashes.size) {
            require(hashes[k].first != hashes[k - 1].first) { "duplicate sentence hash between entries ${hashes[k - 1].second} and ${hashes[k].second}" }
        }
        tokens.forEach { require(it.entry in entries.indices) { "token ${it.scene}/${it.token} → bad entry ${it.entry}" } }
        val byScene = tokens.groupBy { it.scene.uppercase() }.toSortedMap()

        // Blocks.
        val per = OfflineAiPack.ENTRIES_PER_BLOCK
        val blocks = ArrayList<Pair<ByteArray, Int>>() // compressed, rawLen
        var k = 0
        while (k < entries.size) {
            val slice = entries.subList(k, minOf(entries.size, k + per)).map { PackCodec.encode(it) }
            val raw = ByteArrayOutputStream()
            fun varint(v: Int) {
                var x = v
                while (x and 0x7F.inv() != 0) { raw.write((x and 0x7F) or 0x80); x = x ushr 7 }
                raw.write(x)
            }
            varint(slice.size)
            slice.forEach { varint(it.size) }
            slice.forEach { raw.write(it) }
            val rb = raw.toByteArray()
            blocks += PackCodec.deflate(rb) to rb.size
            k += per
        }

        val metaBytes = AiJson.encodeToString(AiPackMeta.serializer(), meta).toByteArray(Charsets.UTF_8)

        // Block offsets are relative to the first data byte (right after the index blob), so the
        // index never depends on its own compressed size.
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { d ->
            d.writeInt(byScene.size)
            for ((scene, rows) in byScene) {
                val sorted = rows.sortedBy { it.token }
                for (j in 1 until sorted.size) require(sorted[j].token != sorted[j - 1].token) { "duplicate token $scene/${sorted[j].token}" }
                d.writeUTF(scene)
                d.writeInt(sorted.size)
                sorted.forEach { d.writeInt(it.token) }
                sorted.forEach { d.writeInt(it.entry) }
            }
            d.writeInt(hashes.size)
            hashes.forEach { d.writeLong(it.first) }
            hashes.forEach { d.writeInt(it.second) }
            d.writeInt(blocks.size)
            d.writeInt(per)
            var off = 0L
            for ((comp, rawLen) in blocks) {
                d.writeLong(off); d.writeInt(comp.size); d.writeInt(rawLen)
                off += comp.size
            }
        }
        val rawIndex = bos.toByteArray()
        val compIndex = PackCodec.deflate(rawIndex)

        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(OfflineAiPack.MAGIC.toByteArray(Charsets.US_ASCII))
            raf.writeInt(OfflineAiPack.FORMAT_VERSION)
            raf.writeInt(metaBytes.size)
            raf.write(metaBytes)
            raf.writeInt(rawIndex.size)
            raf.writeInt(compIndex.size)
            raf.write(compIndex)
            blocks.forEach { raf.write(it.first) }
        }
        if (file.exists()) file.delete()
        check(tmp.renameTo(file)) { "rename ${tmp.name} → ${file.name} failed" }
    }
}

// ------------------------------------------------------------------------------------------- reader

/**
 * Read-only, thread-safe random-access reader of a `.wa2ai` pack. Indices live in memory
 * (≈ 12 bytes per line); entry blocks are read lazily with lock-free positional reads and kept in
 * a tiny synchronized LRU of [BLOCK_CACHE] decoded blocks. After [close] every lookup returns null.
 */
class OfflineAiPack private constructor(
    val file: File,
    private val pf: PositionalFile,
    val meta: AiPackMeta,
    private val sceneNames: Array<String>,
    private val sceneTokens: Array<IntArray>,
    private val sceneEntries: Array<IntArray>,
    private val hashes: LongArray,
    private val hashEntries: IntArray,
    private val entryHash: LongArray,
    private val blockOffset: LongArray,
    private val blockComp: IntArray,
    private val blockRaw: IntArray,
    private val entriesPerBlock: Int,
) : Closeable {
    @Volatile
    private var closed = false

    private class DecodedBlock(val raw: ByteArray, val offsets: IntArray, val lengths: IntArray)

    private val blockCache = object : LinkedHashMap<Int, DecodedBlock>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, DecodedBlock>?): Boolean = size > BLOCK_CACHE
    }

    val entryCount: Int get() = entryHash.size
    val occurrenceCount: Int get() = sceneTokens.sumOf { it.size }
    val isClosed: Boolean get() = closed

    /** Model label stamped on re-hydrated explanations. */
    val modelLabel: String get() = "$MODEL_PREFIX${meta.packVersion}/${meta.model}"

    val stats: AiPackStats
        get() = AiPackStats(meta.packVersion, sceneNames.size, occurrenceCount, entryCount, pf.length, meta.model, meta.createdAt)

    /** Scene names (upper-case) covered by the primary index. */
    val scenes: List<String> get() = sceneNames.toList()

    private fun tokenEntry(scene: String?, token: Int?): Int {
        if (scene == null || token == null) return -1
        val si = sceneNames.binarySearch(scene.uppercase())
        if (si < 0) return -1
        val ti = sceneTokens[si].binarySearch(token)
        return if (ti >= 0) sceneEntries[si][ti] else -1
    }

    private fun hashEntry(h: Long): Int {
        val k = hashes.binarySearch(h)
        return if (k >= 0) hashEntries[k] else -1
    }

    /**
     * Entry index for the line: the primary `(scene, token)` index when its entry was built for
     * exactly [sentence], else the secondary sentence-hash index; -1 when not covered.
     */
    fun entryIndexFor(scene: String?, token: Int?, sentence: String): Int {
        if (closed || sentence.isEmpty()) return -1
        val h = hash64(sentence)
        val t = tokenEntry(scene, token)
        if (t >= 0 && entryHash[t] == h) return t
        return hashEntry(h)
    }

    /** True when the pack has a breakdown for [sentence] (no block I/O). */
    fun contains(scene: String?, token: Int?, sentence: String): Boolean = entryIndexFor(scene, token, sentence) >= 0

    /** Decoded clean-room entry [idx], or null when out of range / closed / corrupt. */
    fun entry(idx: Int): PackEntry? {
        if (closed || idx !in entryHash.indices) return null
        val bi = idx / entriesPerBlock
        val block = block(bi) ?: return null
        val k = idx - bi * entriesPerBlock
        if (k !in block.offsets.indices) return null
        return runCatching { PackCodec.decode(block.raw, block.offsets[k], block.lengths[k]) }.getOrNull()
    }

    private fun block(bi: Int): DecodedBlock? {
        synchronized(blockCache) { blockCache[bi]?.let { return it } }
        if (bi !in blockOffset.indices) return null
        val decoded = runCatching {
            val comp = pf.read(blockOffset[bi], blockComp[bi])
            val raw = PackCodec.inflate(comp, blockRaw[bi])
            var p = 0
            fun varint(): Int {
                var shift = 0; var r = 0
                while (true) {
                    val x = raw[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
            }
            val n = varint()
            val lengths = IntArray(n) { varint() }
            val offsets = IntArray(n)
            var off = p
            for (j in 0 until n) { offsets[j] = off; off += lengths[j] }
            require(off <= raw.size) { "block $bi overflows" }
            DecodedBlock(raw, offsets, lengths)
        }.getOrNull() ?: return null
        synchronized(blockCache) { blockCache[bi] = decoded }
        return decoded
    }

    /**
     * Full breakdown of the line ([scene]/[token] = [com.wa2port.core.engine.LineRef] when known)
     * re-hydrated over [req] (`fromCache = true`), or null when not covered.
     */
    fun lookup(scene: String?, token: Int?, req: ExplainRequest): SentenceExplanation? {
        val idx = entryIndexFor(scene, token, req.sentence)
        if (idx < 0) return null
        val e = entry(idx) ?: return null
        return e.toExplanation(req, idFor(req), modelLabel, meta.promptVersion, meta.createdAt)
    }

    /** Stable explanation id (notebook / follow-up thread key), distinct from online cache keys. */
    fun idFor(req: ExplainRequest): String = ExplainCache.key(req, modelLabel)

    /** Every decoded raw block (tests / clean-room leak audits). */
    fun rawBlocks(): Sequence<ByteArray> = sequence {
        for (bi in blockOffset.indices) yield(PackCodec.inflate(pf.read(blockOffset[bi], blockComp[bi]), blockRaw[bi]))
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(blockCache) { blockCache.clear() }
        runCatching { pf.close() }
    }

    companion object {
        const val MAGIC = "WA2AIPK1"
        const val FORMAT_VERSION = 1
        const val ENTRIES_PER_BLOCK = 64
        const val BLOCK_CACHE = 4
        const val MODEL_PREFIX = "offline-pack:"

        /** Fixed release asset name. */
        const val FILE_NAME = "wa2-ai-pack-v1.wa2ai"

        /** First 8 bytes (big-endian) of SHA-256 over the UTF-8 sentence. */
        fun hash64(s: String): Long {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(d, 0, 8).order(ByteOrder.BIG_ENDIAN).long
        }

        /** First 4 bytes of SHA-256 (never 0, which means "unscoped"). */
        fun hash32(s: String): Int {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val v = ByteBuffer.wrap(d, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            return if (v == 0) 1 else v
        }

        /** [hash32] of a CK-GAL line with all whitespace removed (CK-GAL repeats lines with spacing variants). */
        fun noteScopeHash(cn: String): Int = hash32(cnKey(cn))

        /** Whitespace-insensitive CK-GAL comparison key. */
        fun cnKey(cn: String): String = cn.filterNot { it.isWhitespace() || it == '\u3000' }

        /** True when [expl] was re-hydrated from an offline pack. */
        fun isPackExplanation(expl: SentenceExplanation?): Boolean = expl?.model?.startsWith(MODEL_PREFIX) == true

        /** Reads only the JSON header (cheap structural probe); throws on a malformed file. */
        fun readMeta(file: File): AiPackMeta = PositionalFile(file).use { pf -> readHeader(pf).first }

        private fun readHeader(pf: PositionalFile): Pair<AiPackMeta, Long> {
            require(pf.length >= 24) { "文件过小，不是离线解读包 File too small" }
            val head = pf.read(0, 16)
            val magic = String(head, 0, 8, Charsets.US_ASCII)
            require(magic == MAGIC) { "不是 WA2 离线解读包 Bad magic" }
            val bb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
            val version = bb.getInt(8)
            require(version == FORMAT_VERSION) { "不支持的离线包格式版本 $version" }
            val metaLen = bb.getInt(12)
            require(metaLen in 2..(1 shl 20) && 16L + metaLen <= pf.length) { "离线包头损坏 Bad meta length" }
            val meta = AiJson.decodeFromString(AiPackMeta.serializer(), String(pf.read(16, metaLen), Charsets.UTF_8))
            return meta to (16L + metaLen)
        }

        /** Opens and structurally validates [file]; throws [IllegalArgumentException]/[IllegalStateException] when corrupt. */
        fun open(file: File): OfflineAiPack {
            val pf = PositionalFile(file)
            try {
                val (meta, idxPos) = readHeader(pf)
                val lens = ByteBuffer.wrap(pf.read(idxPos, 8)).order(ByteOrder.BIG_ENDIAN)
                val rawLen = lens.getInt(0)
                val compLen = lens.getInt(4)
                require(rawLen in 4..(256 shl 20) && compLen in 1..(256 shl 20) && idxPos + 8 + compLen <= pf.length) { "离线包索引损坏 Bad index" }
                val raw = PackCodec.inflate(pf.read(idxPos + 8, compLen), rawLen)
                val d = DataInputStream(raw.inputStream())
                val sceneCount = d.readInt()
                require(sceneCount in 0..100_000) { "bad scene count" }
                val names = arrayOfNulls<String>(sceneCount)
                val toks = arrayOfNulls<IntArray>(sceneCount)
                val ents = arrayOfNulls<IntArray>(sceneCount)
                for (s in 0 until sceneCount) {
                    names[s] = d.readUTF()
                    val n = d.readInt()
                    require(n in 0..10_000_000) { "bad token count" }
                    toks[s] = IntArray(n) { d.readInt() }
                    ents[s] = IntArray(n) { d.readInt() }
                }
                val hc = d.readInt()
                require(hc in 0..10_000_000) { "bad hash count" }
                val hs = LongArray(hc) { d.readLong() }
                val he = IntArray(hc) { d.readInt() }
                val bc = d.readInt()
                val per = d.readInt()
                require(bc in 0..1_000_000 && per in 1..4096) { "bad block table" }
                val dataBase = idxPos + 8 + compLen
                val bo = LongArray(bc); val bcl = IntArray(bc); val brl = IntArray(bc)
                for (b in 0 until bc) {
                    bo[b] = dataBase + d.readLong(); bcl[b] = d.readInt(); brl[b] = d.readInt()
                    require(bo[b] >= 0 && bcl[b] >= 0 && bo[b] + bcl[b] <= pf.length) { "离线包数据块越界 Block $b out of file" }
                }
                val entryCount = hc
                require(entryCount <= bc.toLong() * per) { "entry count exceeds blocks" }
                val eh = LongArray(entryCount)
                for (k in 0 until hc) {
                    require(he[k] in 0 until entryCount) { "bad hash entry" }
                    if (k > 0) require(hs[k] > hs[k - 1]) { "hash index not sorted" }
                    eh[he[k]] = hs[k]
                }
                @Suppress("UNCHECKED_CAST")
                val sortedNames = names as Array<String>
                for (s in 1 until sceneCount) require(sortedNames[s] > sortedNames[s - 1]) { "scene index not sorted" }
                for (s in 0 until sceneCount) {
                    val t = toks[s]!!
                    for (j in 1 until t.size) require(t[j] > t[j - 1]) { "token index not sorted" }
                    require(ents[s]!!.all { it in 0 until entryCount }) { "bad token entry" }
                }
                return OfflineAiPack(
                    file, pf, meta, sortedNames,
                    toks.map { it!! }.toTypedArray(), ents.map { it!! }.toTypedArray(),
                    hs, he, eh, bo, bcl, brl, per,
                )
            } catch (t: Throwable) {
                runCatching { pf.close() }
                throw t
            }
        }
    }
}
