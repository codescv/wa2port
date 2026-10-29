package com.wa2port.core.text

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure geometry of the message window (ADV) and NOVEL page, in 1280×720 stage pixels, plus the
 * font-size ([TextScale]) dependent rules. Kept free of Android so the line-cap and window-height
 * math is unit-tested in `:core:test`; the app's `MessageWindow.kt` draws with these values.
 */
object MessageLayout {
    const val STAGE_W = 1280f
    const val STAGE_H = 720f

    /** Glyph sizes at 100 % (stage px). */
    const val MAIN_PX = 30f
    const val SECONDARY_PX = 22f
    const val NAME_PX = 25f

    /** 双语 two-column layout: CN left, JP right, ≤19 full-width chars per line at 100 %. */
    const val TWO_COL_PX = 28f
    const val TWO_COL_MAX_CHARS = 19f
    const val SINGLE_MAX_CHARS = 34f
    const val NOVEL_TWO_COL_PX = 26f

    /** Line pitch as a multiple of the glyph size (`GlyphAtlasText.lineSpacing`). */
    const val LINE_PITCH = 1.5f

    /** ADV window box as fractions of the stage, and paddings in stage px. */
    const val ADV_LEFT = 0.05f
    const val ADV_RIGHT = 0.95f
    const val ADV_TOP = 0.715f
    const val ADV_BOTTOM = 0.975f
    const val ADV_PAD = 22f
    const val ADV_COL_GAP = 30f

    /** Highest the ADV window may grow for large text (fraction of the stage height). */
    const val ADV_TOP_MIN = 0.56f

    /** Lines of single-language main text the ADV window holds. */
    const val ADV_MIN_LINES = 3

    /** Lines of two-column bilingual (`BOTH`) text the ADV window holds. */
    const val ADV_TWO_COL_LINES = 4

    /**
     * Extra stage px of text area beyond the exact line-capacity need, so float rounding at any
     * device height cannot tip `measure <= avail`.
     */
    const val ADV_FIT_MARGIN = 1f

    /** NOVEL page box as fractions of the stage, and the column gap at 100 % (stage px). */
    const val NOVEL_LEFT = 0.08f
    const val NOVEL_WIDTH = 0.84f
    const val NOVEL_COL_GAP = 36f

    /** Smallest line cap a NOVEL column may use (very narrow screens). */
    const val NOVEL_MIN_CHARS = 8f

    /**
     * Per-line full-width character cap for a column [availPx] wide at constant glyph size [sizePx]:
     * the design cap [maxChars] (19 two-column / 34 single-language), capped at what the column
     * physically holds (`floor(availPx / sizePx)`) so large text never double-wraps into 1–2 char
     * orphans.
     */
    fun lineCap(availPx: Float, sizePx: Float, maxChars: Float): Float {
        if (sizePx <= 0f) return maxChars
        return minOf(maxChars, floor(availPx / sizePx).coerceAtLeast(1f))
    }

    /**
     * Balanced per-line cap for one paragraph of [chars] full-width characters under [maxChars]
     * (used by `GlyphAtlasText.layout`): the paragraph takes `L = ceil(chars / maxChars)` lines of
     * about `even = ceil(chars / L)` characters. One character of slack absorbs unsplittable units
     * (ASCII words, ruby groups), but only while the last line stays at least `max(4, even / 2)`
     * long; otherwise `L − 1` slack-filled lines leave a 1–3 character stub (e.g. 51 chars at
     * cap 12: `12/12/12/12/3` → `11/11/11/11/7`). Same as the old `min(maxChars, even + 1)` for
     * full-width paragraphs of up to 5 lines at the 100 % caps (18 / 19 / 34); it may differ for
     * longer paragraphs or half-width counts, always towards a better-balanced last line.
     */
    fun balancedCap(chars: Float, maxChars: Float): Float {
        if (maxChars <= 0f || chars <= maxChars) return maxChars
        val lines = ceil(chars / maxChars)
        val even = ceil(chars / lines)
        val lastWithSlack = chars - (lines - 1f) * (even + 1f)
        val slack = if (lastWithSlack >= maxOf(4f, even / 2f)) 1f else 0f
        return minOf(maxChars, even + slack)
    }

    /** Text area height of the ADV window (stage px) for a window top at [topFrac]. */
    fun advTextAvail(topFrac: Float): Float {
        val top = topFrac * STAGE_H
        val bottom = ADV_BOTTOM * STAGE_H
        val textTop = top + ADV_PAD * 1.25f
        return bottom - ADV_PAD * 0.6f - textTop
    }

    /**
     * ADV window top (fraction of the stage height) for [textScale] (1.0 = 100 %). Holds
     * [ADV_MIN_LINES] (3) lines of [MAIN_PX] in single-language mode and [ADV_TWO_COL_LINES] (4)
     * lines of [TWO_COL_PX] in two-column bilingual mode ([twoCol] = true), plus [ADV_FIT_MARGIN].
     * Never lower than [ADV_TOP] nor above [ADV_TOP_MIN].
     */
    fun advTop(textScale: Float, twoCol: Boolean = false): Float {
        val lines = if (twoCol) ADV_TWO_COL_LINES else ADV_MIN_LINES
        val glyphPx = if (twoCol) TWO_COL_PX else MAIN_PX
        val need = lines * LINE_PITCH * glyphPx * textScale + ADV_FIT_MARGIN
        // Solve advTextAvail(top) == need for top.
        val top = (ADV_BOTTOM * STAGE_H - ADV_PAD * 0.6f - ADV_PAD * 1.25f - need) / STAGE_H
        return top.coerceIn(ADV_TOP_MIN, ADV_TOP)
    }

    /**
     * Gap between the 双语 ADV columns (stage px) for glyphs of [glyphPx] stage px: grows above the
     * 28 px default so one hanging closer (up to one em past the column) cannot cross the divider
     * into the JP column.
     */
    fun advColumnGapFor(glyphPx: Float): Float = ADV_COL_GAP * maxOf(1f, glyphPx / TWO_COL_PX)

    /** Column width (stage px) of the 双语 ADV layout for glyphs of [glyphPx] stage px. */
    fun advColumnWidthFor(glyphPx: Float): Float = (advSingleWidth() - advColumnGapFor(glyphPx)) / 2f

    /** [advColumnGapFor] at the constant 双语 glyph size for [textScale]. */
    fun advColumnGap(textScale: Float): Float = advColumnGapFor(TWO_COL_PX * textScale)

    /** [advColumnWidthFor] at the constant 双语 glyph size for [textScale]. */
    fun advColumnWidth(textScale: Float): Float = advColumnWidthFor(TWO_COL_PX * textScale)

    /** Single-column text width (stage px) of the ADV window. */
    fun advSingleWidth(): Float = (ADV_RIGHT - ADV_LEFT) * STAGE_W - ADV_PAD * 2

    /** NOVEL 双语 column width for the page [width] at combined [scale] (stage scale × text scale). */
    fun novelColumnWidth(width: Float, scale: Float): Float = (width - NOVEL_COL_GAP * scale.coerceAtLeast(0.01f)) / 2f

    /** NOVEL 双语 line cap: whatever the column holds (no upper cap, so small text uses the width). */
    fun novelLineCap(width: Float, scale: Float): Float {
        val size = NOVEL_TWO_COL_PX * scale
        if (size <= 0f) return TWO_COL_MAX_CHARS
        return floor(novelColumnWidth(width, scale) / size).coerceAtLeast(NOVEL_MIN_CHARS)
    }
}

/**
 * Policy for handling script `\n` ([TextItem.NewLine]) breaks when wrapping to a column.
 *
 * * [KEEP_ALL]: preserve every script `\n` break.
 * * [SENTENCE]: preserve script `\n` only after sentence-ending punctuation (`。！？…」』）` etc.) or
 *   before opening quotes/brackets (`「『（` etc.); join mid-sentence breaks.
 * * [JOIN_ALL]: soft-join all script `\n` breaks into a single balanced paragraph (only `\k`
 *   [TextItem.PageBreak] when `pageBreakIsNewLine` breaks).
 */
enum class Reflow { KEEP_ALL, SENTENCE, JOIN_ALL }

/**
 * Font size setting (`Settings.textScale`, percent of the default size): range, one-tap presets,
 * ± steps and the in-game quick-bar cycle.
 */
object TextScale {
    const val MIN = 70
    const val MAX = 150
    const val DEFAULT = 100
    const val STEP = 5

    /** One-tap presets (percent) with their labels, smallest first. */
    val PRESETS: List<Pair<Int, String>> = listOf(
        85 to "小",
        100 to "标准",
        115 to "大",
        130 to "特大",
        145 to "超大",
    )

    /** [percent] clamped to [MIN]..[MAX] (settings files may hold anything). */
    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** Scale factor (1.0 = 100 %) for a stored percent. */
    fun factor(percent: Int): Float = clamp(percent) / 100f

    /** Label of the preset at [percent], or null when [percent] is not a preset. */
    fun presetLabel(percent: Int): String? = PRESETS.firstOrNull { it.first == percent }?.second

    /**
     * Quick-bar cycle: the next preset above [percent], wrapping to the smallest
     * (100 → 115 → 130 → 145 → 85 → 100). Off-preset values jump to the next preset up.
     */
    fun nextPreset(percent: Int): Int = PRESETS.firstOrNull { it.first > percent }?.first ?: PRESETS.first().first

    /** One `+` ([up]) or `−` step: to the next multiple of [STEP] in that direction, clamped. */
    fun step(percent: Int, up: Boolean): Int {
        val p = clamp(percent)
        val next = if (up) (p / STEP + 1) * STEP else ((p + STEP - 1) / STEP - 1) * STEP
        return clamp(next)
    }
}
