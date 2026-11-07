package com.wa2port.android.game

import androidx.compose.ui.geometry.Rect
import com.wa2port.core.text.DeckLayout

/**
 * Pure geometry of the speaker nameplate tab drawn by [AdvWindow] on the window / deck frame top.
 *
 * Coordinates are canvas px; [stageScale] = px per stage px. Name glyph boxes follow the
 * [com.wa2port.android.bridge.TextPainter.draw] convention: `y` is the *line box* top and the glyph
 * em sits [com.wa2port.android.bridge.TextPainter.glyphInkTop] below it (the atlas painter puts
 * glyphs at the bottom of a `1.5 em` line box, the band above holds furigana). Centering the
 * line box instead of the glyph em is what dropped the v1.4.9 portrait name 0.65 em low, past the
 * tab bottom and across the frame's top border stroke.
 *
 * * Landscape: the PC tab (`1.5 em` tall, 55 % above the window top) — rect unchanged.
 * * Portrait: a slightly tighter `1.4 em` tab, 55 % above the frame top, clamped so it stays
 *   [STAGE_CLEAR] stage px below the 16:9 stage bottom (inside the [DeckLayout.DECK_GAP_DP] gap)
 *   and [TEXT_CLEAR] stage px above the first text line (`textTop`) at every text scale.
 * * Every name block's glyph em is vertically centred inside the tab ([inkTop]).
 * * Horizontal fit next to the rim pills (`✨ AI解读` / `🔊 重听`) is [AdvPills.fitNameplate]'s job;
 *   pass the fitted width as `namesWidth`.
 */
internal object NamePlate {
    /** Landscape tab height in name ems (unchanged PC layout). */
    const val LANDSCAPE_TAB_EM = 1.5f

    /** Portrait tab height in name ems. */
    const val PORTRAIT_TAB_EM = 1.4f

    /** Fraction of the tab above the frame top. */
    const val OVERHANG = 0.55f

    /** Minimum gap (stage px) between the portrait tab top and the 16:9 stage bottom. */
    const val STAGE_CLEAR = 4f

    /** Minimum gap (stage px) between the portrait tab bottom and `textTop`. */
    const val TEXT_CLEAR = 3f

    /** Horizontal gap (stage px) between the CN and JP name blocks. */
    const val NAME_GAP = 12f

    /** Gap between the stage bottom and the portrait deck frame top, in stage px. */
    const val DECK_GAP_STAGE = DeckLayout.DECK_GAP_DP / DeckLayout.DECK_SCALE

    /**
     * Tab rect for a name of em [nameEm] (the CN / main name size) and total glyph advance
     * [namesWidth] (all blocks + inter-block gaps), on a frame whose left edge is [frameLeft], top
     * is [frameTop] and first text line top is [textTop]. [portrait] selects the deck tab.
     */
    fun tab(
        frameLeft: Float,
        frameTop: Float,
        textTop: Float,
        pad: Float,
        nameEm: Float,
        namesWidth: Float,
        stageScale: Float,
        portrait: Boolean,
    ): Rect {
        val left = frameLeft + pad
        val width = namesWidth + pad * 2f
        if (!portrait) {
            val h = nameEm * LANDSCAPE_TAB_EM
            val top = frameTop - h * OVERHANG
            return Rect(left, top, left + width, top + h)
        }
        var h = nameEm * PORTRAIT_TAB_EM
        val maxBottom = textTop - TEXT_CLEAR * stageScale
        val minTop = frameTop - (DECK_GAP_STAGE - STAGE_CLEAR) * stageScale
        val top = minOf(frameTop - h * OVERHANG, maxBottom - h).coerceAtLeast(minTop)
        // Only for scales far beyond 150 %: shrink rather than touch the stage or the text.
        if (top + h > maxBottom) h = (maxBottom - top).coerceAtLeast(nameEm)
        return Rect(left, top, left + width, top + h)
    }

    /** Top of a glyph em [em] vertically centred in [tab]. */
    fun inkTop(tab: Rect, em: Float): Float = tab.top + (tab.height - em) / 2f

    /** `TextPainter.draw` y for a block of em [em] whose glyph em starts [glyphInkTop] below its line top. */
    fun drawY(tab: Rect, em: Float, glyphInkTop: Float): Float = inkTop(tab, em) - glyphInkTop
}
