package com.wa2port.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiSampleExplanationTest {
    @Test
    fun everySpanSlicesToItsSurface() {
        val e = AiSampleExplanation.build()
        assertEquals(AiSampleExplanation.SENTENCE, e.ruby.joinToString("") { it.base })
        e.ruby.forEach { assertEquals(it.base, e.sentence.substring(it.span.start, it.span.end)) }
        assertEquals(5, e.chunks.size)
        e.chunks.forEach { c ->
            assertEquals(SpanAlignment.EXACT, c.alignment, c.surface)
            assertEquals(c.surface, e.sentence.substring(c.span.start, c.span.end))
            assertEquals(c.surface, c.ruby.joinToString("") { it.base })
        }
        e.vocab.forEach { v ->
            assertEquals(SpanAlignment.EXACT, v.alignment, v.surface)
            assertEquals(v.surface, e.sentence.substring(v.span.start, v.span.end))
        }
        assertEquals(1f, SpanAlign.coverage(e.sentence, e.chunks.map { it.span }))
    }

    @Test
    fun contentHighlights() {
        val e = AiSampleExplanation.build()
        val yuki = e.chunks.first { it.surface == "雪だね" }
        assertEquals("雪ですね", yuki.textbookForm)
        assertNotNull(yuki.spokenEvolution)
        assertEquals(3, yuki.spokenEvolution!!.formalityLevel) // 0.4 → 1 + round(1.6) = 3
        assertEquals(listOf("雪", "だ", "ね"), SpanAlign.findVocabForChunk(yuki, e.vocab).map { it.surface })
        assertEquals("ゆき", e.ruby.first { it.base == "雪" }.ruby)
        assertEquals(RegisterTag.CASUAL, e.register)
        assertEquals(AiSampleExplanation.OFFICIAL_CN, e.officialCnTranslation)
        assertTrue(e.officialTranslationNote!!.contains("きっと"))
        assertEquals(listOf("c4"), e.grammar[0].chunkIds)
        assertEquals(listOf("c1", "c4"), e.grammar[1].chunkIds)
        // Tap on 寒 → 寒くなるね chunk.
        assertEquals("寒くなるね", SpanAlign.findChunkAtChar(e.chunks, e.sentence.indexOf('寒'))?.surface)
        // Tap on the opening bracket → first chunk to the right (あ).
        assertEquals("あ", SpanAlign.findChunkAtChar(e.chunks, 0)?.surface)
        assertEquals("寒い", e.vocab.first { it.surface == "寒く" }.lemma)
        assertEquals("寒い", e.vocab.first { it.surface == "寒く" }.lemmaRuby.joinToString("") { it.base })
    }
}
