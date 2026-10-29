package com.wa2port.android.render

import androidx.compose.ui.geometry.Offset
import com.wa2port.android.bridge.AtlasTextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.game.AdvHitBox
import com.wa2port.android.game.nameBlocks
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.ScenarioTextParser
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.Charset

class GlyphAtlasTextKinsokuTest {
    @TempDir
    lateinit var tempDir: File

    private val ms932: Charset = Charset.forName("MS932")

    private fun parseJp(text: String): RichText = ScenarioTextParser.parse(text.toByteArray(ms932))

    /** Groups laid-out glyphs by their vertical `top` coordinate (one list of codes per line). */
    private fun linesOf(layout: GlyphAtlasText.TextLayout): List<List<Int>> {
        val map = LinkedHashMap<Float, MutableList<Int>>()
        for (g in layout.glyphs) {
            map.getOrPut(g.top) { ArrayList() }.add(g.code)
        }
        return map.values.toList()
    }

    @Test
    fun noLineStartContainsSmallKanaIterationMarksAndClosers() {
        for (ch in "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶー々ゝゞヽヾ、。，．」』）】〕〉》…‥！？") {
            val bytes = ch.toString().toByteArray(ms932)
            val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            assertTrue(code in GlyphAtlasText.NO_LINE_START, "Expected $ch (0x${code.toString(16)}) in NO_LINE_START")
        }
    }

    @Test
    fun smallKanaAtWrapBoundaryPullsPrecedingCharToNextLine() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // 4 full-width chars per line (maxWidth = 120, sizePx = 30).
        // Without kinsoku backoff, "あいうえっお" would wrap as ["あいうえ", "っお"] starting line 2 with 'っ'.
        // With 1-char backoff, 'え' is pulled down so line 1 is "あいう" (3) and line 2 is "えっお" (3).
        val rich = parseJp("あいうえっお")
        val layout = renderer.layout(rich, TextLang.JP, maxWidth = 120f, sizePx = 30f)
        val lines = linesOf(layout)
        assertEquals(2, lines.size)
        assertEquals(listOf(3, 3), lines.map { it.size })
        assertFalse(lines[1].first() in GlyphAtlasText.NO_LINE_START)
    }

    @Test
    fun katakanaSmallAndIterationMarkPullPrecedingCharToNextLine() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // 'ッ' (0x8362) and '々' (0x8158) at index 4 on a 4-char column:
        for (sample in listOf("アイウエッオ", "あいうえ々お")) {
            val layout = renderer.layout(parseJp(sample), TextLang.JP, maxWidth = 120f, sizePx = 30f)
            val lines = linesOf(layout)
            assertEquals(listOf(3, 3), lines.map { it.size }, "Sample: $sample")
            assertFalse(lines[1].first() in GlyphAtlasText.NO_LINE_START, "Sample: $sample")
        }
    }

    @Test
    fun singleClosingPunctuationStillHangsWithoutBackoff() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // "あいうえ。お": '。' is in HANGING, so line 1 keeps all 4 chars + hanging '。' (5 glyphs),
        // and line 2 starts cleanly with 'お' (1 glyph).
        val layout = renderer.layout(parseJp("あいうえ。お"), TextLang.JP, maxWidth = 120f, sizePx = 30f)
        val lines = linesOf(layout)
        assertEquals(listOf(5, 1), lines.map { it.size })
    }

    @Test
    fun doubleClosingPunctuationPullsPrecedingCharSoSecondDoesNotStartLine() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // "あいうえ。」お": '。' would consume the single hang slot and leave '」' at the start of line 2.
        // Backoff pulls 'え' to line 2 so line 1 has "あいう" (3) and line 2 has "え。」お" (4).
        val layout = renderer.layout(parseJp("あいうえ。」お"), TextLang.JP, maxWidth = 120f, sizePx = 30f)
        val lines = linesOf(layout)
        assertEquals(listOf(3, 4), lines.map { it.size })
        assertFalse(lines[1].first() in GlyphAtlasText.NO_LINE_START)
    }

    @Test
    fun nameplateDeduplicatesIdenticalSpeakerNamesInBothMode() {
        val takeya = parseJp("武也")
        val lineSame = DialogueLine(
            ref = LineRef("1001", 1, 1),
            jp = parseJp("テスト"),
            cn = parseJp("テスト"),
            speakerJp = takeya,
            speakerCn = takeya,
            read = false,
        )
        val blocksSame = nameBlocks(lineSame, DisplayLang.BOTH, 1f)
        assertEquals(1, blocksSame.size)

        val lineDiff = lineSame.copy(
            speakerCn = parseJp("冬馬"),
            speakerJp = parseJp("かずさ"),
        )
        val blocksDiff = nameBlocks(lineDiff, DisplayLang.BOTH, 1f)
        assertEquals(2, blocksDiff.size)
    }

    @Test
    fun tieredReflowPoliciesKeepSentenceOrJoinAll() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val colW = MessageLayout.advColumnWidth(1f)
        val sizePx = MessageLayout.TWO_COL_PX
        val cap = MessageLayout.lineCap(colW, sizePx, MessageLayout.TWO_COL_MAX_CHARS)
        // Mid-sentence literal `\n` (after 'は') vs sentence-end literal `。\n`:
        // S1 = 10 + \n + 4 + 。\n (15 glyphs), S2 = 24 + 。\n (25 glyphs), S3 = 24 + 。 (25 glyphs) = 65 glyphs total.
        val script = "あ".repeat(9) + "は\\n" + "い".repeat(4) + "。\\n" +
            "う".repeat(24) + "。\\n" +
            "え".repeat(24) + "。"
        val rich = parseJp(script)

        val keepAll = renderer.layout(rich, TextLang.JP, colW, sizePx, cap, Reflow.KEEP_ALL)
        val sentence = renderer.layout(rich, TextLang.JP, colW, sizePx, cap, Reflow.SENTENCE)
        val joinAll = renderer.layout(rich, TextLang.JP, colW, sizePx, cap, Reflow.JOIN_ALL)

        // KEEP_ALL keeps both mid-sentence '\n' and the two '。\n' breaks -> 1+1 + 2 + 2 = 6 lines.
        assertEquals(6, keepAll.lineCount)
        // SENTENCE joins the mid-sentence '\n' (15 chars -> 1 line) and keeps the two '。\n' -> 1 + 2 + 2 = 5 lines.
        assertEquals(5, sentence.lineCount)
        // JOIN_ALL soft-joins all '\n' so all 65 glyphs fit in 4 lines at constant 28 px!
        assertEquals(4, joinAll.lineCount)
        assertEquals(sizePx, joinAll.sizePx)
        assertTrue(joinAll.height <= MessageLayout.advTextAvail(MessageLayout.advTop(1f, twoCol = true)))
    }

    @Test
    fun revealedHeightTracksLineProgress() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        // 5 lines × 10 chars on a 10-char column (sizePx = 30, pitch = 45).
        val rich = parseJp("あ".repeat(50))
        val layout = renderer.layout(rich, TextLang.JP, maxWidth = 300f, sizePx = 30f, maxChars = 10f, reflow = Reflow.JOIN_ALL)
        assertEquals(5, layout.lineCount)
        assertEquals(0f, layout.revealedHeight(0))
        assertEquals(45f, layout.revealedHeight(1), 1e-3f)
        assertEquals(45f, layout.revealedHeight(10), 1e-3f)
        assertEquals(90f, layout.revealedHeight(11), 1e-3f)
        assertEquals(180f, layout.revealedHeight(40), 1e-3f)
        assertEquals(225f, layout.revealedHeight(41), 1e-3f)
        assertEquals(225f, layout.revealedHeight(50), 1e-3f)
    }

    @Test
    fun advHitBoxScrollAndClippedGlyphHitTesting() {
        val renderer = GlyphAtlasText(Wa2Assets(tempDir))
        val painter = AtlasTextPainter(renderer)
        // 5 lines of 10 distinct characters per line (size = 30, pitch = 45, total height = 225, 4-line avail = 180, maxScroll = 45).
        val rich = parseJp("あ".repeat(10) + "い".repeat(10) + "う".repeat(10) + "え".repeat(10) + "お".repeat(10))
        val hit = AdvHitBox()
        val line1 = DialogueLine(LineRef("1001", 1, 1), rich, rich, null, null, false)
        hit.syncLine(line1, DisplayLang.BOTH, 1.45f)

        // Typewriter auto-follow stays at 0 on lines 1..4, then glides down when line 5 reveals.
        assertEquals(0f, hit.resolveScrollY(maxScroll = 45f, avail = 180f, revealedHeight = 180f, waiting = false, stageScale = 1f))
        val autoStep = hit.resolveScrollY(maxScroll = 45f, avail = 180f, revealedHeight = 225f, waiting = false, stageScale = 1f)
        assertTrue(autoStep > 0f && autoStep <= 45f)

        // Manual touch drag overrides auto-follow and clamps to 0..maxScrollPx.
        hit.scrollBy(-100f) // finger moves up -> scroll down to bottom (45f)
        assertTrue(hit.userScrolled)
        assertEquals(45f, hit.scrollY, 1e-3f)

        // At scrollY = 45f, textTop = 440f -> drawn y = 395f, clipTop = 440f, clipBottom = 620f.
        // Line 0 (indices 0..9, screen y 395..440) is scrolled above clipTop and must NOT hit;
        // Line 4 (indices 40..49, screen y 575..620) is visible at the bottom and MUST hit index 40!
        hit.jp = AdvHitBox.JpBox(
            rich = rich,
            x = 600f,
            y = 440f - hit.scrollY,
            width = 300f,
            sizePx = 30f,
            maxChars = 10f,
            reflow = Reflow.JOIN_ALL,
            visibleGlyphs = 50,
            clipTop = 440f,
            clipBottom = 620f,
        )
        assertEquals(-1, hit.glyphAt(painter, Offset(615f, 425f)), "Scrolled-out Line 0 above clipTop must not hit")
        assertEquals(10, hit.glyphAt(painter, Offset(615f, 470f)), "Top visible line at scrollY=45 must hit Line 1 (glyph 10)")
        assertEquals(40, hit.glyphAt(painter, Offset(615f, 605f)), "Bottom visible line at scrollY=45 must hit Line 4 (glyph 40)")

        // Switching to a new line resets scrollY and userScrolled.
        val line2 = DialogueLine(LineRef("1001", 2, 2), rich, rich, null, null, false)
        hit.syncLine(line2, DisplayLang.BOTH, 1.45f)
        assertEquals(0f, hit.scrollY)
        assertFalse(hit.userScrolled)
    }
}
