package com.wa2port.android.render

import androidx.compose.ui.geometry.Offset
import com.wa2port.android.bridge.AtlasTextPainter
import com.wa2port.android.game.AdvHitBox
import com.wa2port.core.ai.PackRuby
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.FuriganaOverlay
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.ScenarioTextParser
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset

class GlyphAtlasTextFuriganaTest {
    @TempDir
    lateinit var tempDir: File

    private val ms932: Charset = Charset.forName("MS932")
    private val decoder = BilingualTextDecoder()

    private fun parseJp(text: String): RichText = ScenarioTextParser.parse(text.toByteArray(ms932))

    @Test
    fun autoRubyGroupsDistinguishAutoFromScriptRubyAndHitTestZeroShift() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val painter = AtlasTextPainter(renderer)
        // Script ruby on 本気 (indices 0..1), kana は (index 2), auto ruby on 今日 (indices 3..4), kana だ (index 5).
        val raw = parseJp("<R本気|マジ>は今日だ")
        val enriched = FuriganaOverlay.applySpans(
            raw,
            listOf(
                PackRuby(0, 2, "ほんき"), // must lose to script ruby
                PackRuby(3, 5, "きょう"), // auto ruby on 今日
            ),
            decoder,
        )
        assertTrue(raw.baseCodes().contentEquals(enriched.baseCodes()))

        val layout = renderer.layout(enriched, TextLang.JP, maxWidth = 300f, sizePx = 30f)
        assertEquals(6, layout.glyphCount)
        assertEquals(2, layout.rubyGroups.size)

        // Group 0: script ruby (auto = false) covering glyphs 0..1.
        assertEquals(GlyphAtlasText.RubyGroup(groupId = 0, startGlyph = 0, endGlyph = 2, auto = false), layout.rubyGroups[0])
        // Group 1: auto ruby (auto = true) covering glyphs 3..4.
        assertEquals(GlyphAtlasText.RubyGroup(groupId = 3, startGlyph = 3, endGlyph = 5, auto = true), layout.rubyGroups[1])

        // autoRubyGroupForGlyph returns -1 for script ruby and kana, and 3 for both kanji of 今日.
        assertEquals(-1, layout.autoRubyGroupForGlyph(0))
        assertEquals(-1, layout.autoRubyGroupForGlyph(1))
        assertEquals(-1, layout.autoRubyGroupForGlyph(2))
        assertEquals(3, layout.autoRubyGroupForGlyph(3))
        assertEquals(3, layout.autoRubyGroupForGlyph(4))
        assertEquals(-1, layout.autoRubyGroupForGlyph(5))

        // PlacedRuby flags match script vs auto.
        val scriptRubies = layout.rubies.filter { !it.auto }
        val autoRubies = layout.rubies.filter { it.auto }
        assertEquals(2, scriptRubies.size) // マジ
        assertEquals(3, autoRubies.size) // きょう
        assertTrue(scriptRubies.all { it.groupId == 0 })
        assertTrue(autoRubies.all { it.groupId == 3 })

        // AtlasTextPainter.autoRubyGroupAt hits group 3 on either 今 (x=90..120) or 日 (x=120..150),
        // returns -1 on script ruby 本 (x=0..30) and kana は (x=60..90), and respects visibleGlyphs.
        val y = layout.glyphs[3].top + 10f
        assertEquals(-1, painter.autoRubyGroupAt(enriched, TextLang.JP, 300f, 30f, 0f, Reflow.KEEP_ALL, 15f, y))
        assertEquals(-1, painter.autoRubyGroupAt(enriched, TextLang.JP, 300f, 30f, 0f, Reflow.KEEP_ALL, 75f, y))
        assertEquals(3, painter.autoRubyGroupAt(enriched, TextLang.JP, 300f, 30f, 0f, Reflow.KEEP_ALL, 105f, y))
        assertEquals(3, painter.autoRubyGroupAt(enriched, TextLang.JP, 300f, 30f, 0f, Reflow.KEEP_ALL, 135f, y))
        assertEquals(-1, painter.autoRubyGroupAt(enriched, TextLang.JP, 300f, 30f, 0f, Reflow.KEEP_ALL, 105f, y, visibleGlyphs = 3))

        // AdvHitBox wraps enriched RichText while keeping sourceRich == raw for current-line checks.
        val hit = AdvHitBox()
        hit.jp = AdvHitBox.JpBox(
            rich = enriched,
            x = 100f,
            y = 400f,
            width = 300f,
            sizePx = 30f,
            maxChars = 0f,
            reflow = Reflow.KEEP_ALL,
            visibleGlyphs = 6,
            clipTop = 395f,
            clipBottom = 500f,
            sourceRich = raw,
        )
        assertSame(raw, hit.jp?.sourceRich)
        assertEquals(3, hit.autoRubyGroupAt(painter, Offset(205f, 400f + y)))
        assertEquals(-1, hit.autoRubyGroupAt(painter, Offset(205f, 350f))) // above clipTop

        // Zero layout shift: layout is invariant regardless of TAP reveal state (drawLayout only gates drawing).
        val layoutAgain = renderer.layout(enriched, TextLang.JP, maxWidth = 300f, sizePx = 30f)
        assertEquals(layout.height, layoutAgain.height)
        assertEquals(layout.glyphs.map { it.x to it.top }, layoutAgain.glyphs.map { it.x to it.top })
    }

    @Test
    fun adjacentDenseRubiesNeverCollideOrClipLeftRightEdges() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // Four adjacent 1-kanji words with long readings (each 3-4 kana wide over a single 30px kanji).
        val raw = parseJp("承雪冬寒")
        val enriched = FuriganaOverlay.applySpans(
            raw,
            listOf(
                PackRuby(0, 1, "うけたまわ"), // 5 kana on glyph 0 (left edge!)
                PackRuby(1, 2, "ゆき"),
                PackRuby(2, 3, "ふゆ"),
                PackRuby(3, 4, "さむい"), // right edge when maxWidth == 120f
            ),
            decoder,
        )
        // Generous width: verify left clamping (>= 0) and zero horizontal overlap between consecutive groups.
        val wide = renderer.layout(enriched, TextLang.JP, maxWidth = 300f, sizePx = 30f)
        val byGroupWide = wide.rubies.groupBy { it.groupId }.toSortedMap()
        assertEquals(4, byGroupWide.size)
        var prevRight = Float.NEGATIVE_INFINITY
        for ((_, groupRubies) in byGroupWide) {
            val minX = groupRubies.minOf { it.x }
            val maxX = groupRubies.maxOf { it.x + it.size }
            assertTrue(minX >= 0f, "Ruby must not clip past left edge: minX=$minX")
            assertTrue(maxX <= 300f + 0.01f, "Ruby must not clip past right edge: maxX=$maxX")
            if (prevRight > Float.NEGATIVE_INFINITY) {
                assertTrue(minX >= prevRight - 0.01f, "Adjacent rubies collided: prevRight=$prevRight > minX=$minX")
            }
            prevRight = maxX
        }

        // Extreme tight edge (maxWidth = 120f, 12 total kana across 4 kanji):
        // 2-pass 1D packer uniformly shrinks so ALL 4 readings fit inside [0..120] with ZERO overlap and ZERO clip!
        val ultraTightWidth = 120f
        val ultraTight = renderer.layout(enriched, TextLang.JP, maxWidth = ultraTightWidth, sizePx = 30f)
        assertEquals(1, ultraTight.lineCount)
        val byGroupUltra = ultraTight.rubies.groupBy { it.groupId }.toSortedMap()
        assertEquals(4, byGroupUltra.size)
        var ultraPrevRight = Float.NEGATIVE_INFINITY
        for ((_, groupRubies) in byGroupUltra) {
            val minX = groupRubies.minOf { it.x }
            val maxX = groupRubies.maxOf { it.x + it.size }
            assertTrue(minX >= 0f, "Ultra-tight ruby clipped left: $minX")
            assertTrue(maxX <= ultraTightWidth + 0.01f, "Ultra-tight ruby clipped right: $maxX")
            if (ultraPrevRight > Float.NEGATIVE_INFINITY) {
                assertTrue(minX >= ultraPrevRight - 0.01f, "Ultra-tight rubies collided: prevRight=$ultraPrevRight minX=$minX")
            }
            ultraPrevRight = maxX
        }

        // Right-edge 2-kanji cluster with empty left space (10 kana + 承雪, maxWidth = 360f):
        // Pass 2 pushes 承 left into free space so BOTH keep full 0.45× ruby scale with ZERO collision and ZERO clip!
        val rightEdgeRaw = parseJp("あいうえおかきくけこ承雪")
        val rightEdgeEnriched = FuriganaOverlay.applySpans(
            rightEdgeRaw,
            listOf(PackRuby(10, 11, "うけたまわ"), PackRuby(11, 12, "ゆき")),
            decoder,
        )
        val rightEdge = renderer.layout(rightEdgeEnriched, TextLang.JP, maxWidth = 360f, sizePx = 30f)
        assertEquals(1, rightEdge.lineCount)
        val reGroups = rightEdge.rubies.groupBy { it.groupId }.toSortedMap()
        assertEquals(2, reGroups.size)
        val g10 = reGroups[10]!!
        val g11 = reGroups[11]!!
        assertEquals(30f * renderer.rubyScale, g10.first().size, 0.01f)
        assertEquals(30f * renderer.rubyScale, g11.first().size, 0.01f)
        assertTrue(g10.minOf { it.x } >= 0f)
        assertTrue(g11.maxOf { it.x + it.size } <= 360f + 0.01f)
        assertTrue(
            g11.minOf { it.x } >= g10.maxOf { it.x + it.size } - 0.01f,
            "Right-edge rubies collided: g10Max=${g10.maxOf { it.x + it.size }} g11Min=${g11.minOf { it.x }}",
        )
    }

    @Test
    fun novelPageHitBoxResolvesAutoRubyAcrossMultipleLines() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val painter = AtlasTextPainter(renderer)
        val raw1 = parseJp("今日は雪")
        val enriched1 = FuriganaOverlay.applySpans(raw1, listOf(PackRuby(0, 2, "きょう"), PackRuby(3, 4, "ゆき")), decoder)
        val raw2 = parseJp("明日も冬")
        val enriched2 = FuriganaOverlay.applySpans(raw2, listOf(PackRuby(0, 2, "あした"), PackRuby(3, 4, "ふゆ")), decoder)
        val line1 = DialogueLine(LineRef("1001", 1, 1), raw1, null, null, null, false)
        val line2 = DialogueLine(LineRef("1001", 2, 2), raw2, null, null, null, false)

        val hit = AdvHitBox()
        hit.minTolPx = 12f
        hit.pageJp = listOf(
            AdvHitBox.PageJpBox(line1, AdvHitBox.JpBox(enriched1, 40f, 100f, 300f, 30f, 0f, Reflow.KEEP_ALL, 4, 80f, 400f, raw1)),
            AdvHitBox.PageJpBox(line2, AdvHitBox.JpBox(enriched2, 40f, 170f, 300f, 30f, 0f, Reflow.KEEP_ALL, 4, 80f, 400f, raw2)),
        )

        // Tap 雪 on line 1 (glyph index 3 -> x = 40 + 90..120, y = 100 + 25).
        val hit1 = hit.pageAutoRubyAt(painter, Offset(145f, 125f))
        assertEquals(AdvHitBox.PageRubyHit(line1, groupId = 3, glyphIndex = 3), hit1)

        // Tap 明日 on line 2 (glyph index 1 -> x = 40 + 30..60, y = 170 + 25).
        val hit2 = hit.pageAutoRubyAt(painter, Offset(85f, 195f))
        assertEquals(AdvHitBox.PageRubyHit(line2, groupId = 0, glyphIndex = 1), hit2)

        // Tap kana も on line 2 (glyph index 2 -> x = 40 + 60..90, y = 170 + 25) -> null.
        assertEquals(null, hit.pageAutoRubyAt(painter, Offset(115f, 195f)))

        // Tap outside clipTop -> null.
        assertEquals(null, hit.pageAutoRubyAt(painter, Offset(145f, 70f)))
    }

    @Test
    fun lineWrapResetsRubyCollisionTrackingPerLine() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val raw = parseJp("今年\\n今年")
        val enriched = FuriganaOverlay.applySpans(
            raw,
            listOf(PackRuby(0, 2, "ことし"), PackRuby(2, 4, "ことし")),
            decoder,
        )
        val layout = renderer.layout(enriched, TextLang.JP, maxWidth = 200f, sizePx = 30f)
        assertEquals(2, layout.lineCount)
        val line1Rubies = layout.rubies.filter { it.groupId == 0 }
        val line2Rubies = layout.rubies.filter { it.groupId == 2 }
        assertEquals(line1Rubies.first().x, line2Rubies.first().x, 0.01f)
        assertFalse(line1Rubies.first().top == line2Rubies.first().top)
    }
}
