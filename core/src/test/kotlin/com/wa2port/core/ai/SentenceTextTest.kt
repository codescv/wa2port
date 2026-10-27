package com.wa2port.core.ai

import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.charset.Charset

class SentenceTextTest {
    private val decoder = BilingualTextDecoder()

    /** CP932 codes of [s] as the engine stores them (double-byte → `lead shl 8 or trail`). */
    private fun sjis(s: String): IntArray {
        val b = s.toByteArray(Charset.forName("MS932"))
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val lead = b[i].toInt() and 0xFF
            if (lead in 0x81..0x9F || lead in 0xE0..0xFC) {
                out += (lead shl 8) or (b[i + 1].toInt() and 0xFF)
                i += 2
            } else {
                out += lead
                i++
            }
        }
        return out.toIntArray()
    }

    @Test
    fun rubyBaseIncludedReadingExcluded() {
        val rich = RichText(
            listOf(
                TextItem.Run(sjis("「")),
                TextItem.Ruby(sjis("本気"), sjis("マジ")),
                TextItem.Run(sjis("か？」")),
            ),
        )
        val s = JpSentence.fromRich(rich, decoder)
        assertEquals("「本気か？」", s.text)
        assertEquals(rich.baseCodes().size, s.glyphCount)
        assertEquals(rich.baseCodes().size + 1, s.glyphCharStart.size)
        assertEquals(s.text.length, s.glyphCharStart.last())
        assertEquals(listOf(ScriptRuby(CharSpan(1, 3), "マジ")), s.scriptRuby)
        assertEquals(1, s.charIndexOfGlyph(1))
        assertEquals(3, s.charIndexOfGlyph(3))
    }

    @Test
    fun newlinesWaitsAndControlCodes() {
        val rich = RichText(
            listOf(
                TextItem.Run(sjis("雪が")),
                TextItem.NewLine,
                TextItem.Wait(10, false),
                TextItem.Run(intArrayOf(0x0A) + sjis("降る")), // control code keeps a glyph slot, no char
                TextItem.PageBreak,
                TextItem.Run(sjis("ね")),
            ),
        )
        val s = JpSentence.fromRich(rich, decoder)
        assertEquals("雪が降るね", s.text)
        assertEquals(6, s.glyphCount)
        assertEquals(listOf(0, 1, 2, 2, 3, 4, 5), s.glyphCharStart.toList())
        assertEquals(2, s.charIndexOfGlyph(2)) // control glyph → next char (降)
        assertEquals(4, s.charIndexOfGlyph(5))
        assertEquals(5, s.charIndexOfGlyph(99)) // clamped
        assertEquals(0, s.charIndexOfGlyph(-3))
    }

    @Test
    fun officialCnAndSpeaker() {
        assertNull(JpSentence.officialCn(null, decoder))
        assertNull(JpSentence.officialCn(RichText.EMPTY, decoder))
        val sp = RichText(listOf(TextItem.Run(sjis("雪菜"))))
        assertEquals("雪菜", JpSentence.speaker(sp, decoder, TextLang.JP))
        assertNull(JpSentence.speaker(RichText.EMPTY, decoder, TextLang.JP))
        val t = JpSentence.ofText("あいう")
        assertEquals(3, t.glyphCount)
        assertEquals(2, t.charIndexOfGlyph(2))
    }
}
