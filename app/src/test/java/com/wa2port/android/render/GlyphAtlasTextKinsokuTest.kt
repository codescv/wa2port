package com.wa2port.android.render

import com.wa2port.android.data.DisplayLang
import com.wa2port.android.game.nameBlocks
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
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
}
