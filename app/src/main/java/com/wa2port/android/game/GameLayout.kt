package com.wa2port.android.game

import com.wa2port.android.data.DisplayLang
import com.wa2port.core.text.DeckLayout

/** Where the quick bar goes: landscape letterbox sides / top, over the stage, or the portrait HUD row. */
enum class BarPlacement { SIDE, TOP, OVERLAY, PORTRAIT_BAR }

/** Axis-aligned rectangle in dp (`left, top, right, bottom`). */
data class DpRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Game screen geometry in dp for one window size (see [GameLayout.compute]).
 *
 * The **content box** ([content], window dp) holds the stage, the ADV / NOVEL text, the gesture
 * layer and the choice cards; every rect below except [content] is relative to the content box.
 * Landscape: content box == stage box (today's letterboxed 16:9 stage). Portrait: content box =
 * `(0, stageTop, w, h)` with the 16:9 stage at its top and the reading deck below.
 */
data class GameGeometry(
    val portrait: Boolean,
    val placement: BarPlacement,
    /** Content box in window dp. */
    val content: DpRect,
    /** Stage box inside the content box (full 16:9 picture, never cropped). */
    val stage: DpRect,
    /** Landscape letterbox sizes (side bar width / top bar height); 0 in portrait. */
    val side: Float,
    val topBox: Float,
    /** Portrait HUD row (quick bar) top and height in window dp; 0 in landscape. */
    val hudTop: Float,
    val hudHeight: Float,
    /** Portrait reading deck (content dp), null in landscape. */
    val deck: DpRect?,
    /** Portrait ADV / NOVEL frame (content dp; = [deck] or its upper part with a choice prompt), null in landscape. */
    val advFrame: DpRect?,
    /** Portrait choice card area (content dp), null in landscape or without choices. */
    val choiceRect: DpRect?,
    /** 双语 layout of the portrait deck. */
    val bothMode: DeckLayout.BothMode,
) {
    val stageWidth: Float get() = stage.width
    val stageHeight: Float get() = stage.height
}

/** Pure game-screen layout: landscape = today's formulas verbatim; portrait = "split-stage theater". */
object GameLayout {
    /** Portrait HUD (quick bar) row height (dp). */
    const val HUD_HEIGHT_DP = 48f

    /** Landscape: side letterbox width that hosts the quick-bar columns. */
    const val SIDE_MIN_DP = 60f

    /** Landscape: top letterbox height that hosts the quick-bar row. */
    const val TOP_MIN_DP = 56f

    /**
     * @param wDp window width, @param hDp window height (dp)
     * @param insetTopDp / @param insetBottomDp floored safe insets (portrait only)
     * @param textScale font size factor (1.0 = 100 %)
     * @param hasChoices choices are on screen; @param hasLine a prompt line is shown with them
     *   (`hasChoices && hasLine` splits the deck into prompt + cards, GameScreen choice semantics)
     */
    fun compute(
        wDp: Float,
        hDp: Float,
        insetTopDp: Float,
        insetBottomDp: Float,
        textScale: Float,
        lang: DisplayLang,
        hasChoices: Boolean,
        hasLine: Boolean,
    ): GameGeometry = if (hDp > wDp) {
        portrait(wDp, hDp, insetTopDp, insetBottomDp, textScale, lang, hasChoices, hasLine)
    } else {
        landscape(wDp, hDp)
    }

    private fun landscape(w: Float, h: Float): GameGeometry {
        val stageW = minOf(w, h * 16f / 9f)
        val stageH = stageW * 9f / 16f
        val side = (w - stageW) / 2f
        val topBox = (h - stageH) / 2f
        val placement = when {
            side >= SIDE_MIN_DP -> BarPlacement.SIDE
            topBox >= TOP_MIN_DP -> BarPlacement.TOP
            else -> BarPlacement.OVERLAY
        }
        return GameGeometry(
            portrait = false,
            placement = placement,
            content = DpRect(side, topBox, side + stageW, topBox + stageH),
            stage = DpRect(0f, 0f, stageW, stageH),
            side = side,
            topBox = topBox,
            hudTop = 0f,
            hudHeight = 0f,
            deck = null,
            advFrame = null,
            choiceRect = null,
            bothMode = DeckLayout.BothMode.TWO_COL,
        )
    }

    private fun portrait(
        w: Float,
        h: Float,
        insetTop: Float,
        insetBottom: Float,
        textScale: Float,
        lang: DisplayLang,
        hasChoices: Boolean,
        hasLine: Boolean,
    ): GameGeometry {
        val hudTop = insetTop
        val stageTop = hudTop + HUD_HEIGHT_DP
        val availH = h - stageTop - insetBottom
        val deckW = w - 2f * DeckLayout.DECK_SIDE_DP
        val bothMode = DeckLayout.bothMode(DeckLayout.textWidth(deckW), textScale)
        val stacked = lang == DisplayLang.BOTH && bothMode == DeckLayout.BothMode.STACKED
        val deckMin = DeckLayout.minDeckDp(textScale, stacked)
        val stageW = minOf(w, maxOf(0f, availH - deckMin) * 16f / 9f)
        val stageH = stageW * 9f / 16f
        val stageLeft = (w - stageW) / 2f
        val contentH = h - stageTop
        val deck = DpRect(
            DeckLayout.DECK_SIDE_DP,
            stageH + DeckLayout.DECK_GAP_DP,
            w - DeckLayout.DECK_SIDE_DP,
            maxOf(stageH + DeckLayout.DECK_GAP_DP, contentH - insetBottom - DeckLayout.DECK_BOTTOM_DP),
        )
        val hasPromptChoices = hasChoices && hasLine
        val advFrame: DpRect
        val choiceRect: DpRect?
        if (!hasPromptChoices) {
            advFrame = deck
            choiceRect = if (hasChoices) deck else null
        } else {
            val promptH = DeckLayout.promptHeight(deck.height, textScale, stacked)
            advFrame = deck.copy(bottom = deck.top + promptH)
            choiceRect = DpRect(deck.left, advFrame.bottom + DeckLayout.CHOICE_GAP_DP, deck.right, maxOf(advFrame.bottom + DeckLayout.CHOICE_GAP_DP, deck.bottom))
        }
        return GameGeometry(
            portrait = true,
            placement = BarPlacement.PORTRAIT_BAR,
            content = DpRect(0f, stageTop, w, h),
            stage = DpRect(stageLeft, 0f, stageLeft + stageW, stageH),
            side = 0f,
            topBox = 0f,
            hudTop = hudTop,
            hudHeight = HUD_HEIGHT_DP,
            deck = deck,
            advFrame = advFrame,
            choiceRect = choiceRect,
            bothMode = bothMode,
        )
    }
}
