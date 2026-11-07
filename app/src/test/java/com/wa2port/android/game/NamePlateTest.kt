package com.wa2port.android.game

import androidx.compose.ui.geometry.Rect
import com.wa2port.android.bridge.AtlasTextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.render.GlyphAtlasText
import com.wa2port.android.ui.ScreenInsets
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.text.DeckLayout
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Speaker nameplate tab ([NamePlate]) — v1.4.10 fix for "竖屏下名字排版不对 跑道框下面了": the
 * portrait name glyphs sat 0.65 em low (centred the 1.5 em *line box*, not the glyph em), past
 * the tab bottom and across the frame's top border.
 */
class NamePlateTest {
    @TempDir
    lateinit var tempDir: File

    private val widths = listOf(360f, 384f, 393f, 412f, 480f, 540f, 600f, 673f)
    private val scales = listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f, 1.5f)
    private val densities = listOf(1f, 2f, 2.625f, 3.5f)

    /** Atlas convention: glyph em sits at the bottom of a `1.5 em` line box. */
    private fun atlasInkTop(em: Float) = em * (MessageLayout.LINE_PITCH - 1f)

    private class Case(
        val tag: String,
        val s: Float,
        val stageBottom: Float,
        val frameLeft: Float,
        val frameTop: Float,
        val frameRight: Float,
        val pad: Float,
        val textTop: Float,
        val nameEm: Float,
    )

    private fun portraitCases(): List<Case> {
        val out = ArrayList<Case>()
        for (w in widths) for (aspect in listOf(1.8f, 2.22f)) for (t in scales) for (lang in listOf(DisplayLang.BOTH, DisplayLang.CN)) {
            val g = GameLayout.compute(w, w * aspect, ScreenInsets.of(36f, 24f, 0f, 0f).top.value, 24f, t, lang, hasChoices = false, hasLine = true)
            val f = g.advFrame!!
            for (d in densities) {
                val s = DeckLayout.DECK_SCALE * d
                val pad = MessageLayout.ADV_PAD * s
                val top = f.top * d
                out += Case(
                    "w=$w h=${w * aspect} t=$t d=$d $lang", s, g.stage.bottom * d, f.left * d, top, f.right * d, pad,
                    DeckText.textTop(top, pad), MessageLayout.NAME_PX * s * t,
                )
            }
        }
        return out
    }

    private fun tabFor(c: Case, namesWidth: Float = 4f * c.nameEm) =
        NamePlate.tab(c.frameLeft, c.frameTop, c.textTop, c.pad, c.nameEm, namesWidth, c.s, portrait = true)

    @Test
    fun portraitTabStraddlesFrameTopBetweenStageAndFirstLine() {
        for (c in portraitCases()) {
            val tab = tabFor(c)
            assertTrue(tab.top > 0f, c.tag)
            assertTrue(tab.top >= c.stageBottom + NamePlate.STAGE_CLEAR * c.s - 1e-2f, "never touches the 16:9 stage: ${c.tag} ${tab.top} vs ${c.stageBottom}")
            assertTrue(tab.bottom <= c.textTop - NamePlate.TEXT_CLEAR * c.s + 1e-2f, "clear of textTop: ${c.tag}")
            assertTrue(tab.top < c.frameTop && tab.bottom > c.frameTop, "tab sits on the frame top border: ${c.tag}")
            assertEquals(c.nameEm * NamePlate.PORTRAIT_TAB_EM, tab.height, 1e-2f, "full 1.4 em tab for 70..150 %: ${c.tag}")
            assertEquals(c.frameLeft + c.pad, tab.left, 1e-3f)
        }
    }

    @Test
    fun cnAndJpNameGlyphsAreCentredInsideTheTab() {
        for (c in portraitCases()) {
            val tab = tabFor(c)
            for (em in listOf(c.nameEm, c.nameEm * 0.75f)) {
                val y = NamePlate.drawY(tab, em, atlasInkTop(em))
                val inkTop = y + atlasInkTop(em)
                val inkBottom = inkTop + em
                assertTrue(inkTop >= tab.top - 1e-3f && inkBottom <= tab.bottom + 1e-3f, "glyph em inside the tab: ${c.tag} em=$em")
                assertEquals(inkTop - tab.top, tab.bottom - inkBottom, 1e-2f, "vertically centred: ${c.tag} em=$em")
                assertTrue(inkBottom < c.textTop, "glyphs above the first dialogue line: ${c.tag}")
            }
        }
    }

    @Test
    fun v149FormulaDroppedTheNameBelowTheTab() {
        // v1.4.9 at 100 % on PKX110 (density 3.5): line box centred → glyph em overflowed the tab
        // bottom by 0.15 em and started only 0.175 em above the frame border (stroke cut through it).
        val s = DeckLayout.DECK_SCALE * 3.5f
        val em = MessageLayout.NAME_PX * s
        val frameTop = 900f
        val nh = em * 1.5f
        val ny = frameTop - nh * 0.55f
        val oldInkTop = ny + (nh - em * 1.2f) / 2f + atlasInkTop(em)
        assertTrue(oldInkTop + em > ny + nh + 0.1f * em, "old glyph bottom past the tab bottom")
        assertTrue(oldInkTop < frameTop && oldInkTop + em > frameTop, "old border crossed the glyphs")
        // Fixed: same em centred in the tab.
        val pad = MessageLayout.ADV_PAD * s
        val tab = NamePlate.tab(40f, frameTop, DeckText.textTop(frameTop, pad), pad, em, 2 * em, s, portrait = true)
        val inkTop = NamePlate.inkTop(tab, em)
        assertEquals(tab.top + tab.height / 2f, inkTop + em / 2f, 1e-3f)
    }

    @Test
    fun landscapeTabRectIsUnchanged() {
        for (h in listOf(360f, 412f, 720f, 1080f)) for (t in scales) {
            val stageScale = h / 720f
            val em = MessageLayout.NAME_PX * stageScale * t
            val pad = MessageLayout.ADV_PAD * stageScale
            val top = h * MessageLayout.advTop(t, true)
            val left = 1280f * stageScale * MessageLayout.ADV_LEFT
            val namesW = 3f * em + NamePlate.NAME_GAP * stageScale
            val tab = NamePlate.tab(left, top, DeckText.textTop(top, pad), pad, em, namesW, stageScale, portrait = false)
            // v1.4.9 landscape rect: (left + pad, top − 0.55·1.5·em, namesW + 2·pad, 1.5·em).
            val nh = em * 1.5f
            val old = Rect(left + pad, top - nh * 0.55f, left + pad + namesW + pad * 2, top - nh * 0.55f + nh)
            assertEquals(old.left, tab.left, 1e-3f)
            assertEquals(old.top, tab.top, 1e-3f)
            assertEquals(old.right, tab.right, 1e-3f)
            assertEquals(old.bottom, tab.bottom, 1e-3f)
        }
    }

    /** Rim pills of a voiced JP line on the portrait deck of [c]. */
    private fun pillsFor(c: Case, shortAi: Boolean = false): AdvPills.Layout {
        val d = c.s / DeckLayout.DECK_SCALE
        return AdvPills.layout(c.frameRight - c.pad, c.frameTop, c.textTop, c.s, showAi = true, showVoice = true, shortAi, 48f * d, c.frameRight + 16f * d)
    }

    @Test
    fun rimPillsClearStageAndTextAndNeverOverlapTheNameTab() {
        for (c in portraitCases()) {
            val pills = pillsFor(c)
            for (pill in listOfNotNull(pills.aiPill, pills.voicePill)) {
                assertTrue(pill.top >= c.stageBottom, "pill below the stage: ${c.tag}")
                assertTrue(pill.bottom <= c.textTop, "pill above the first line: ${c.tag}")
            }
            // A typical 2 + 2 glyph bilingual name (亲志 / 親志) stays full size with the full AI label.
            val parts = listOf(2f * c.nameEm, 2f * 0.75f * c.nameEm)
            val fit = AdvPills.fitNameplate(c.frameLeft + c.pad, parts, c.pad, c.s, c.frameRight - c.pad, showAi = true, showVoice = true)
            assertEquals(1f, fit.glyphScale, c.tag)
            val nw = AdvPills.nameplateWidth(parts.sum() * fit.glyphScale, fit.parts, c.pad, c.s)
            val tab = tabFor(c, nw - 2f * c.pad)
            assertTrue(tab.right < pillsFor(c, fit.shortAi).pillsLeft, "tab and pills side by side: ${c.tag}")
            // Touch targets: ≥ 48 dp and above the first text line.
            val d = c.s / DeckLayout.DECK_SCALE
            for (r in listOfNotNull(pills.aiTouch, pills.voiceTouch)) {
                assertTrue(r.width >= 48f * d - 1e-3f && r.height >= 48f * d - 1e-3f, c.tag)
                assertTrue(r.bottom <= c.textTop, c.tag)
            }
            assertTrue(pills.voiceTouch!!.contains(pills.voicePill!!.center), c.tag)
        }
    }

    @Test
    fun longBilingualSpeakerFitsLeftOfTheRimPillsAndStaysCentred() {
        // 雪菜的母亲＆孝宏 / 雪菜の母＆孝宏 (8 CN + 7 JP glyphs) at 100–150 % with ✨ + 🔊, on 360 / 412 dp.
        for (w in listOf(360f, 412f)) for (t in listOf(1f, 1.25f, 1.5f)) for (d in densities) {
            val g = GameLayout.compute(w, w * 2.22f, 36f, 24f, t, DisplayLang.BOTH, hasChoices = false, hasLine = true)
            val f = g.advFrame!!
            val s = DeckLayout.DECK_SCALE * d
            val pad = MessageLayout.ADV_PAD * s
            val left = f.left * d
            val right = f.right * d
            val top = f.top * d
            val textTop = DeckText.textTop(top, pad)
            val em = MessageLayout.NAME_PX * s * t
            val parts = listOf(8f * em, 7f * 0.75f * em)
            for (voiced in listOf(true, false)) {
                val fit = AdvPills.fitNameplate(left + pad, parts, pad, s, right - pad, showAi = true, showVoice = voiced)
                val pills = AdvPills.layout(right - pad, top, textTop, s, true, voiced, fit.shortAi, 48f * d, right + 16f * d)
                val nw = AdvPills.nameplateWidth(parts.take(fit.parts).sum() * fit.glyphScale, fit.parts, pad, s)
                val tab = NamePlate.tab(left, top, textTop, pad, em, nw - 2f * pad, s, portrait = true)
                val tag = "w=$w t=$t d=$d voiced=$voiced fit=$fit"
                assertTrue(tab.right + AdvPills.NAME_GAP * s <= pills.pillsLeft + 1e-2f, "clear of the pills: $tag")
                // Shrunk glyphs stay vertically centred in the unchanged-height tab, inside it.
                val cn = em * fit.glyphScale
                val inkTop = NamePlate.inkTop(tab, cn)
                assertEquals(tab.center.y, inkTop + cn / 2f, 1e-2f, tag)
                assertTrue(inkTop >= tab.top - 1e-3f && inkTop + cn <= tab.bottom + 1e-3f, tag)
                assertTrue(tab.bottom <= textTop - NamePlate.TEXT_CLEAR * s + 1e-2f, tag)
            }
        }
    }

    @Test
    fun portraitTabClampsInsteadOfTouchingStageOrText() {
        // Far beyond 150 %: the tab shrinks inside the gap rather than touching the stage or text.
        val s = DeckLayout.DECK_SCALE
        val pad = MessageLayout.ADV_PAD * s
        val frameTop = 300f
        val textTop = DeckText.textTop(frameTop, pad)
        val stageBottom = frameTop - DeckLayout.DECK_GAP_DP
        val tab = NamePlate.tab(16f, frameTop, textTop, pad, MessageLayout.NAME_PX * s * 2f, 100f, s, portrait = true)
        assertTrue(tab.height < MessageLayout.NAME_PX * s * 2f * NamePlate.PORTRAIT_TAB_EM, "shrunk")
        assertTrue(tab.top >= stageBottom + NamePlate.STAGE_CLEAR * s - 1e-3f)
        assertTrue(tab.bottom <= textTop - NamePlate.TEXT_CLEAR * s + 1e-3f)
    }

    @Test
    fun atlasPainterInkTopMatchesLayoutConvention() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val painter = AtlasTextPainter(renderer)
        val rich = RichText(listOf(TextItem.Run(intArrayOf(0x88A4, 0x8E75))))
        for (size in listOf(14.3f, 25f, 50f)) {
            val layout = renderer.layout(rich, TextLang.CN, 1000f, size)
            assertEquals(layout.glyphs.first().top, painter.glyphInkTop(size), 1e-3f)
            assertEquals(size * (renderer.lineSpacing - 1f), painter.glyphInkTop(size), 1e-3f)
        }
    }
}
