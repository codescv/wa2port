package com.wa2port.android.game

import android.graphics.Canvas
import androidx.compose.ui.geometry.Offset
import com.wa2port.android.bridge.TextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.ui.ScreenInsets
import com.wa2port.core.text.DeckLayout
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import kotlin.math.floor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Portrait deck hit-testing: content-box px, stacked JP block, 🔊 / ✨ touch targets, scroll state (spec §2.4). */
class AdvHitBoxPortraitTest {
    /** Monospace fake: glyph `i` of line `L` at (`i·em`, `L·1.5·em`), [cap] glyphs per line. */
    private class FakePainter : TextPainter {
        var lastLocal: Offset? = null

        override fun glyphCount(rich: RichText): Int = rich.baseCodes().size
        override fun measureHeight(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float, reflow: Reflow): Float =
            kotlin.math.ceil(glyphCount(rich) / maxChars.coerceAtLeast(1f)) * MessageLayout.LINE_PITCH * sizePx

        override fun draw(
            canvas: Canvas, rich: RichText, lang: TextLang, x: Float, y: Float, maxWidth: Float, sizePx: Float, color: Int,
            visibleGlyphs: Int, maxChars: Float, reflow: Reflow,
        ): Float = measureHeight(rich, lang, maxWidth, sizePx, maxChars, reflow)

        override fun glyphIndexAt(
            rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float, reflow: Reflow,
            localX: Float, localY: Float, visibleGlyphs: Int,
        ): Int {
            lastLocal = Offset(localX, localY)
            if (localX < 0f || localY < 0f) return -1
            val line = floor(localY / (MessageLayout.LINE_PITCH * sizePx)).toInt()
            val col = floor(localX / sizePx).toInt().coerceAtMost(maxChars.toInt() - 1)
            val idx = line * maxChars.toInt() + col
            return if (idx < minOf(glyphCount(rich), visibleGlyphs)) idx else -1
        }
    }

    private val jp = RichText(listOf(TextItem.Run(IntArray(30) { 0x82A0 + it })))

    /** Device B @100 % (density 1 → px == dp), bilingual stacked deck. */
    private val geometry = GameLayout.compute(
        412f, 915f, ScreenInsets.of(36f, 24f, 0f, 0f).top.value, 24f, 1f, DisplayLang.BOTH, hasChoices = false, hasLine = true,
    )
    private val frame = geometry.advFrame!!.let { AdvFrame(it.left, it.top, it.right, it.bottom, DeckLayout.DECK_SCALE, geometry.bothMode) }

    @Test
    fun workedExampleOnPkx110() {
        assertEquals(251.75f, frame.top, 0.01f)
        assertEquals(DeckLayout.BothMode.STACKED, frame.bothMode)
        val pad = MessageLayout.ADV_PAD * frame.scale
        val em = MessageLayout.TWO_COL_PX * frame.scale
        val textTop = DeckText.textTop(frame.top, pad)
        assertEquals(267.49f, textTop, 0.05f)
        // CN block: 2 lines; JP block below the 18 dp stacked gap.
        val hCn = 2 * MessageLayout.LINE_PITCH * em
        assertEquals(48.07f, hCn, 0.05f)
        val jpTop = DeckText.jpTop(textTop, hCn, DeckLayout.STACKED_GAP_DP, scrollY = 0f)
        assertEquals(333.56f, jpTop, 0.05f)
        val width = frame.width - 2 * pad
        val cap = DeckLayout.lineCap(width, em)
        assertEquals(21f, cap)

        val hit = AdvHitBox()
        hit.minTolPx = 12f
        hit.jp = AdvHitBox.JpBox(jp, frame.left + pad, jpTop, width, em, cap, Reflow.SENTENCE, Int.MAX_VALUE, textTop - 3f, frame.bottom)
        val painter = FakePainter()
        // Tap (60, 420) dp on screen → content box (60, 336): the content box starts at stageTop = 84.
        assertEquals(84f, geometry.content.top, 0.01f)
        val p = Offset(60f, 420f - geometry.content.top)
        assertEquals(1, hit.glyphAt(painter, p))
        assertEquals(31.41f, painter.lastLocal!!.x, 0.05f)
        assertEquals(2.44f, painter.lastLocal!!.y, 0.05f)
        // Scrolled by 30 px: the same screen tap now lands on line 1 of the JP block (21 + 1).
        hit.jp = AdvHitBox.JpBox(jp, frame.left + pad, DeckText.jpTop(textTop, hCn, 18f, 30f), width, em, cap, Reflow.SENTENCE, Int.MAX_VALUE, textTop, frame.bottom)
        assertEquals(22, hit.glyphAt(painter, p))
        // Taps above the clip (on the stage) never hit the JP block.
        assertEquals(-1, hit.glyphAt(painter, Offset(60f, 100f)))
    }

    @Test
    fun stackedRevealedHeightFollowsJpOnceItStarts() {
        assertEquals(48f + 18f + 24f, DeckText.stackedRevealedHeight(48f, 18f, revealedCn = 30f, revealedJp = 24f, visibleJp = 5))
        assertEquals(30f, DeckText.stackedRevealedHeight(48f, 18f, revealedCn = 30f, revealedJp = 0f, visibleJp = 0))
    }

    @Test
    fun voiceTouchRectIsAtLeast48dpAndAboveTheFirstLine() {
        for (density in listOf(1f, 2f, 2.625f, 3.5f)) for (t in listOf(0.7f, 1f, 1.5f)) {
            val s = DeckLayout.DECK_SCALE * density
            val left = frame.left * density
            val right = frame.right * density
            val top = frame.top * density
            val pad = MessageLayout.ADV_PAD * s
            val textTop = DeckText.textTop(top, pad)
            val r = AdvPills.layout(right - pad, top, textTop, s, showAi = false, showVoice = true, shortAi = false, 48f * density, 412f * density)
                .voiceTouch!!
            assertTrue(r.width >= 48f * density - 1e-3f, "w density=$density")
            assertTrue(r.height >= 48f * density - 1e-3f, "h density=$density")
            assertTrue(r.bottom <= textTop, "never covers the first text line (density=$density t=$t)")
            assertTrue(r.left >= 0f && r.right <= 412f * density + 1e-3f)
            assertTrue(r.left > left, "right half of the deck")
        }
    }

    @Test
    fun userScrolledResetsWhenNothingToScroll() {
        val hit = AdvHitBox()
        hit.resolveScrollY(maxScroll = 100f, avail = 200f, revealedHeight = 0f, waiting = true, stageScale = 1f)
        hit.scrollBy(-40f)
        assertTrue(hit.userScrolled)
        assertEquals(40f, hit.scrollY, 1e-3f)
        // e.g. rotated to a taller deck: the line now fits → scroll 0, auto-follow resumes.
        assertEquals(0f, hit.resolveScrollY(maxScroll = 0f, avail = 400f, revealedHeight = 0f, waiting = true, stageScale = 1f))
        assertFalse(hit.userScrolled)
        assertEquals(0f, hit.scrollY)
    }

    @Test
    fun clearGeometryLeavesFrameMode() {
        val hit = AdvHitBox()
        hit.frameMode = true
        hit.voiceRect = androidx.compose.ui.geometry.Rect(0f, 0f, 48f, 48f)
        hit.aiRect = androidx.compose.ui.geometry.Rect(0f, 0f, 48f, 48f)
        hit.clearGeometry()
        assertFalse(hit.frameMode)
        assertEquals(null, hit.voiceRect)
        assertEquals(null, hit.aiRect)
    }

    @Test
    fun waitMarkStaysInsideTheFrame() {
        val pad = MessageLayout.ADV_PAD * frame.scale
        assertEquals(310f, DeckText.waitMarkY(300f, 10f, frame.bottom, pad), 1e-3f)
        assertEquals(frame.bottom - pad * 0.36f, DeckText.waitMarkY(frame.bottom, 10f, frame.bottom, pad), 1e-3f)
    }
}
