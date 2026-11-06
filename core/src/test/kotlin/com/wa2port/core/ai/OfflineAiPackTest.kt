package com.wa2port.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OfflineAiPackTest {
    @TempDir
    lateinit var dir: File

    private val sentence = "「本気で言ってるのか？　春希くん」"
    private val cn = "「你是认真的吗？春希君」"
    private val req = ExplainRequest(
        sentence = sentence,
        speakerJp = "かずさ",
        speakerCn = "和纱",
        officialCnTranslation = cn,
        scriptRuby = listOf(ScriptRuby(CharSpan(1, 3), "マジ")),
    )

    private val raw = RawExplanation(
        translation = "「你是说真的吗？春希君」",
        literalTranslation = "你是认真地在说吗？春希君",
        officialTranslationNote = "官方译文语气更平；原文更口语、更冲。",
        register = RegisterTag.CASUAL,
        tone = "惊讶、质问",
        ruby = listOf(RawRuby("本気", "ほんき"), RawRuby("言", "い"), RawRuby("春希", "はるき")),
        chunks = listOf(
            RawChunk("本気で", "まじで", "本気で", null, "状语", "认真地", null, listOf(RawVocab("本気", "本気", "ほんき", emptyList(), "na-adjective", "认真", null, "N3", null, null))),
            RawChunk(
                "言ってるのか", "いってるのか", "言っているのか", RawSpokenEvolution("縮約", "ている → てる", 0.4f), "谓语", "在说吗", "带质问",
                listOf(RawVocab("言ってる", "言う", "いう", emptyList(), "verb", "说", "て形+いる", "N5", null, null)),
            ),
            RawChunk("春希くん", "はるきくん", "春希くん", null, "称呼", "春希君", null, listOf(RawVocab("春希", "春希", "はるき", emptyList(), "proper-noun", "人名", null, null, null, null))),
        ),
        grammar = listOf(RawGrammar("〜のか", listOf("言ってるのか"), "表示追问、确认。", "N4")),
    )

    private fun full(r: ExplainRequest = req) = ExplainJsonCodec.toSentenceExplanation(raw, r, ExplanationMeta("x", "m", now = 1))

    private fun entryFor(r: ExplainRequest = req) = PackEntry.fromExplanation(full(r.copy(scriptRuby = emptyList())), r.copy(scriptRuby = emptyList()))

    private fun meta(n: Int) = AiPackMeta("v1", "wa2-pack-v1", "gemini-flash", 1000L, 1, n, n)

    private fun writePack(entries: List<PackEntry>, tokens: List<OfflineAiPackWriter.TokenRef>, name: String = "p.wa2ai"): File {
        val f = File(dir, name)
        OfflineAiPackWriter.write(f, meta(entries.size), entries, tokens)
        return f
    }

    @Test
    fun codecRoundTripIsLossless() {
        val e = entryFor()
        val back = PackCodec.decode(PackCodec.encode(e))
        assertEquals(e.copy(chunks = e.chunks.map { c -> c.copy(spokenEvolution = c.spokenEvolution?.copy(formality = null)) }),
            back.copy(chunks = back.chunks.map { c -> c.copy(spokenEvolution = c.spokenEvolution?.copy(formality = null)) }))
        assertEquals(0.4f, back.chunks[1].spokenEvolution!!.formality!!, 0.001f)
    }

    @Test
    fun cleanRoomEntryStoresNoScriptText() {
        val e = entryFor()
        val bytes = String(PackCodec.encode(e), Charsets.UTF_8)
        assertFalse(bytes.contains(sentence))
        assertFalse(bytes.contains(cn))
        assertFalse(bytes.contains("かずさ"))
        assertFalse(bytes.contains("和纱"))
        assertFalse(bytes.contains("言ってるのか"), "aligned chunk surface must not be stored")
        assertFalse(bytes.contains("マジ"), "script furigana must not be stored")
        assertTrue(e.chunks.all { it.unalignedSurface.isEmpty() })
        assertEquals("", e.chunks[0].textbookDiff, "unchanged textbook form stored as empty")
        assertEquals("言っているのか", e.chunks[1].textbookDiff)
        assertEquals("", e.chunks[2].vocab[0].lemma, "lemma == surface stored as empty")
        assertTrue(e.ruby.all { JapaneseText.hasKanji(sentence.substring(it.start, it.end)) })
    }

    @Test
    fun lookupRehydratesFullExplanationOverRequest() {
        val f = writePack(listOf(entryFor()), listOf(OfflineAiPackWriter.TokenRef("1001", 42, 0)))
        OfflineAiPack.open(f).use { pack ->
            val expected = full()
            val e = pack.lookup("1001", 42, req)!!
            assertTrue(e.fromCache)
            assertTrue(OfflineAiPack.isPackExplanation(e))
            assertEquals(sentence, e.sentence)
            assertEquals(sentence, e.ruby.joinToString("") { it.base })
            assertEquals(expected.ruby.filter { it.ruby != null }.map { it.span to it.ruby }, e.ruby.filter { it.ruby != null }.map { it.span to it.ruby })
            assertEquals("マジ", e.ruby.first { it.span == CharSpan(1, 3) }.ruby, "script furigana overlay")
            assertEquals(expected.chunks.map { it.surface }, e.chunks.map { it.surface })
            assertEquals(expected.chunks.map { it.textbookForm }, e.chunks.map { it.textbookForm })
            assertEquals(expected.chunks.map { it.reading }, e.chunks.map { it.reading })
            assertEquals(
                expected.chunks.map { c -> c.ruby.filter { it.ruby != null }.map { it.span to it.ruby } },
                e.chunks.map { c -> c.ruby.filter { it.ruby != null }.map { it.span to it.ruby } },
            )
            assertEquals(expected.vocab.map { listOf(it.surface, it.lemma, it.lemmaReading, it.partOfSpeech, it.meaning) }, e.vocab.map { listOf(it.surface, it.lemma, it.lemmaReading, it.partOfSpeech, it.meaning) })
            assertEquals(RubyBuilder.computeWordRuby("言う", "いう"), e.vocab[1].lemmaRuby)
            assertEquals(listOf("c1"), e.grammar[0].chunkIds)
            assertEquals(cn, e.officialCnTranslation)
            assertEquals("かずさ", e.speakerJp)
            assertEquals("和纱", e.speakerCn)
            assertEquals(raw.officialTranslationNote, e.officialTranslationNote)
            assertEquals(RegisterTag.CASUAL, e.register)
            assertEquals(pack.idFor(req), e.id)
            // Secondary hash index (no LineRef) and case-insensitive scene names.
            assertNotNull(pack.lookup(null, null, req))
            assertNotNull(pack.lookup("1001".lowercase(), 42, req))
            assertTrue(pack.contains("1001", 42, sentence))
            assertTrue(pack.contains(null, null, sentence))
            assertFalse(pack.contains("1001", 42, sentence + "x"))
            assertNull(pack.lookup("1001", 42, req.copy(sentence = "別の文")))
        }
    }

    @Test
    fun tokenPointingToOtherSentenceFallsBackToHashIndex() {
        val other = ExplainRequest(sentence = "「うん」")
        val otherEntry = PackEntry(OfflineAiPack.hash64(other.sentence), other.sentence.length, "嗯。", chunks = listOf(PackChunk(1, 3, SpanAlignment.EXACT, "", 1, "", "", null, "感叹", "嗯")))
        val f = writePack(listOf(entryFor(), otherEntry), listOf(OfflineAiPackWriter.TokenRef("1001", 1, 1)))
        OfflineAiPack.open(f).use { pack ->
            // (1001, 1) maps to 「うん」, but the request is the long sentence → hash index finds entry 0.
            val e = pack.lookup("1001", 1, req)
            assertNotNull(e)
            assertEquals(sentence, e!!.sentence)
            assertEquals("うん", pack.lookup("1001", 1, other)!!.chunks[0].surface)
        }
    }

    @Test
    fun scopedNoteOnlyShownForMatchingOfficialLine() {
        val e = PackEntry.fromExplanation(full(req.copy(scriptRuby = emptyList())), req.copy(scriptRuby = emptyList()), noteCnHash = OfflineAiPack.noteScopeHash(cn))
        val f = writePack(listOf(e), emptyList())
        OfflineAiPack.open(f).use { pack ->
            assertNotNull(pack.lookup(null, null, req)!!.officialTranslationNote)
            assertNotNull(pack.lookup(null, null, req.copy(officialCnTranslation = "「你是认真的吗？ 春希君」"))!!.officialTranslationNote, "whitespace-insensitive")
            assertNull(pack.lookup(null, null, req.copy(officialCnTranslation = "完全不同的译文"))!!.officialTranslationNote)
        }
    }

    @Test
    fun outOfBoundsSpansAreRejected() {
        val bad = entryFor().let { it.copy(chunks = it.chunks + PackChunk(5, 99, SpanAlignment.EXACT, "", 1, "", "", null, "x", "y")) }
        assertFalse(bad.spansValidFor(sentence.length))
        assertNull(bad.toExplanation(req, "id", "m", "p", 0))
        val badGrammar = entryFor().let { it.copy(grammar = listOf(PackGrammar("p", intArrayOf(7), "e"))) }
        assertNull(badGrammar.toExplanation(req, "id", "m", "p", 0))
    }

    @Test
    fun manyEntriesAcrossBlocksWithLruAndConcurrency() {
        val n = 64 * 7 + 5
        val sentences = (0 until n).map { "「テスト${it}番目の文です」" }
        val entries = sentences.map { s ->
            PackEntry(OfflineAiPack.hash64(s), s.length, "测试$s".takeLast(6), chunks = listOf(PackChunk(1, s.length - 1, SpanAlignment.EXACT, "", PackChunk.READING_HIRAGANA, "", "", null, "整句", "测试")))
        }
        val tokens = sentences.indices.map { OfflineAiPackWriter.TokenRef(if (it % 2 == 0) "1001" else "2001", it, it) }
        val f = writePack(entries, tokens)
        OfflineAiPack.open(f).use { pack ->
            assertEquals(n, pack.entryCount)
            assertEquals(n, pack.occurrenceCount)
            assertEquals(listOf("1001", "2001"), pack.scenes)
            val pool = Executors.newFixedThreadPool(8)
            val ok = AtomicInteger()
            repeat(8) { t ->
                pool.submit {
                    for (k in 0 until 600) {
                        val i = (k * 31 + t * 17) % n
                        val e = pack.lookup(if (i % 2 == 0) "1001" else "2001", i, ExplainRequest(sentences[i]))
                        if (e != null && e.chunks[0].surface == sentences[i].substring(1, sentences[i].length - 1)) ok.incrementAndGet()
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            assertEquals(8 * 600, ok.get())
            // Timing: a random lookup (cold block) stays well under a few ms.
            val t0 = System.nanoTime()
            for (i in 0 until n step 13) pack.lookup(null, null, ExplainRequest(sentences[i]))
            val perLookupMs = (System.nanoTime() - t0) / 1e6 / (n / 13 + 1)
            assertTrue(perLookupMs < 5.0, "lookup too slow: $perLookupMs ms")
        }
    }

    @Test
    fun closedPackReturnsNull() {
        val f = writePack(listOf(entryFor()), emptyList())
        val pack = OfflineAiPack.open(f)
        pack.close()
        assertTrue(pack.isClosed)
        assertNull(pack.lookup(null, null, req))
        assertFalse(pack.contains(null, null, sentence))
    }

    @Test
    fun corruptFilesAreRejectedByOpen() {
        val f = writePack(listOf(entryFor()), emptyList())
        val badMagic = File(dir, "bad.wa2ai").also { it.writeBytes(f.readBytes().also { b -> b[0] = 'X'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { OfflineAiPack.open(badMagic) }
        val truncated = File(dir, "trunc.wa2ai").also { it.writeBytes(f.readBytes().copyOf((f.length() - 10).toInt())) }
        assertThrows(Exception::class.java) { OfflineAiPack.open(truncated) }
        val garbage = File(dir, "garbage.wa2ai").also { it.writeBytes(ByteArray(4096) { (it * 7).toByte() }) }
        assertThrows(Exception::class.java) { OfflineAiPack.open(garbage) }
        // Flip a byte inside the last data block: open succeeds, the entry decodes to null (no crash).
        val flipped = File(dir, "flip.wa2ai").also { it.writeBytes(f.readBytes()) }
        RandomAccessFile(flipped, "rw").use { raf ->
            raf.seek(flipped.length() - 4)
            val b = raf.read()
            raf.seek(flipped.length() - 4)
            raf.write(b xor 0xFF)
        }
        OfflineAiPack.open(flipped).use { p -> runCatching { p.lookup(null, null, req) } }
        assertEquals("v1", OfflineAiPack.readMeta(f).packVersion)
    }

    @Test
    fun duplicateHashesAreRejectedByWriter() {
        val e = entryFor()
        assertThrows(IllegalArgumentException::class.java) { writePack(listOf(e, e), emptyList(), "dup.wa2ai") }
    }
}
