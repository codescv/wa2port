package com.wa2port.android.game

import com.wa2port.android.data.DisplayLang
import com.wa2port.android.ui.ScreenInsets
import com.wa2port.core.text.DeckLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [GameLayout]: landscape = today's letterbox formulas, portrait = split-stage theater (spec §2.1). */
class GameLayoutTest {
    private val eps = 0.05f

    private fun landscape(w: Float, h: Float) = GameLayout.compute(w, h, 8f, 16f, 1f, DisplayLang.BOTH, hasChoices = false, hasLine = true)

    @Test
    fun landscapeEqualsTodaysFormulas() {
        val b = landscape(915f, 412f)
        assertFalse(b.portrait)
        assertEquals(BarPlacement.SIDE, b.placement)
        assertEquals(732.44f, b.stageWidth, eps)
        assertEquals(412f, b.stageHeight, eps)
        assertEquals(91.28f, b.side, eps)
        // Content box == centred stage box; no portrait deck.
        assertEquals(DpRect(91.28f, 0f, 823.72f, 412f).left, b.content.left, eps)
        assertEquals(b.stageWidth, b.content.width, eps)
        assertEquals(b.stageHeight, b.content.height, eps)
        assertEquals(DpRect(0f, 0f, b.stageWidth, b.stageHeight), b.stage)
        assertNull(b.deck)
        assertNull(b.advFrame)
        assertNull(b.choiceRect)

        val narrow = landscape(800f, 360f)
        assertEquals(BarPlacement.SIDE, narrow.placement)
        assertEquals(80f, narrow.side, eps)
        assertEquals(640f, narrow.stageWidth, eps)

        val tablet = landscape(1280f, 800f)
        assertEquals(BarPlacement.OVERLAY, tablet.placement)
        assertEquals(40f, tablet.topBox, eps)

        val fourThree = landscape(1024f, 768f)
        assertEquals(BarPlacement.TOP, fourThree.placement)
        assertEquals(96f, fourThree.topBox, eps)
        assertEquals(576f, fourThree.stageHeight, eps)

        // Square windows are landscape (portrait needs h > w); insets / scale / choices never matter.
        assertFalse(landscape(700f, 700f).portrait)
        assertEquals(b, GameLayout.compute(915f, 412f, 36f, 48f, 1.5f, DisplayLang.JP, hasChoices = true, hasLine = true))
    }

    private data class Device(
        val w: Float,
        val h: Float,
        val cutoutTop: Float,
        val hudTop: Float,
        val stageH: Float,
        val deckTopScreen: Float,
        val deckBottomScreen: Float,
    )

    private val a = Device(360f, 780f, 32f, 32f, 202.5f, 302.5f, 748f)
    private val b = Device(412f, 915f, 36f, 36f, 231.75f, 335.75f, 883f)
    private val c = Device(600f, 960f, 24f, 24f, 337.5f, 429.5f, 928f)
    private val d = Device(673f, 841f, 0f, 8f, 378.56f, 454.56f, 809f)
    private val devices = listOf(a, b, c, d)

    private fun portrait(dev: Device, t: Float = 1f, choices: Boolean = false, line: Boolean = true, lang: DisplayLang = DisplayLang.BOTH): GameGeometry {
        val insets = ScreenInsets.of(cutoutTopDp = dev.cutoutTop, gestureBottomDp = 24f, cutoutLeftDp = 0f, cutoutRightDp = 0f)
        return GameLayout.compute(dev.w, dev.h, insets.top.value, insets.bottom.value, t, lang, choices, line)
    }

    @Test
    fun portraitTableNoChoices() {
        for (dev in devices) for (t in listOf(1f, 1.5f)) {
            val g = portrait(dev, t)
            val tag = "${dev.w}×${dev.h} @$t"
            assertTrue(g.portrait, tag)
            assertEquals(BarPlacement.PORTRAIT_BAR, g.placement, tag)
            assertEquals(dev.hudTop, g.hudTop, eps, tag)
            assertEquals(48f, g.hudHeight, eps, tag)
            // Content box = (0, stageTop, w, h); full-width uncropped 16:9 stage at its top.
            assertEquals(DpRect(0f, dev.hudTop + 48f, dev.w, dev.h), g.content, tag)
            assertEquals(dev.w, g.stageWidth, eps, tag)
            assertEquals(dev.stageH, g.stageHeight, eps, tag)
            assertEquals(g.stageWidth * 9f / 16f, g.stageHeight, 1e-3f, tag)
            // Deck: 16 dp margins, 20 dp below the stage, 8 dp above the bottom inset.
            val deck = g.deck!!
            assertEquals(16f, deck.left, eps, tag)
            assertEquals(dev.w - 16f, deck.right, eps, tag)
            assertEquals(dev.deckTopScreen, deck.top + g.content.top, eps, tag)
            assertEquals(dev.deckBottomScreen, deck.bottom + g.content.top, eps, tag)
            assertEquals(deck, g.advFrame, tag)
            assertNull(g.choiceRect, tag)
            assertEquals(DeckLayout.BothMode.STACKED, g.bothMode, tag)
            // The deck holds 3 + 3 stacked lines at every scale.
            assertTrue(deck.height + DeckLayout.DECK_GAP_DP + DeckLayout.DECK_BOTTOM_DP >= DeckLayout.minDeckDp(t, stacked = true), tag)
        }
        assertEquals(445.5f, portrait(a).deck!!.height, eps)
        assertEquals(547.25f, portrait(b).deck!!.height, eps)
        assertEquals(498.5f, portrait(c).deck!!.height, eps)
        assertEquals(354.44f, portrait(d).deck!!.height, eps)
    }

    @Test
    fun portraitChoicesWithPromptSplitTheDeck() {
        val expected100 = mapOf(a to 300.1f, b to 401.85f, c to 353.1f, d to 209.0f)
        val expected150 = mapOf(a to 252.0f, b to 353.75f, c to 305.0f, d to 186.9f)
        for (dev in devices) for (t in listOf(1f, 1.25f, 1.5f)) {
            val g = portrait(dev, t, choices = true, line = true)
            val deck = g.deck!!
            val frame = g.advFrame!!
            val cards = g.choiceRect!!
            val tag = "${dev.w}×${dev.h} @$t"
            assertEquals(deck.top, frame.top, eps, tag)
            assertEquals(minOf(0.45f * deck.height, DeckLayout.promptDeckDp(t, stacked = true)), frame.height, eps, tag)
            assertEquals(frame.bottom + 8f, cards.top, eps, tag)
            assertEquals(deck.bottom, cards.bottom, eps, tag)
            assertEquals(deck.left, cards.left, eps, tag)
            assertEquals(deck.right, cards.right, eps, tag)
            assertTrue(cards.height >= 186f, "$tag choiceRect.h=${cards.height}")
            // Stage never covered: the whole split lives below the stage.
            assertTrue(frame.top >= g.stageHeight, tag)
            // Same stage as without choices (choices never move the stage).
            assertEquals(portrait(dev, t).stage, g.stage, tag)
        }
        for ((dev, h) in expected100) assertEquals(h, portrait(dev, 1f, choices = true).choiceRect!!.height, 0.5f, "100% ${dev.w}")
        for ((dev, h) in expected150) assertEquals(h, portrait(dev, 1.5f, choices = true).choiceRect!!.height, 0.5f, "150% ${dev.w}")
    }

    @Test
    fun portraitChoicesWithoutLineUseTheWholeDeck() {
        for (dev in devices) {
            val g = portrait(dev, choices = true, line = false)
            assertEquals(g.deck, g.choiceRect)
            assertEquals(g.deck, g.advFrame)
        }
    }

    @Test
    fun splitScreenAt150PercentPillarboxesTheStage() {
        val insets = ScreenInsets.of(0f, 24f, 0f, 0f)
        assertEquals(8f, insets.top.value)
        val g = GameLayout.compute(360f, 500f, insets.top.value, insets.bottom.value, 1.5f, DisplayLang.BOTH, false, true)
        assertTrue(g.portrait)
        assertEquals(208f, g.stageWidth, 1f)
        assertEquals(117f, g.stageHeight, 1f)
        assertEquals((360f - g.stageWidth) / 2f, g.stage.left, eps)
        // The deck still holds the minimum 3 + 3 lines.
        assertTrue(g.deck!!.height + DeckLayout.DECK_GAP_DP + DeckLayout.DECK_BOTTOM_DP >= DeckLayout.minDeckDp(1.5f, true) - 0.01f)
    }

    @Test
    fun insetFloors() {
        val none = ScreenInsets.of(0f, 0f, 0f, 0f)
        assertEquals(8f, none.top.value)
        assertEquals(16f, none.bottom.value)
        val g = GameLayout.compute(673f, 841f, none.top.value, none.bottom.value, 1f, DisplayLang.BOTH, false, true)
        assertEquals(8f, g.hudTop, eps)
        assertEquals(841f - 56f - 16f - 8f, g.deck!!.bottom, eps)
        val big = ScreenInsets.of(40f, 30f, 12f, 0f)
        assertEquals(40f, big.top.value)
        assertEquals(30f, big.bottom.value)
        assertEquals(12f, big.left.value)
    }

    @Test
    fun singleLanguageAndWideDecks() {
        // Single language needs less deck → never a smaller stage than bilingual.
        for (dev in devices) assertTrue(portrait(dev, 1.5f, lang = DisplayLang.JP).stageWidth >= portrait(dev, 1.5f).stageWidth)
        // An 800 dp wide portrait tablet gets side-by-side 双语 columns.
        val wide = GameLayout.compute(800f, 1280f, 24f, 24f, 1f, DisplayLang.BOTH, false, true)
        assertEquals(DeckLayout.BothMode.TWO_COL, wide.bothMode)
    }
}
