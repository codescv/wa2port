package com.wa2port.core

import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.text.DeckLayout
import com.wa2port.core.text.DeckLayout.BothMode
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextScale
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Portrait reading deck geometry ([DeckLayout]): glyph sizes, line caps, heights, corpus fit. */
class DeckLayoutTest {
    private val allScales = (TextScale.MIN..TextScale.MAX).map { it / 100f }

    /** Portrait devices A–D: window width (dp) and deck height (dp) at the spec insets. */
    private data class Device(val name: String, val w: Float, val deckH: Float, val cap100: Float, val cap150: Float)

    private val devices = listOf(
        Device("A 360×780", 360f, 445.5f, 18f, 12f),
        Device("B 412×915", 412f, 547.25f, 21f, 14f),
        Device("C 600×960", 600f, 498.5f, 33f, 22f),
        Device("D 673×841", 673f, 354.44f, 34f, 25f),
    )

    /** Text width of a deck spanning `16 … w − 16`. */
    private fun textW(w: Float) = DeckLayout.textWidth(w - 2f * DeckLayout.DECK_SIDE_DP)

    @Test
    fun emAtHundredPercent() {
        assertEquals(16.02f, DeckLayout.em(MessageLayout.TWO_COL_PX, 1f), 0.01f)
        assertEquals(17.17f, DeckLayout.em(MessageLayout.MAIN_PX, 1f), 0.01f)
        assertEquals(14.31f, DeckLayout.em(MessageLayout.NAME_PX, 1f), 0.01f)
        assertEquals(14.88f, DeckLayout.em(MessageLayout.NOVEL_TWO_COL_PX, 1f), 0.01f)
        assertEquals(12.59f, DeckLayout.pad(), 0.01f)
        assertEquals(302.82f, textW(360f), 0.01f)
        assertEquals(299.39f, DeckLayout.capWidth(textW(360f), DeckLayout.em(MessageLayout.TWO_COL_PX, 1f)), 0.01f)
        // px variant is the dp variant × density.
        val density = 2.625f
        val s = DeckLayout.DECK_SCALE * density
        assertEquals(DeckLayout.em(MessageLayout.TWO_COL_PX, 1.3f) * density, DeckLayout.em(MessageLayout.TWO_COL_PX, 1.3f, s), 1e-3f)
        assertEquals(DeckLayout.pad() * density, DeckLayout.pad(s), 1e-3f)
    }

    @Test
    fun capTableAtHundredAndHundredFiftyPercent() {
        for (d in devices) {
            val tw = textW(d.w)
            assertEquals(d.cap100, DeckLayout.lineCap(tw, DeckLayout.em(MessageLayout.TWO_COL_PX, 1f)), "${d.name} 100%")
            assertEquals(d.cap150, DeckLayout.lineCap(tw, DeckLayout.em(MessageLayout.TWO_COL_PX, 1.5f)), "${d.name} 150%")
        }
    }

    /** P1-1: one hanging closer past `cap·em` still stays inside the deck, for every scale. */
    @Test
    fun hangingCloserNeverCrossesTheDeckEdge() {
        for (w in listOf(360f, 412f, 600f, 673f)) for (t in allScales) {
            val left = DeckLayout.DECK_SIDE_DP
            val right = w - DeckLayout.DECK_SIDE_DP
            val pad = DeckLayout.pad()
            for (px in listOf(MessageLayout.TWO_COL_PX, MessageLayout.MAIN_PX, MessageLayout.NOVEL_TWO_COL_PX)) {
                val em = DeckLayout.em(px, t)
                val cap = DeckLayout.lineCap(textW(w), em)
                assertTrue(left + pad + (cap + 1f) * em <= right + 1e-3f, "w=$w t=$t px=$px cap=$cap em=$em")
                assertTrue(cap >= 8f, "w=$w t=$t px=$px cap=$cap")
                assertTrue(cap <= MessageLayout.SINGLE_MAX_CHARS)
            }
        }
    }

    @Test
    fun bothModeStackedOnPhonesTwoColOnWideDecks() {
        for (d in devices) assertEquals(BothMode.STACKED, DeckLayout.bothMode(textW(d.w), 1f), d.name)
        assertEquals(BothMode.TWO_COL, DeckLayout.bothMode(textW(800f), 1f))
        // TWO_COL needs textW ≥ 38·em + gap ≈ 625.9 dp at 100 %.
        assertEquals(BothMode.STACKED, DeckLayout.bothMode(625f, 1f))
        assertEquals(BothMode.TWO_COL, DeckLayout.bothMode(627f, 1f))
    }

    @Test
    fun deckHeightsAndPromptHeights() {
        assertEquals(225.3f, DeckLayout.minDeckDp(1f, stacked = true), 0.5f)
        assertEquals(303.3f, DeckLayout.minDeckDp(1.5f, stacked = true), 0.5f)
        assertEquals(138f, DeckLayout.promptDeckDp(1f, stacked = true), 1f)
        assertEquals(186f, DeckLayout.promptDeckDp(1.5f, stacked = true), 1f)
        for (stacked in listOf(true, false)) {
            var prevMin = 0f
            var prevPrompt = 0f
            for (t in allScales) {
                val m = DeckLayout.minDeckDp(t, stacked)
                val p = DeckLayout.promptDeckDp(t, stacked)
                assertTrue(m > prevMin && p > prevPrompt, "monotone stacked=$stacked t=$t")
                prevMin = m
                prevPrompt = p
            }
        }
        // Single language needs less than stacked bilingual at every scale.
        for (t in allScales) assertTrue(DeckLayout.minDeckDp(t, false) < DeckLayout.minDeckDp(t, true))
    }

    @Test
    fun devicesHoldTheMinimumDeckAndTheChoiceSplit() {
        for (d in devices) {
            assertTrue(DeckLayout.minDeckDp(1.5f, stacked = true) - DeckLayout.DECK_GAP_DP - DeckLayout.DECK_BOTTOM_DP <= d.deckH, d.name)
            for (t in allScales) {
                val prompt = DeckLayout.promptHeight(d.deckH, t, stacked = true)
                val choiceH = d.deckH - prompt - DeckLayout.CHOICE_GAP_DP
                assertTrue(choiceH >= 186f, "${d.name} t=$t choiceRect.h=$choiceH")
                assertTrue(prompt <= DeckLayout.PROMPT_MAX_FRAC * d.deckH + 1e-3f)
            }
        }
        // D (unfolded foldable) has the tightest spare: ≥ 51 dp at 150 %.
        assertTrue(354.44f - DeckLayout.minDeckDp(1.5f, stacked = true) >= 51f)
    }

    @Test
    fun novelPortraitCapacity() {
        assertTrue(DeckLayout.novelParagraphCapacity(445.5f, 1f) >= 6, "A @100%")
        assertTrue(DeckLayout.novelCapacityLines(547.25f, 1f) >= 23, "B @100%")
    }

    /** Full-width units of a paragraph (half-width codes count ½). */
    private fun units(r: RichText): Float = r.baseCodes().sumOf { (if (it < 0x100) 0.5 else 1.0) }.toFloat()

    /** Lines of [r] at [cap] under `JOIN_ALL` (script `\n` soft-joined, `\k` page breaks kept). */
    private fun joinedLines(r: RichText, cap: Float): Int {
        var lines = 0
        var cur = 0f
        fun flush() {
            if (cur > 0f) lines += ceil(cur / cap).toInt()
            cur = 0f
        }
        for (item in r.items) {
            when (item) {
                is TextItem.PageBreak -> flush()
                else -> {}
            }
            cur += units(RichText(listOf(item)))
        }
        flush()
        return lines
    }

    /**
     * Real-corpus check (all 187 scenes incl. IC, every bilingual token): a stacked CN + JP line
     * under `JOIN_ALL` fits the portrait deck without in-deck scrolling for ≥ 99 % of tokens at both
     * 100 % and 150 % on every device A–D, and portrait never scrolls more often than
     * the landscape 4-line × 19-char 双语 window at the same text scale.
     */
    @Test
    fun realCorpusFitsTheStackedDeck() {
        val store = ScenarioStore(TestAssets.require())
        val pairs = ArrayList<Pair<RichText, RichText>>()
        for (n in store.sceneNames) {
            val sc = store.scene(n) ?: continue
            val cn = sc.cn ?: continue
            for (k in 0 until sc.jp.size) {
                val jp = sc.jp.rich(k)
                if (jp.baseCodes().isEmpty()) continue
                pairs += cn.rich(k) to jp
            }
        }
        println("DeckLayoutTest corpus: ${pairs.size} bilingual tokens")
        assertTrue(pairs.size > 60_000, "expected the full corpus, got ${pairs.size} tokens")
        for (t in listOf(1f, 1.5f)) {
            // Landscape 双语 window: each column independently holds `availLines` lines.
            val twoEm = MessageLayout.TWO_COL_PX * t
            val landCap = MessageLayout.lineCap(MessageLayout.advColumnWidth(t), twoEm, MessageLayout.TWO_COL_MAX_CHARS)
            val landLines = (MessageLayout.advTextAvail(MessageLayout.advTop(t, twoCol = true)) / (MessageLayout.LINE_PITCH * twoEm)).toInt()
            val landFit = pairs.count { (cn, jp) -> maxOf(joinedLines(cn, landCap), joinedLines(jp, landCap)) <= landLines }.toDouble() / pairs.size
            println("  landscape @${(t * 100).toInt()}%: cap=$landCap lines=$landLines fit=${"%.4f".format(landFit)}")
            for (d in devices) {
                val em = DeckLayout.em(MessageLayout.TWO_COL_PX, t)
                val cap = DeckLayout.lineCap(textW(d.w), em)
                val avail = DeckLayout.textAvail(d.deckH)
                var fits = 0
                var worst = 0
                for ((cn, jp) in pairs) {
                    val lines = joinedLines(cn, cap) + joinedLines(jp, cap)
                    worst = maxOf(worst, lines)
                    val h = lines * MessageLayout.LINE_PITCH * em + DeckLayout.STACKED_GAP_DP
                    if (h <= avail) fits++
                }
                val ratio = fits.toDouble() / pairs.size
                println("  ${d.name} @${(t * 100).toInt()}%: cap=$cap avail=${"%.1f".format(avail)} fit=${"%.4f".format(ratio)} worstLines=$worst")
                assertTrue(ratio >= 0.99, "${d.name} t=$t fit ratio $ratio")
                assertTrue(ratio >= landFit, "${d.name} t=$t portrait fit $ratio < landscape $landFit")
            }
        }
    }
}
