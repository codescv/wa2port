package com.wa2port.android.game

import com.wa2port.core.ai.CharSpan
import com.wa2port.core.ai.RubyToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [RubyFlow] — v1.4.10 fix for "解释页面如果词太长 原文和假名会重叠": chunk 3 of
 * `「ええと…次は２－Ｃの柳原だな」` crushed `２－Ｃの柳原だな` to `２－Ｃの木|` (furigana `や…`) because
 * surface, kana reading and role tag were forced into one non-wrapping row.
 */
class RubyFlowTest {
    /** Tokens with consecutive spans starting at [start]. */
    private fun tokens(start: Int, vararg parts: Pair<String, String?>): List<RubyToken> {
        var i = start
        return parts.map { (base, ruby) -> RubyToken(base, ruby, CharSpan(i, i + base.length)).also { i += base.length } }
    }

    private val chunk3 = tokens(5, "２－Ｃの" to null, "柳原" to "やなぎはら", "だな" to null)

    @Test
    fun rubyTokensStayAtomicPlainRunsSplitPerCharacter() {
        val cells = RubyFlow.cells(chunk3, "２－Ｃの柳原だな")
        assertEquals(listOf("２", "－", "Ｃ", "の", "柳原", "だ", "な"), cells.map { it.base })
        assertEquals("やなぎはら", cells[4].ruby)
        assertEquals(1, cells.count { it.hasRuby })
        // Concatenation invariant: nothing dropped or duplicated.
        assertEquals("２－Ｃの柳原だな", cells.joinToString("") { it.base })
    }

    @Test
    fun wideFuriganaWidensItsOwnCell() {
        val yanagihara = RubyFlow.Cell("柳原", "やなぎはら")
        // 5 kana × 0.5 em (9 sp over 18 sp) > 2 kanji: the cell is the ruby width (+ padding).
        assertEquals(2.5f + 2 * RubyFlow.RUBY_CELL_PAD_EM, RubyFlow.cellEms(yanagihara, 0.5f), 1e-4f)
        assertEquals(2.5f + 2 * RubyFlow.RUBY_CELL_PAD_EM, RubyFlow.cellEms(RubyFlow.Cell("承", "うけたまわ"), 0.5f), 1e-4f)
        assertEquals(2.5f + 2 * RubyFlow.RUBY_CELL_PAD_EM, RubyFlow.cellEms(RubyFlow.Cell("志", "こころざし"), 0.5f), 1e-4f)
        // Narrow ruby: the cell is the base width.
        assertEquals(1f + 2 * RubyFlow.RUBY_CELL_PAD_EM, RubyFlow.cellEms(RubyFlow.Cell("次", "つぎ"), 0.5f), 1e-4f)
        assertEquals(1f, RubyFlow.cellEms(RubyFlow.Cell("な"), 0.5f), 1e-4f)
    }

    @Test
    fun closingMarksNeverStartALine() {
        val cells = RubyFlow.cells(tokens(0, "だな」" to null), "")
        assertEquals(listOf("だ", "な」"), cells.map { it.base })
        val sentence = RubyFlow.cells(emptyList(), "ええと…次は。")
        assertEquals(listOf("え", "え", "と…", "次", "は。"), sentence.map { it.base })
    }

    @Test
    fun fallbackSplitsWhenThereAreNoTokens() {
        val cells = RubyFlow.cells(emptyList(), "２－Ｃの柳原だな")
        assertEquals(8, cells.size)
        assertFalse(cells.any { it.hasRuby })
    }

    @Test
    fun ultraLongRubyCellDegradesInsteadOfOverflowing() {
        val long = tokens(0, "東京特許許可局長" to "とうきょうとっきょきょかきょくちょう", "だ" to null)
        // A 360 dp card holds ~12 ems of 18 sp: the 9.5 + 0.2 em ruby cell fits, stays atomic.
        val wide = RubyFlow.cells(long, "", maxEms = 12f)
        assertEquals(1, wide.count { it.hasRuby })
        // A 6-em line cannot hold it: per-character base cells + dimmed （reading） echo cells.
        val narrow = RubyFlow.cells(long, "", maxEms = 6f)
        assertFalse(narrow.any { it.hasRuby })
        assertEquals("東京特許許可局長", narrow.filter { !it.echo }.dropLast(1).joinToString("") { it.base })
        assertEquals("（とうきょうとっきょきょかきょくちょう）", narrow.filter { it.echo }.joinToString("") { it.base })
        // Every cell now fits the line.
        for (c in narrow) assertTrue(RubyFlow.cellEms(c, 0.5f) <= 6f, c.base)
        // The closer glues onto the echo, never starting a line on its own.
        assertTrue(narrow.none { it.base == "）" })
    }

    @Test
    fun everyCellFitsTheLineForAnyWidth() {
        val samples = listOf(
            chunk3,
            tokens(0, "承" to "うけたまわ", "りました" to null),
            tokens(0, "志" to "こころざし", "を" to null, "持" to "も", "って" to null),
            tokens(0, "東京特許許可局長" to "とうきょうとっきょきょかきょくちょう"),
        )
        for (toks in samples) for (maxEms in listOf(2f, 3f, 5f, 8f, 12f, 20f)) {
            for (c in RubyFlow.cells(toks, "", maxEms = maxEms)) {
                val ems = RubyFlow.cellEms(c, 0.5f)
                assertTrue(ems <= maxEms || (!c.hasRuby && RubyFlow.emUnits(c.base) <= 2f), "${c.base} $ems > $maxEms")
            }
        }
    }

    @Test
    fun readingFlowsBelowWhenItDoesNotFitBeside() {
        // 360 dp portrait card: 360 − 2·16 (sheet) − 2·12 (card) − (24 + 8) badge − (8 + ~34) 谓语 ≈ 222 dp.
        val avail = 222f
        // Chunk 3 (the screenshot): surface ≈ (6 full-width + 2.7-em ruby cell)·18 ≈ 157 dp; reading
        // にのしーのやなぎはらだな = 12 kana × 12 sp = 144 dp → no room: reading goes below.
        val surface3 = RubyFlow.lineEms(RubyFlow.cells(chunk3, ""), 0.5f) * 18f
        assertFalse(RubyFlow.readingBeside(surface3, 12 * 12f, avail, 8f))
        // ② 次は つぎは: 2.2 ems + 3 kana → stays crisp side by side.
        val surface2 = RubyFlow.lineEms(RubyFlow.cells(tokens(0, "次" to "つぎ", "は" to null), ""), 0.5f) * 18f
        assertTrue(RubyFlow.readingBeside(surface2, 3 * 12f, avail, 8f))
        // Landscape split pane (right pane ≈ 0.58 × (915 − 2·16 − 12) ≈ 505 dp → ≈ 390 dp free): beside.
        assertTrue(RubyFlow.readingBeside(surface3, 12 * 12f, 390f, 8f))
    }

    @Test
    fun emUnitsCountsHalfWidthAsHalf() {
        assertEquals(1f, RubyFlow.emUnits("ab"), 1e-4f)
        assertEquals(1f, RubyFlow.emUnits("ｱｲ"), 1e-4f)
        assertEquals(4f, RubyFlow.emUnits("２－Ｃの"), 1e-4f)
        assertEquals(0f, RubyFlow.emUnits(""), 1e-4f)
    }

    @Test
    fun sentenceGroupsKeepRubyTogetherAndGlueClosers() {
        val text = "「次は柳原だな」"
        val toks = tokens(0, "「" to null, "次" to "つぎ", "は" to null, "柳原" to "やなぎはら", "だな」" to null)
        val groups = RubyFlow.sentenceGroups(toks, text)
        assertEquals(
            listOf(
                RubyFlow.SentenceGroup(null, listOf(0)), RubyFlow.SentenceGroup("つぎ", listOf(1)), RubyFlow.SentenceGroup(null, listOf(2)),
                RubyFlow.SentenceGroup("やなぎはら", listOf(3, 4)), RubyFlow.SentenceGroup(null, listOf(5)), RubyFlow.SentenceGroup(null, listOf(6, 7)),
            ),
            groups,
        )
    }

    /** Header width in 24 sp ems: 356 dp (PKX110 412 dp portrait), 304 dp (360 dp), 230 dp (640 dp landscape pane). */
    private val headerEms = listOf(356f / 24f, 304f / 24f, 230f / 24f)

    @Test
    fun realWa2GikunTokensNeverClipTheSentenceHeader() {
        // Real WA2 script / pack ruby tokens (tester corpus audit of 58,913 entries).
        val real = listOf(
            "プレゼントのお返しができる日" to "ばれんたいんでー",
            "峰城大学付属学園在学中" to "ほうじょうだいがくふぞくがくえんざいがくちゅう",
            "何月何日何時何分何秒" to "なんがつなんにちなんじなんぷんなんびょう",
            "最重要" to "さいじゅうようでいちばんたいせつなことはなにかというとそれはほかでもないきみのことだよ",
        )
        for ((base, ruby) in real) for (maxEms in headerEms) {
            val text = "「$base」"
            val toks = tokens(0, "「" to null, base to ruby, "」" to null)
            val groups = RubyFlow.sentenceGroups(toks, text, maxEms)
            // Every base character is still exactly one tappable index, in order (selectChar / chunk highlight intact).
            assertEquals(text.indices.toList(), groups.flatMap { it.starts }, "$base @ $maxEms")
            for (g in groups) {
                if (g.echo != null) {
                    assertTrue(g.starts.isEmpty())
                    assertEquals("（$ruby）", g.echo)
                    continue
                }
                val baseEms = g.starts.sumOf { (RubyFlow.emUnits(text.substring(it, it + 1)) + 2f / 24f).toDouble() }.toFloat()
                val ems = if (g.ruby != null) maxOf(baseEms, RubyFlow.emUnits(g.ruby!!) * 9f / 24f) + 2 * RubyFlow.RUBY_CELL_PAD_EM else baseEms
                assertTrue(ems <= maxEms, "group ${g.starts} ($ems em) fits $maxEms em for $base")
            }
        }
        // ばれんたいんでー over プレゼントのお返しができる日 = 14 + 14/12 em: atomic only on wide lines.
        val wide = RubyFlow.sentenceGroups(tokens(0, "プレゼントのお返しができる日" to "ばれんたいんでー"), "プレゼントのお返しができる日", maxEms = 20f)
        assertEquals(1, wide.size)
        val narrow = RubyFlow.sentenceGroups(tokens(0, "プレゼントのお返しができる日" to "ばれんたいんでー"), "プレゼントのお返しができる日", maxEms = 304f / 24f)
        assertEquals(15, narrow.size)
        assertEquals("（ばれんたいんでー）", narrow.last().echo)
        // Short words keep their furigana group at every width.
        val short = RubyFlow.sentenceGroups(tokens(0, "柳原" to "やなぎはら"), "柳原", maxEms = 230f / 24f)
        assertEquals(listOf(RubyFlow.SentenceGroup("やなぎはら", listOf(0, 1))), short)
    }
}
