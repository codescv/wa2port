package com.wa2port.core.text

import kotlin.math.floor

/**
 * Pure geometry of the portrait reading deck (the ADV / NOVEL text panel below the 16:9 stage).
 *
 * The deck renders every existing [MessageLayout] stage-px constant (pad, name box, voice pill,
 * stroke, glyph sizes, column gap) at [DECK_SCALE] dp per stage px — i.e. as if the stage were
 * 412 dp tall, the glyph / chrome size of the same phone held sideways. Glyph sizes are therefore
 * constant per [TextScale] and independent of the screen width; only the line caps change.
 *
 * Every function takes [s] = output units per stage px: the default [DECK_SCALE] gives dp; pass
 * `DECK_SCALE × density` to get px. [MessageLayout] (the landscape geometry) is untouched.
 */
object DeckLayout {
    /** dp per stage px in the deck. */
    const val DECK_SCALE = 412f / 720f

    /** Gap between the stacked CN block and the JP block below it (dp; divider drawn at +9 dp). */
    const val STACKED_GAP_DP = 18f

    /** Gap between the stage bottom and the deck top (dp; holds the nameplate / 🔊 pill overhang). */
    const val DECK_GAP_DP = 20f

    /** Horizontal deck margin on each side (dp). */
    const val DECK_SIDE_DP = 16f

    /** Margin between the deck bottom and the bottom safe inset (dp). */
    const val DECK_BOTTOM_DP = 8f

    /** Gap between the prompt frame and the choice cards when the deck is split (dp). */
    const val CHOICE_GAP_DP = 8f

    /** Largest fraction of the deck the choice prompt may take. */
    const val PROMPT_MAX_FRAC = 0.45f

    /** Lines of text per language block the deck must hold without scrolling. */
    const val DECK_LINES = 3

    /** Lines of text per language block the choice prompt frame holds. */
    const val PROMPT_LINES = 2

    /** Smallest bilingual NOVEL paragraph in lines (speaker name + CN + JP). */
    const val NOVEL_MIN_PARAGRAPH_LINES = 3

    /** 双语 deck layout: side-by-side columns (wide decks) or CN block above JP block (phones). */
    enum class BothMode { TWO_COL, STACKED }

    /** Glyph em for a [stagePx] glyph constant (e.g. [MessageLayout.TWO_COL_PX]) at [textScale]. */
    fun em(stagePx: Float, textScale: Float, s: Float = DECK_SCALE): Float = stagePx * s * textScale

    /** Inner deck padding ([MessageLayout.ADV_PAD] scaled). */
    fun pad(s: Float = DECK_SCALE): Float = MessageLayout.ADV_PAD * s

    /** Text width inside a deck frame [frameWidth] wide. */
    fun textWidth(frameWidth: Float, s: Float = DECK_SCALE): Float = frameWidth - 2f * pad(s)

    /**
     * Width available to `cap` glyphs: one hanging closer may extend one em past `cap·em`, so the
     * part of that em not absorbed by the right padding is reserved. Guarantees
     * `left + pad + (cap + 1)·em ≤ right` for every text scale.
     */
    fun capWidth(textWidth: Float, em: Float, s: Float = DECK_SCALE): Float = textWidth - maxOf(0f, em - pad(s))

    /** Full-width line cap of a deck block: `min(34, ⌊capW / em⌋)`. */
    fun lineCap(textWidth: Float, em: Float, s: Float = DECK_SCALE): Float =
        MessageLayout.lineCap(capWidth(textWidth, em, s), em, MessageLayout.SINGLE_MAX_CHARS)

    /** Column gap of the 双语 TWO_COL deck at [textScale] ([MessageLayout.advColumnGap] scaled). */
    fun columnGap(textScale: Float, s: Float = DECK_SCALE): Float = MessageLayout.advColumnGap(textScale) * s

    /** TWO_COL only when each column still holds [MessageLayout.TWO_COL_MAX_CHARS] (19) glyphs. */
    fun bothMode(textWidth: Float, textScale: Float, s: Float = DECK_SCALE): BothMode {
        val em = em(MessageLayout.TWO_COL_PX, textScale, s)
        val colW = (textWidth - columnGap(textScale, s)) / 2f
        return if (colW >= MessageLayout.TWO_COL_MAX_CHARS * em) BothMode.TWO_COL else BothMode.STACKED
    }

    /** Line pitch × [lines] for a block of glyphs [em]. */
    private fun blockHeight(lines: Int, em: Float): Float = lines * MessageLayout.LINE_PITCH * em

    /** Em of the main text block: 双语 stacked blocks use TWO_COL_PX, single language MAIN_PX. */
    private fun blockEm(textScale: Float, stacked: Boolean, s: Float): Float =
        em(if (stacked) MessageLayout.TWO_COL_PX else MessageLayout.MAIN_PX, textScale, s)

    /**
     * Smallest deck height (dp, including the [DECK_GAP_DP] stage gap and the [DECK_BOTTOM_DP]
     * margin) holding [DECK_LINES] lines per block without scrolling:
     * `20 + 0.55·NAME·1.5·s·t + 1.85·pad + 3·1.5·em (+ 18 + 3·1.5·em when stacked) + 8`
     * (≈ 225 dp @100 %, ≈ 303 dp @150 % stacked).
     */
    fun minDeckDp(textScale: Float, stacked: Boolean): Float {
        val s = DECK_SCALE
        val em = blockEm(textScale, stacked, s)
        val nameOverhang = 0.55f * MessageLayout.NAME_PX * 1.5f * s * textScale
        val chrome = (1.25f + 0.6f) * pad(s)
        val second = if (stacked) STACKED_GAP_DP + blockHeight(DECK_LINES, em) else 0f
        return DECK_GAP_DP + nameOverhang + chrome + blockHeight(DECK_LINES, em) + second + DECK_BOTTOM_DP
    }

    /**
     * Inner frame height (dp) of the choice prompt: [PROMPT_LINES] lines per block (the deck rect
     * already excludes the stage gap / bottom margin; the nameplate overhang lives in the gap)
     * (≈ 138 dp @100 %, ≈ 186 dp @150 % stacked).
     */
    fun promptDeckDp(textScale: Float, stacked: Boolean): Float {
        val s = DECK_SCALE
        val em = blockEm(textScale, stacked, s)
        val chrome = (1.25f + 0.6f) * pad(s)
        val second = if (stacked) STACKED_GAP_DP + blockHeight(PROMPT_LINES, em) else 0f
        return chrome + blockHeight(PROMPT_LINES, em) + second
    }

    /** Prompt frame height inside a deck [deckHeight] dp tall: `min(0.45·deck, promptDeckDp)`. */
    fun promptHeight(deckHeight: Float, textScale: Float, stacked: Boolean): Float =
        minOf(PROMPT_MAX_FRAC * deckHeight, promptDeckDp(textScale, stacked))

    /** Text area height of a deck frame [frameHeight] tall (`textTop = top + 1.25·pad`, bottom pad 0.6). */
    fun textAvail(frameHeight: Float, s: Float = DECK_SCALE): Float = frameHeight - (1.25f + 0.6f) * pad(s)

    /** NOVEL lines (at [MessageLayout.NOVEL_TWO_COL_PX]) a deck frame [frameHeight] dp tall holds. */
    fun novelCapacityLines(frameHeight: Float, textScale: Float): Int {
        val pitch = MessageLayout.LINE_PITCH * em(MessageLayout.NOVEL_TWO_COL_PX, textScale)
        if (pitch <= 0f) return 0
        return floor(textAvail(frameHeight) / pitch).toInt().coerceAtLeast(0)
    }

    /** Smallest bilingual NOVEL paragraphs (name + CN + JP lines) a deck frame holds. */
    fun novelParagraphCapacity(frameHeight: Float, textScale: Float): Int =
        novelCapacityLines(frameHeight, textScale) / NOVEL_MIN_PARAGRAPH_LINES
}
