package com.wa2port.core

import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.MessageLayout.ADV_MIN_LINES
import com.wa2port.core.text.MessageLayout.ADV_TWO_COL_LINES
import com.wa2port.core.text.MessageLayout.LINE_PITCH
import com.wa2port.core.text.MessageLayout.MAIN_PX
import com.wa2port.core.text.MessageLayout.SINGLE_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_PX
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.TextScale
import kotlin.math.ceil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Font size setting: constant glyph sizing, line caps, ADV window capacity, NOVEL caps and presets. */
class FontScaleTest {
    private val presets = listOf(85, 100, 115, 130, 145)

    /** Every slider value 70..150 as a factor. */
    private val allScales = (TextScale.MIN..TextScale.MAX).map { it / 100f }

    @Test
    fun hundredPercentIsUnchanged() {
        assertEquals(MessageLayout.ADV_TOP, MessageLayout.advTop(1f, twoCol = false))
        assertEquals(0.68375f, MessageLayout.advTop(1f, twoCol = true), 1e-5f)
        assertEquals(0.56f, MessageLayout.ADV_TOP_MIN)
        assertEquals(3, ADV_MIN_LINES)
        assertEquals(4, ADV_TWO_COL_LINES)
        assertEquals(1.5f, LINE_PITCH)
        assertEquals(19f, TWO_COL_MAX_CHARS)
        assertEquals(MessageLayout.ADV_COL_GAP, MessageLayout.advColumnGap(1f))
        assertEquals(539f, MessageLayout.advColumnWidth(1f))
        assertEquals(1108f, MessageLayout.advSingleWidth())
        assertEquals(19f, MessageLayout.lineCap(MessageLayout.advColumnWidth(1f), TWO_COL_PX, TWO_COL_MAX_CHARS))
        assertEquals(34f, MessageLayout.lineCap(MessageLayout.advSingleWidth(), MAIN_PX, SINGLE_MAX_CHARS))
        assertEquals(19f, MessageLayout.novelLineCap(MessageLayout.NOVEL_WIDTH * MessageLayout.STAGE_W, 1f))
    }

    @Test
    fun dynamicFontShrinkingIsRemovedAndReflowEnumExists() {
        val methods = MessageLayout::class.java.declaredMethods.map { it.name }.toSet()
        val fields = MessageLayout::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("fitSteps" in methods, "fitSteps must be removed so glyph size is strictly constant")
        assertFalse("FIT_STEPS" in fields, "FIT_STEPS must be removed")
        assertFalse("FIT_EXTRA_RATIO" in fields, "FIT_EXTRA_RATIO must be removed")
        assertEquals(listOf(Reflow.KEEP_ALL, Reflow.SENTENCE, Reflow.JOIN_ALL), Reflow.entries)
    }

    @Test
    fun twoColumnCapsAtPresets() {
        val expected = mapOf(85 to 19f, 100 to 19f, 115 to 16f, 130 to 14f, 145 to 13f, 150 to 12f)
        for ((p, cap) in expected) {
            val t = p / 100f
            assertEquals(cap, MessageLayout.lineCap(MessageLayout.advColumnWidth(t), TWO_COL_PX * t, TWO_COL_MAX_CHARS), "two-col $p%")
        }
    }

    @Test
    fun singleLanguageCapsAtPresets() {
        val expected = mapOf(85 to 34f, 100 to 34f, 115 to 32f, 130 to 28f, 145 to 25f, 150 to 24f)
        for ((p, cap) in expected) {
            val t = p / 100f
            assertEquals(cap, MessageLayout.lineCap(MessageLayout.advSingleWidth(), MAIN_PX * t, SINGLE_MAX_CHARS), "single $p%")
        }
    }

    /** cap × size ≤ width for every slider value: constant glyph size never pixel-wraps before cap. */
    @Test
    fun capNeverExceedsColumnWidth() {
        for (t in allScales) {
            val two = TWO_COL_PX * t
            val colW = MessageLayout.advColumnWidth(t)
            assertTrue(MessageLayout.lineCap(colW, two, TWO_COL_MAX_CHARS) * two <= colW, "two-col t=$t")
            val w = MessageLayout.advSingleWidth()
            val one = MAIN_PX * t
            assertTrue(MessageLayout.lineCap(w, one, SINGLE_MAX_CHARS) * one <= w, "single t=$t")
        }
    }

    /** 3 lines of single-language text and 4 lines of two-column text fit at every scale 70..150 %. */
    @Test
    fun windowHoldsThreeLinesSingleAndFourLinesTwoColAtEveryScale() {
        for (t in allScales) {
            val availSingle = MessageLayout.advTextAvail(MessageLayout.advTop(t, twoCol = false))
            assertTrue(availSingle >= ADV_MIN_LINES * LINE_PITCH * MAIN_PX * t, "single t=$t avail=$availSingle")
            val availTwoCol = MessageLayout.advTextAvail(MessageLayout.advTop(t, twoCol = true))
            assertTrue(availTwoCol >= ADV_TWO_COL_LINES * LINE_PITCH * TWO_COL_PX * t, "two-col t=$t avail=$availTwoCol")
        }
    }

    /**
     * Same check in float32 with `AdvWindow`'s exact operation order on 12 real device stage
     * heights, verifying 3 lines of `MAIN_PX` in single mode and 4 lines of `TWO_COL_PX` in
     * `twoCol = true` mode fit at every slider value `70..150` without tolerance.
     */
    @Test
    fun windowHoldsLinesOnDeviceHeights() {
        val heights = listOf(540f, 607.5f, 720f, 810f, 900f, 1012.5f, 1080f, 1170f, 1206f, 1350f, 1440f, 1600f)
        for (h in heights) for (p in TextScale.MIN..TextScale.MAX) {
            val t = p / 100f
            val scale = h / 720f * t
            val stageScale = h / 720f
            val bottom = h * MessageLayout.ADV_BOTTOM
            val pad = MessageLayout.ADV_PAD * stageScale

            val topSingle = h * MessageLayout.advTop(t, twoCol = false)
            val availSingle = bottom - pad * 0.6f - (topSingle + pad * 1.25f)
            val needSingle = ADV_MIN_LINES * ((MAIN_PX * scale) * LINE_PITCH)
            assertTrue(needSingle <= availSingle, "single h=$h p=$p need=$needSingle avail=$availSingle")

            val topTwoCol = h * MessageLayout.advTop(t, twoCol = true)
            val availTwoCol = bottom - pad * 0.6f - (topTwoCol + pad * 1.25f)
            val needTwoCol = ADV_TWO_COL_LINES * ((TWO_COL_PX * scale) * LINE_PITCH)
            assertTrue(needTwoCol <= availTwoCol, "two-col h=$h p=$p need=$needTwoCol avail=$availTwoCol")
        }
    }

    @Test
    fun windowGrowsOnlyUpwardsAndWithinBounds() {
        for (twoCol in listOf(false, true)) {
            var prev = 1f
            for (t in allScales) {
                val top = MessageLayout.advTop(t, twoCol)
                assertTrue(top <= prev, "monotone twoCol=$twoCol at t=$t")
                assertTrue(top in MessageLayout.ADV_TOP_MIN..MessageLayout.ADV_TOP, "bounds twoCol=$twoCol at t=$t: $top")
                if (!twoCol && t <= 1f) assertEquals(MessageLayout.ADV_TOP, top, "single <=100% keeps classic window (t=$t)")
                if (twoCol && t <= 0.85f) assertEquals(MessageLayout.ADV_TOP, top, "twoCol <=85% keeps classic window (t=$t)")
                prev = top
            }
        }
    }

    @Test
    fun novelCapsUseTheWholeColumn() {
        val width = MessageLayout.NOVEL_WIDTH * MessageLayout.STAGE_W
        assertTrue(MessageLayout.novelLineCap(width, 0.7f) >= 26f, "70% no longer clipped at 22")
        assertEquals(23f, MessageLayout.novelLineCap(width, 0.85f))
        assertEquals(13f, MessageLayout.novelLineCap(width, 1.5f))
        for (t in allScales) {
            val cap = MessageLayout.novelLineCap(width, t)
            assertTrue(cap >= MessageLayout.NOVEL_MIN_CHARS)
            assertTrue(cap * MessageLayout.NOVEL_TWO_COL_PX * t <= MessageLayout.novelColumnWidth(width, t), "novel t=$t")
        }
    }

    /** The pre-fix balancer: `min(cap, ceil(n / ceil(n / cap)) + 1)`. */
    private fun oldBalancedCap(n: Float, cap: Float): Float =
        if (n <= cap) cap else minOf(cap, ceil(n / ceil(n / cap)) + 1f)

    /**
     * Mirrors `GlyphAtlasText.layout` for one paragraph of [n] full-width characters (no hanging
     * punctuation): balanced cap from [balance], then a line also breaks when it would exceed
     * [fit] characters of pixel width.
     */
    private fun wrap(n: Int, cap: Float, fit: Int, balance: (Float, Float) -> Float = MessageLayout::balancedCap): List<Int> {
        val seg = balance(n.toFloat(), cap)
        val lines = ArrayList<Int>()
        var cur = 0
        repeat(n) {
            if (cur > 0 && (cur + 1 > seg + 0.01f || cur + 1 > fit)) { lines += cur; cur = 0 }
            cur++
        }
        if (cur > 0) lines += cur
        return lines
    }

    /** Paragraphs of up to 5 lines at 100 % … 150 %: no line shorter than 4 chars, never the old double wrap. */
    @Test
    fun noOrphanLinesAtLargeSizes() {
        for (p in listOf(100, 115, 130, 145, 150)) {
            val t = p / 100f
            val colW = MessageLayout.advColumnWidth(t)
            val size = TWO_COL_PX * t
            val fit = (colW / size).toInt()
            val cap = MessageLayout.lineCap(colW, size, TWO_COL_MAX_CHARS)
            for (n in 4..(cap.toInt() * 5)) {
                val lines = wrap(n, cap, fit)
                assertEquals(n, lines.sum())
                assertTrue(lines.all { it >= 4 }, "$p% n=$n: $lines")
                assertTrue(lines.size <= ceil(n / cap).toInt(), "$p% n=$n uses the minimum line count: $lines")
            }
        }
        // Without lineCap clamping to fit115 (16), a 19-char cap on a 16-char column orphans.
        val t = 1.15f
        val fit115 = (MessageLayout.advColumnWidth(t) / (TWO_COL_PX * t)).toInt()
        assertEquals(listOf(16, 16, 2), wrap(34, TWO_COL_MAX_CHARS, fit115, ::oldBalancedCap))
        assertEquals(listOf(16, 2), wrap(18, TWO_COL_MAX_CHARS, fit115, ::oldBalancedCap))
        assertEquals(listOf(12, 12, 12, 12, 3), wrap(51, 12f, 12, ::oldBalancedCap))
        assertEquals(listOf(11, 11, 11, 11, 7), wrap(51, 12f, 12))
    }

    /** The balancer change leaves the 100 % caps (18, 19 two-col/NOVEL, 34 single) untouched up to 5 lines. */
    @Test
    fun balancerUnchangedAtHundredPercent() {
        for (cap in listOf(18f, 19f, 34f)) for (n in 1..(cap.toInt() * 5)) {
            assertEquals(oldBalancedCap(n.toFloat(), cap), MessageLayout.balancedCap(n.toFloat(), cap), "cap=$cap n=$n")
        }
    }

    /**
     * QA #121 regression under v1.4.4 fixed-font design: IC 1002 token 194 (CN 13+21+21 = 55 chars,
     * JP 15+25+25 = 65 chars in three `。\n` sentences) took 5 lines under `SENTENCE` (`1+2+2`) at
     * 100 %, which used to shrink font size to 21 px. With `JOIN_ALL` fallback in the 4-line×19-char
     * window, all 65 JP chars (`ceil(65 / 19) = 4` lines) fit at full constant 28 px!
     */
    @Test
    fun threeSentenceDialogueFitsFourLinesAtConstant28PxWithJoinAll() {
        val avail = MessageLayout.advTextAvail(MessageLayout.advTop(1f, twoCol = true))
        val colW = MessageLayout.advColumnWidth(1f)
        val cap = MessageLayout.lineCap(colW, TWO_COL_PX, TWO_COL_MAX_CHARS)
        assertEquals(19f, cap)
        val joinedJpLines = wrap(15 + 25 + 25, cap, (colW / TWO_COL_PX).toInt())
        assertEquals(4, joinedJpLines.size)
        assertTrue(joinedJpLines.size * LINE_PITCH * TWO_COL_PX <= avail)
    }

    @Test
    fun quickBarCycle() {
        var p = 100
        val seen = mutableListOf(p)
        repeat(5) { p = TextScale.nextPreset(p); seen += p }
        assertEquals(listOf(100, 115, 130, 145, 85, 100), seen)
        assertEquals(100, TextScale.nextPreset(92))
        assertEquals(85, TextScale.nextPreset(148))
        assertEquals(85, TextScale.nextPreset(75))
        assertEquals(presets, TextScale.PRESETS.map { it.first })
    }

    @Test
    fun stepsAndClamp() {
        assertEquals(105, TextScale.step(100, up = true))
        assertEquals(95, TextScale.step(100, up = false))
        assertEquals(105, TextScale.step(103, up = true))
        assertEquals(100, TextScale.step(103, up = false))
        assertEquals(150, TextScale.step(150, up = true))
        assertEquals(70, TextScale.step(70, up = false))
        assertEquals(150, TextScale.clamp(999))
        assertEquals(70, TextScale.clamp(0))
        assertEquals(1.5f, TextScale.factor(999))
        assertEquals("特大", TextScale.presetLabel(130))
        assertNull(TextScale.presetLabel(120))
    }
}
