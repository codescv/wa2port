package com.wa2port.core

import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.MessageLayout.FIT_STEPS
import com.wa2port.core.text.MessageLayout.LINE_PITCH
import com.wa2port.core.text.MessageLayout.MAIN_PX
import com.wa2port.core.text.MessageLayout.SINGLE_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_PX
import com.wa2port.core.text.TextScale
import kotlin.math.ceil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** Font size setting: line caps, ADV window growth, NOVEL caps and the preset / step policy. */
class FontScaleTest {
    private val presets = listOf(85, 100, 115, 130, 145)

    /** Every slider value 70..150 as a factor. */
    private val allScales = (TextScale.MIN..TextScale.MAX).map { it / 100f }

    @Test
    fun hundredPercentIsUnchanged() {
        assertEquals(MessageLayout.ADV_TOP, MessageLayout.advTop(1f))
        assertEquals(MessageLayout.ADV_COL_GAP, MessageLayout.advColumnGap(1f))
        assertEquals(539f, MessageLayout.advColumnWidth(1f))
        assertEquals(1108f, MessageLayout.advSingleWidth())
        assertEquals(18f, MessageLayout.lineCap(MessageLayout.advColumnWidth(1f), TWO_COL_PX, TWO_COL_MAX_CHARS, stepped = false))
        assertEquals(34f, MessageLayout.lineCap(MessageLayout.advSingleWidth(), MAIN_PX, SINGLE_MAX_CHARS, stepped = false))
        assertEquals(19f, MessageLayout.novelLineCap(MessageLayout.NOVEL_WIDTH * MessageLayout.STAGE_W, 1f))
    }

    @Test
    fun twoColumnCapsAtPresets() {
        val expected = mapOf(85 to 18f, 100 to 18f, 115 to 16f, 130 to 14f, 145 to 13f, 150 to 12f)
        for ((p, cap) in expected) {
            val t = p / 100f
            assertEquals(cap, MessageLayout.lineCap(MessageLayout.advColumnWidth(t), TWO_COL_PX * t, TWO_COL_MAX_CHARS, false), "two-col $p%")
        }
    }

    @Test
    fun singleLanguageCapsAtPresets() {
        val expected = mapOf(85 to 34f, 100 to 34f, 115 to 32f, 130 to 28f, 145 to 25f, 150 to 24f)
        for ((p, cap) in expected) {
            val t = p / 100f
            assertEquals(cap, MessageLayout.lineCap(MessageLayout.advSingleWidth(), MAIN_PX * t, SINGLE_MAX_CHARS, false), "single $p%")
        }
    }

    /** cap × size ≤ width for every slider value and every fit step: the pixel wrap never fires first. */
    @Test
    fun capNeverExceedsColumnWidth() {
        for (t in allScales) for (f in FIT_STEPS) {
            val stepped = f < 1f
            val colW = MessageLayout.advColumnWidth(t)
            val two = TWO_COL_PX * t * f
            assertTrue(MessageLayout.lineCap(colW, two, TWO_COL_MAX_CHARS, stepped) * two <= colW, "two-col t=$t f=$f")
            val w = MessageLayout.advSingleWidth()
            val one = MAIN_PX * t * f
            assertTrue(MessageLayout.lineCap(w, one, SINGLE_MAX_CHARS, stepped) * one <= w, "single t=$t f=$f")
        }
    }

    /** Stepping down never narrows the cap below the nominal one (the old "widen to the column" intent). */
    @Test
    fun steppedCapWidens() {
        for (t in allScales) {
            val colW = MessageLayout.advColumnWidth(t)
            val nominal = MessageLayout.lineCap(colW, TWO_COL_PX * t, TWO_COL_MAX_CHARS, false)
            for (f in FIT_STEPS.drop(1)) {
                assertTrue(MessageLayout.lineCap(colW, TWO_COL_PX * t * f, TWO_COL_MAX_CHARS, true) >= nominal, "t=$t f=$f")
            }
        }
    }

    /** Three lines of main text (and of 双语 text) fit the ADV window at the nominal size up to 150 %. */
    @Test
    fun windowHoldsThreeLinesAtEveryScale() {
        for (t in allScales) {
            val avail = MessageLayout.advTextAvail(MessageLayout.advTop(t))
            assertTrue(avail >= 3 * LINE_PITCH * MAIN_PX * t, "single t=$t avail=$avail")
            assertTrue(avail >= 3 * LINE_PITCH * TWO_COL_PX * t, "two-col t=$t avail=$avail")
        }
    }

    /**
     * Same check in float32 with `AdvWindow`'s exact operation order on real device stage heights,
     * so rounding can never tip a 3-line message into the fit loop (no tolerance).
     */
    @Test
    fun windowHoldsThreeLinesOnDeviceHeights() {
        val heights = listOf(540f, 607.5f, 720f, 810f, 900f, 1012.5f, 1080f, 1170f, 1206f, 1350f, 1440f, 1600f)
        for (h in heights) for (p in TextScale.MIN..TextScale.MAX) {
            val t = p / 100f
            val scale = h / 720f * t
            val stageScale = h / 720f
            val top = h * MessageLayout.advTop(t)
            val bottom = h * MessageLayout.ADV_BOTTOM
            val pad = MessageLayout.ADV_PAD * stageScale
            val textTop = top + pad * 1.25f
            val avail = bottom - pad * 0.6f - textTop
            val single = 3 * ((MAIN_PX * scale * 1f) * LINE_PITCH)
            val two = 3 * ((TWO_COL_PX * scale * 1f) * LINE_PITCH)
            assertTrue(single <= avail, "single h=$h p=$p need=$single avail=$avail")
            assertTrue(two <= avail, "two-col h=$h p=$p need=$two avail=$avail")
        }
    }

    @Test
    fun windowGrowsOnlyUpwardsAndWithinBounds() {
        var prev = 1f
        for (t in allScales) {
            val top = MessageLayout.advTop(t)
            assertTrue(top <= prev, "monotone at t=$t")
            assertTrue(top in MessageLayout.ADV_TOP_MIN..MessageLayout.ADV_TOP, "bounds at t=$t: $top")
            if (t <= 1f) assertEquals(MessageLayout.ADV_TOP, top, "small text keeps the classic window (t=$t)")
            prev = top
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
            val cap = MessageLayout.lineCap(colW, size, TWO_COL_MAX_CHARS, false)
            for (n in 4..(cap.toInt() * 5)) {
                val lines = wrap(n, cap, fit)
                assertEquals(n, lines.sum())
                assertTrue(lines.all { it >= 4 }, "$p% n=$n: $lines")
                assertTrue(lines.size <= kotlin.math.ceil(n / cap).toInt(), "$p% n=$n uses the minimum line count: $lines")
            }
        }
        // The pre-fix behaviour (cap fixed at 18 with a 16-char column) really did orphan at 115 %.
        val t = 1.15f
        val fit115 = (MessageLayout.advColumnWidth(t) / (TWO_COL_PX * t)).toInt()
        assertEquals(listOf(16, 16, 2), wrap(34, TWO_COL_MAX_CHARS, fit115, ::oldBalancedCap))
        assertEquals(listOf(16, 2), wrap(18, TWO_COL_MAX_CHARS, fit115, ::oldBalancedCap))
        assertEquals(listOf(12, 12, 12, 12, 3), wrap(51, 12f, 12, ::oldBalancedCap))
        assertEquals(listOf(11, 11, 11, 11, 7), wrap(51, 12f, 12))
    }

    /** The balancer change leaves the 100 % caps (18 two-col, 19 NOVEL, 34 single) untouched up to 5 lines. */
    @Test
    fun balancerUnchangedAtHundredPercent() {
        for (cap in listOf(18f, 19f, 34f)) for (n in 1..(cap.toInt() * 5)) {
            assertEquals(oldBalancedCap(n.toFloat(), cap), MessageLayout.balancedCap(n.toFloat(), cap), "cap=$cap n=$n")
        }
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
