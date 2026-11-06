package com.wa2port.core.ai

import com.wa2port.core.TestAssets
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Corpus pipeline: compact-schema validator (pure) + real-corpus extraction, 3A round trip and zero-leak audit. */
class AiPackCorpusTest {
    @TempDir
    lateinit var dir: File

    private fun unit(jp: String, cn: String? = null, cnv: Int = 1, srb: List<ScriptRuby> = emptyList()) =
        CorpusUnit("u00001", jp, cn, null, srb, emptyList(), 1, cnv, AiPackCorpus.isLexical(jp))

    private fun obj(json: String) = AiJson.parseToJsonElement(json) as JsonObject

    private val good = """{"i":"u00001","tr":"终于还是下起来了。","lt":"终于，（雪）落下来了。","rg":"plain","tn":"无奈","rb":[["降","ふ"]],"ch":[["とうとう","とうとう",null,"副词","终于",[["とうとう","","とうとう","adverb","终于","","N3"]]],["降ってきた","ふってきた",null,"谓语","下起来了",[["降って","降る","ふる","verb","下（雨雪）","て形","N5"],["きた","来る","くる","auxiliary","开始…起来","过去式","N4"]]]],"gr":[["〜てくる",["降ってきた"],"表示变化开始。","N4"]]}"""

    @Test
    fun compactItemValidatesAndRoundTrips() {
        val r = AiPackCorpus.validateItem(unit("とうとう、降ってきた。", "终于，还是下起来了。"), obj(good))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        val e = r.entry!!.toExplanation(ExplainRequest("とうとう、降ってきた。"), "id", "m", "p", 0)!!
        assertEquals(listOf("とうとう", "降ってきた"), e.chunks.map { it.surface })
        assertEquals("ふ", e.ruby.first { it.base == "降" }.ruby)
        assertEquals(listOf("とうとう", "降る", "来る"), e.vocab.map { it.lemma })
        assertEquals("ふってきた", e.chunks[1].reading)
    }

    @Test
    fun fullRawExplanationSchemaIsAccepted() {
        val full = """{"i":"u00001","translation":"嗯。","ruby":[],"chunks":[{"surface":"うん","reading":"うん","textbookForm":"うん","role":"应答","meaning":"嗯","vocab":[{"surface":"うん","lemma":"うん","lemmaReading":"うん","partOfSpeech":"interjection","meaning":"嗯"}]}],"grammar":[]}"""
        val r = AiPackCorpus.validateItem(unit("「うん」"), obj(full))
        assertTrue(r.errors.isEmpty(), r.errors.toString())
        assertEquals(PackChunk.READING_SURFACE, r.entry!!.chunks[0].readingMode)
    }

    @Test
    fun badItemsReportActionableErrors() {
        val u = unit("耳をつんざくジェットエンジンの音が、ゆっくりと遠ざかっていく。", "那震耳欲聋的飞机引擎声， 也渐渐地远去了。")
        val r = AiPackCorpus.validateItem(u, obj("""{"i":"u00001","tr":"TODO","ch":[["耳をつんざく","みみをつんざく",null,"定语","震耳欲聋的",[]],["ジェット音","じぇっと",null,"x","y",[]]]}"""))
        val text = r.errors.joinToString("\n")
        assertTrue(text.contains("placeholder"), text)
        assertTrue(text.contains("not a verbatim substring"), text)
        assertTrue(text.contains("cover only"), text)
        assertTrue(text.contains("kanji without reading"), text)
        val leak = AiPackCorpus.validateItem(u, obj("""{"i":"u00001","tr":"那震耳欲聋的飞机引擎声， 也渐渐地远去了。","ch":[]}"""))
        assertTrue(leak.errors.any { it.contains("clean-room") }, leak.errors.toString())
        val blank = AiPackCorpus.validateItem(u, obj("""{"i":"u00001","tr":"   "}"""))
        assertTrue(blank.errors.any { it.contains("tr is blank") })
        val japanese = AiPackCorpus.validateItem(unit("「うん」"), obj("""{"i":"u00001","tr":"うんうんうん","ch":[["うん","うん",null,"应答","嗯",[]]]}"""))
        assertTrue(japanese.errors.any { it.contains("Japanese") || it.contains("Chinese") }, japanese.errors.toString())
    }

    @Test
    fun silentEntriesForPunctuationOnlyLines() {
        for (jp in listOf("「……」", "「……！」", "――", "「？」", "………")) {
            assertTrue(!AiPackCorpus.isLexical(jp), jp)
            val e = AiPackCorpus.silentEntry(jp).toExplanation(ExplainRequest(jp), "id", "m", "p", 0)
            assertNotNull(e, jp)
            assertTrue(e!!.translation.isNotBlank() && e.chunks.size == 1)
        }
        assertTrue(AiPackCorpus.isLexical("「ＯＫ」"))
        assertTrue(AiPackCorpus.isLexical("「あ…」"))
    }

    /**
     * Real corpus: every playable line of all 187 scenes is extracted, a full synthetic clean-room
     * pack (one entry per distinct sentence) resolves every `(scene, token)` and every sentence hash,
     * and no script / CK-GAL line ≥ 12 chars appears in any decompressed block.
     */
    @Test
    fun realCorpusFullPackRoundTripAndZeroLeak() {
        val assets = TestAssets.require()
        val ex = AiPackCorpus.extract(assets)
        assertEquals(187, ex.audit.scenes)
        assertTrue(ex.occurrences.size > 70_000, "occurrences ${ex.occurrences.size}")
        assertEquals(0, ex.audit.nonLiteralMessageCalls)
        assertEquals(0, ex.audit.unreferencedJapaneseTokens.size, "every JP token must be attributed")
        assertEquals(ex.occurrences.size, ex.occurrences.map { it.scene to it.token }.toSet().size)
        val units = ex.units()
        val entries = units.map { u ->
            if (!u.lexical) return@map AiPackCorpus.silentEntry(u.jp)
            // Synthetic "model output": OfflineExplainer phrase chunks, model-free prose (no script text).
            val req = AiPackCorpus.unitRequest(u).copy(scriptRuby = emptyList())
            val skel = OfflineExplainer.build(req)
            val raw = RawExplanation(
                translation = "（测试译文 ${u.id}）", literalTranslation = null, officialTranslationNote = null, register = null, tone = null,
                ruby = emptyList(),
                chunks = skel.chunks.map { RawChunk(it.surface, "", it.surface, null, "测试", "测试含义", null, emptyList()) },
                grammar = emptyList(),
            )
            PackEntry.fromExplanation(ExplainJsonCodec.toSentenceExplanation(raw, req, ExplanationMeta("x", "m", now = 0)), req)
        }
        val idx = units.withIndex().associate { (i, u) -> u.jp to i }
        val tokens = ex.occurrences.map { OfflineAiPackWriter.TokenRef(it.scene, it.token, idx.getValue(it.jp)) }
        val file = File(dir, OfflineAiPack.FILE_NAME)
        OfflineAiPackWriter.write(file, AiPackMeta("test", "t", "synthetic", 0, ex.occurrences.map { it.scene }.toSet().size, tokens.size, entries.size), entries, tokens)
        val r = AiPackTool.verifyPackFull(TestAssets.root, file)
        println("synthetic full pack: ${file.length() / 1024} KiB, ${entries.size} entries, ${r.lines} lines, ${r.millis} ms")
        assertEquals(ex.occurrences.size, r.lines)
        assertEquals(r.lines, r.hits, "misses: ${r.misses.take(5)}")
        assertEquals(r.lines, r.hashHits)
        assertTrue(r.bad.isEmpty(), r.bad.take(5).toString())
        assertTrue(r.leaks.isEmpty(), "clean-room leaks: ${r.leaks.take(3)}")
    }

    /** When the generated pack exists (`/tmp/wa2-ai-gen`), audit it too. */
    @Test
    fun generatedPackIfPresentIsCleanAndComplete() {
        val f = File(System.getProperty("wa2.aipack") ?: "/tmp/wa2-ai-gen/${OfflineAiPack.FILE_NAME}")
        org.junit.jupiter.api.Assumptions.assumeTrue(f.isFile && TestAssets.available, "no generated pack at $f")
        val r = AiPackTool.verifyPackFull(TestAssets.root, f)
        assertTrue(r.bad.isEmpty(), r.bad.take(5).toString())
        assertTrue(r.leaks.isEmpty(), "clean-room leaks: ${r.leaks.take(3)}")
        println("generated pack: ${r.hits}/${r.lines} lines covered")
    }
}
