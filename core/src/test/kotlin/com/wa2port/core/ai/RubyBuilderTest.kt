package com.wa2port.core.ai

import com.wa2port.core.ai.RubyBuilder.RawRuby
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RubyBuilderTest {
    private fun join(t: List<RubyToken>) = t.joinToString("") { it.base }

    @Test
    fun fastPathKeepsReadingsOnlyOnKanji() {
        val s = "食べて行こう"
        val t = RubyBuilder.buildRuby(s, listOf(RawRuby("食", "タ"), RawRuby("べて", "べて"), RawRuby("行", "い"), RawRuby("こう")))
        assertEquals(s, join(t))
        assertEquals("た", t[0].ruby) // katakana → hiragana
        assertNull(t[1].ruby)
        assertEquals("い", t[2].ruby)
        assertEquals(CharSpan(3, 4), t[2].span)
    }

    @Test
    fun fallbackAlignsKanjiAndFillsGaps() {
        val s = "今年の冬も寒い"
        // Bases do not concatenate to the sentence (model dropped / altered text).
        val t = RubyBuilder.buildRuby(s, listOf(RawRuby("今年", "ことし"), RawRuby("冬", "ふゆ"), RawRuby("寒", "さむ"), RawRuby("XYZ", "x")))
        assertEquals(s, join(t))
        assertEquals("ことし", t.first { it.base == "今年" }.ruby)
        assertEquals("ふゆ", t.first { it.base == "冬" }.ruby)
        assertEquals("さむ", t.first { it.base == "寒" }.ruby)
    }

    @Test
    fun invariantHoldsForGarbage() {
        val s = "「……あ、雪だね」"
        for (raw in listOf(emptyList(), listOf(RawRuby("雪", null)), listOf(RawRuby("ゆき", "ゆき"), RawRuby("雪", "ゆき"), RawRuby("雪", "ゆき")))) {
            assertEquals(s, join(RubyBuilder.buildRuby(s, raw)))
        }
        assertEquals(emptyList<RubyToken>(), RubyBuilder.buildRuby("", listOf(RawRuby("a"))))
    }

    @Test
    fun sliceRubySplitsStraddlingTokens() {
        val s = "今年の冬"
        val ruby = RubyBuilder.buildRuby(s, listOf(RawRuby("今年", "ことし"), RawRuby("の"), RawRuby("冬", "ふゆ")))
        val slice = RubyBuilder.sliceRuby(ruby, CharSpan(1, 4))
        assertEquals("年の冬", join(slice))
        assertNull(slice[0].ruby) // split piece loses its reading
        assertEquals("ふゆ", slice.last().ruby)
        assertEquals(CharSpan(1, 2), slice[0].span)
    }

    @Test
    fun scriptRubyOverridesModel() {
        val s = "本気で言ってる"
        val model = RubyBuilder.buildRuby(s, listOf(RawRuby("本", "ほん"), RawRuby("気", "き"), RawRuby("で"), RawRuby("言", "い"), RawRuby("ってる")))
        val out = RubyBuilder.overlayScriptRuby(s, model, listOf(ScriptRuby(CharSpan(0, 2), "マジ")))
        assertEquals(s, join(out))
        assertEquals("本気", out[0].base)
        assertEquals("マジ", out[0].ruby)
        assertEquals("い", out.first { it.base == "言" }.ruby)
        // Straddling: script span covers half of a model token.
        val model2 = RubyBuilder.buildRuby("大事な人", listOf(RawRuby("大事な人", null)))
        val out2 = RubyBuilder.overlayScriptRuby("大事な人", model2, listOf(ScriptRuby(CharSpan(3, 4), "ひと"), ScriptRuby(CharSpan(3, 9), "bad")))
        assertEquals("大事な人", join(out2))
        assertEquals("ひと", out2.last().ruby)
    }

    @Test
    fun kanaOnlyBaseNeverGetsRuby() {
        val s = "ドラムを叩く"
        val out = RubyBuilder.buildRuby(
            s,
            listOf(RawRuby("ドラム", "どらむ"), RawRuby("を", "を"), RawRuby("叩", "たた"), RawRuby("く", "く")),
        )
        assertEquals(s, join(out))
        assertNull(out.first { it.base == "ドラム" }.ruby)
        assertEquals("たた", out.first { it.base == "叩" }.ruby)
        assertNull(RubyBuilder.cleanReading("どらむ", "ドラム"))
        assertNull(RubyBuilder.cleanReading("どらむ…", "ドラム…"))
        assertNull(RubyBuilder.cleanReading("ちょっと", "ちょっと"))
        assertEquals("まじで", RubyBuilder.cleanReading("マジで", "本気で"))
    }

    @Test
    fun splitsMixedKanjiAndOkuriganaTokens() {
        val w1 = RubyBuilder.computeWordRuby("放り出す", "ほうりだす")
        assertEquals("放り出す", join(w1))
        assertEquals(
            listOf("放" to "ほう", "り" to null, "出" to "だ", "す" to null),
            w1.map { it.base to it.ruby },
        )

        val w2 = RubyBuilder.computeWordRuby("他に", "ほかに")
        assertEquals(listOf("他" to "ほか", "に" to null), w2.map { it.base to it.ruby })

        val w3 = RubyBuilder.computeWordRuby("気かよ？", "きかよ")
        assertEquals(listOf("気" to "き", "かよ？" to null), w3.map { it.base to it.ruby })

        // Fast path with mixed kanji+okurigana RawRuby tokens.
        val s = "他に放り出す"
        val fast = RubyBuilder.buildRuby(s, listOf(RawRuby("他に", "ほかに"), RawRuby("放り出す", "ほうりだす")))
        assertEquals(s, join(fast))
        assertEquals(
            listOf("他" to "ほか", "に" to null, "放" to "ほう", "り" to null, "出" to "だ", "す" to null),
            fast.map { it.base to it.ruby },
        )

        // Fallback path with mixed kanji+okurigana RawRuby tokens.
        val s2 = "「他にいない。放り出す気かよ？」"
        val fallback = RubyBuilder.buildRuby(
            s2,
            listOf(RawRuby("他に", "ほかに"), RawRuby("放り出す", "ほうりだす"), RawRuby("気かよ", "きかよ")),
        )
        assertEquals(s2, join(fallback))
        assertEquals("ほか", fallback.first { it.base == "他" }.ruby)
        assertNull(fallback.firstOrNull { it.base == "他に" })
        assertEquals("ほう", fallback.first { it.base == "放" }.ruby)
        assertEquals("だ", fallback.first { it.base == "出" }.ruby)
        assertEquals("き", fallback.first { it.base == "気" }.ruby)
    }

    @Test
    fun whitespaceDifferenceInSentencePreservesTokenPositions() {
        val s = "人　大事な人"
        val out = RubyBuilder.buildRuby(
            s,
            listOf(RawRuby("人", null), RawRuby("大事な", null), RawRuby("人", "ひと")),
        )
        assertEquals(s, join(out))
        assertNull(out.first { it.span == CharSpan(0, 1) }.ruby)
        assertEquals("ひと", out.first { it.span == CharSpan(5, 6) }.ruby)
    }

    @Test
    fun jukujikunNormalizationAndBackfill() {
        val s = "明日の朝、放り出す"
        val initial = RubyBuilder.buildRuby(
            s,
            listOf(RawRuby("明", "あ"), RawRuby("日", "ひ"), RawRuby("の"), RawRuby("朝", "あさ"), RawRuby("、放り出す", null)),
        )
        val chunks = listOf(
            RubyReadingSpan(CharSpan(0, 3), "あしたの"),
            RubyReadingSpan(CharSpan(3, 4), "あさ"),
            RubyReadingSpan(CharSpan(5, 9), "ほうりだす"),
        )
        val fixed = RubyBuilder.backfillAndNormalizeRuby(s, initial, chunks, emptyList())
        assertEquals(s, join(fixed))
        assertEquals("あした", fixed.first { it.base == "明日" }.ruby)
        assertEquals("あさ", fixed.first { it.base == "朝" }.ruby)
        assertEquals("ほう", fixed.first { it.base == "放" }.ruby)
        assertEquals("だ", fixed.first { it.base == "出" }.ruby)

        // Adjacent kanji from two chunks (`今日` + `行く` -> plainRuns initially groups `今日行`).
        val s2 = "今日行く"
        val plain = RubyBuilder.plainRuns(s2, 0)
        val fixed2 = RubyBuilder.backfillAndNormalizeRuby(
            s2,
            plain,
            listOf(RubyReadingSpan(CharSpan(0, 2), "きょう"), RubyReadingSpan(CharSpan(2, 4), "いく")),
            emptyList(),
        )
        assertEquals(s2, join(fixed2))
        assertEquals(listOf("今日" to "きょう", "行" to "い", "く" to null), fixed2.map { it.base to it.ruby })
    }
}
