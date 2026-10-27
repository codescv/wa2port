package com.wa2port.core.ai

import com.wa2port.core.ai.SpanAlignment.EXACT
import com.wa2port.core.ai.SpanAlignment.FUZZY
import com.wa2port.core.ai.SpanAlignment.UNALIGNED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpanAlignTest {
    private fun align(sentence: String, vararg surfaces: String) = SpanAlign.alignSpans(sentence, surfaces.toList())

    @Test
    fun exactAlignment() {
        val r = align("今日は晴れです", "今日", "は", "晴れ", "です")
        assertEquals(listOf(CharSpan(0, 2), CharSpan(2, 3), CharSpan(3, 5), CharSpan(5, 7)), r.spans)
        assertEquals(List(4) { EXACT }, r.alignment)
    }

    @Test
    fun duplicateParticlesBindLeftToRight() {
        val r = align("私の猫の名前", "私", "の", "猫", "の", "名前")
        assertEquals(CharSpan(1, 2), r.spans[1])
        assertEquals(CharSpan(3, 4), r.spans[3])
        assertEquals(CharSpan(4, 6), r.spans[4])
        val t = align("見たけど、見た", "見た", "けど", "見た")
        assertEquals(listOf(CharSpan(0, 2), CharSpan(2, 4), CharSpan(5, 7)), t.spans)
    }

    @Test
    fun kanaNormalisation() {
        val r = align("ハルキくん", "はるき", "くん")
        assertEquals(CharSpan(0, 3), r.spans[0])
        assertEquals(FUZZY, r.alignment[0])
        assertEquals(EXACT, r.alignment[1])
    }

    @Test
    fun widthAndWaveNormalisation() {
        assertEquals(CharSpan(0, 3), align("ｱｲｳだ", "アイウ").spans[0])
        assertEquals(FUZZY, align("ｱｲｳだ", "アイウ").alignment[0])
        assertEquals(CharSpan(1, 3), align("第１２話", "12").spans[0])
        assertEquals(CharSpan(1, 2), align("あ～あ", "〜").spans[0])
        // Ideographic space inside the sentence but not in the surface.
        val sp = align("春希　くん", "春希くん")
        assertEquals(CharSpan(0, 5), sp.spans[0])
        assertEquals(FUZZY, sp.alignment[0])
    }

    @Test
    fun halfWidthDakutenComposes() {
        val r = align("ｶﾞｯｺｳへ", "がっこう", "へ")
        assertEquals(CharSpan(0, 5), r.spans[0])
        assertEquals(FUZZY, r.alignment[0])
        assertEquals(CharSpan(5, 6), r.spans[1])
        assertEquals("がっこう", JapaneseText.normalise("ｶﾞｯｺｳ"))
        // Never composes across dropped whitespace.
        val n = JapaneseText.normaliseWithMap("ｶ ﾞ")
        assertEquals("か\u3099", n.text)
        assertEquals(listOf(0, 2, 3), n.map.toList())
        assertEquals("ば", JapaneseText.normalise("ﾊﾞ"))
    }

    @Test
    fun ellipsisVariants() {
        assertEquals(CharSpan(0, 1), align("……あ", "…").spans[0])
        // NFKC turns … into "..." (3 units) that all map back to one source char.
        val r = align("……あ", "...", "...", "あ")
        assertEquals(listOf(CharSpan(0, 1), CharSpan(1, 2), CharSpan(2, 3)), r.spans)
        assertEquals(listOf(FUZZY, FUZZY, EXACT), r.alignment)
    }

    @Test
    fun earlierFuzzyBeatsLaterExact() {
        val r = align("はるき、ハルキ", "ハルキ", "、", "ハルキ")
        assertEquals(CharSpan(0, 3), r.spans[0])
        assertEquals(FUZZY, r.alignment[0])
        assertEquals(CharSpan(3, 4), r.spans[1])
        assertEquals(CharSpan(4, 7), r.spans[2])
        assertEquals(EXACT, r.alignment[2])
    }

    @Test
    fun unalignedFallbackKeepsCursor() {
        val r = align("雪が降る", "雪", "雨", "が", "", "降る")
        assertEquals(listOf(EXACT, UNALIGNED, EXACT, UNALIGNED, EXACT), r.alignment)
        assertEquals(CharSpan(1, 1), r.spans[1])
        assertEquals(CharSpan(1, 2), r.spans[2])
        assertEquals(CharSpan(2, 2), r.spans[3])
        assertEquals(CharSpan(2, 4), r.spans[4])
    }

    @Test
    fun clampAndCoverage() {
        assertEquals(
            listOf(CharSpan(0, 2), CharSpan(2, 3), CharSpan(3, 4)),
            SpanAlign.clampSpans(listOf(CharSpan(-1, 2), CharSpan(1, 3), CharSpan(3, 9)), 4),
        )
        assertEquals(1f, SpanAlign.coverage("「」。", emptyList()))
        // 雪 だ ね are content chars (3); 雪 and ね covered → 2/3.
        assertEquals(2f / 3f, SpanAlign.coverage("雪だ。ね", listOf(CharSpan(0, 1), CharSpan(3, 4))), 1e-6f)
    }

    private fun chunk(id: String, s: Int, e: Int, vocabIds: List<String> = emptyList()) =
        SemanticChunk(id, CharSpan(s, e), if (e > s) EXACT else UNALIGNED, "x", "x", emptyList(), "x", null, "r", "m", null, vocabIds)

    private fun vocab(id: String, s: Int, e: Int) = VocabEntry(id, CharSpan(s, e), EXACT, "x", "x", "x", emptyList(), "noun", "m")

    @Test
    fun findChunkAtChar() {
        // 「 あ 、 雪だね 。 」  → chunks c0=[1,2) c1=[3,6)
        val chunks = listOf(chunk("c0", 1, 2), chunk("c1", 3, 6), chunk("cu", 6, 6))
        assertEquals("c0", SpanAlign.findChunkAtChar(chunks, 1)?.id)
        assertEquals("c1", SpanAlign.findChunkAtChar(chunks, 4)?.id)
        // Gap (punctuation) → right neighbour.
        assertEquals("c0", SpanAlign.findChunkAtChar(chunks, 0)?.id)
        assertEquals("c1", SpanAlign.findChunkAtChar(chunks, 2)?.id)
        // Trailing gap → last chunk to the left.
        assertEquals("c1", SpanAlign.findChunkAtChar(chunks, 7)?.id)
        assertNull(SpanAlign.findChunkAtChar(listOf(chunk("u", 2, 2)), 2))
        assertTrue(SpanAlign.isGapCharAt("あ、い", 1))
    }

    @Test
    fun findVocabForChunk() {
        val vocab = listOf(vocab("v0", 3, 4), vocab("v1", 4, 5), vocab("v2", 7, 8))
        assertEquals(listOf("v1", "v0"), SpanAlign.findVocabForChunk(chunk("c", 3, 6, listOf("v1", "v0", "missing")), vocab).map { it.id })
        // Fallback: vocab inside the chunk span when vocabIds is empty.
        assertEquals(listOf("v0", "v1"), SpanAlign.findVocabForChunk(chunk("c", 3, 6), vocab).map { it.id })
        assertEquals(emptyList<VocabEntry>(), SpanAlign.findVocabForChunk(chunk("c", 3, 3), vocab))
        assertEquals("v1", SpanAlign.findVocabAtChar(vocab, 4)?.id)
        assertNull(SpanAlign.findVocabAtChar(vocab, 6))
        val g = listOf(GrammarPoint("g0", "p", listOf("c"), "e"), GrammarPoint("g1", "q", listOf("d"), "e"))
        assertEquals(listOf("g0"), SpanAlign.grammarForChunk(chunk("c", 0, 1), g).map { it.id })
    }
}
