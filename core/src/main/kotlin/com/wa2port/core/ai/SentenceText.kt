package com.wa2port.core.ai

import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang

/** One script-provided furigana reading over [span] of the sentence. */
data class ScriptRuby(val span: CharSpan, val reading: String)

/**
 * Plain Japanese sentence for the AI plus the glyph-index → char-offset map used by tap-to-analyze.
 *
 * Glyph indices follow [RichText.baseCodes] order (Run codes + Ruby base codes; readings excluded),
 * which is what the renderer's `PlacedGlyph.index` counts. [glyphCharStart] has `glyphCount + 1`
 * entries; the last one equals `text.length`.
 */
class JpSentence(
    val text: String,
    val glyphCharStart: IntArray,
    /** Script furigana (`<R本気|マジ>` gikun readings), authoritative; spans index into [text]. */
    val scriptRuby: List<ScriptRuby> = emptyList(),
) {
    val glyphCount: Int get() = glyphCharStart.size - 1

    /**
     * UTF-16 offset in [text] of [glyph] (clamped to `0..text.length`). A glyph whose code produced
     * no character (control code) maps to the next character's offset.
     */
    fun charIndexOfGlyph(glyph: Int): Int {
        if (glyphCharStart.isEmpty()) return 0
        val g = glyph.coerceIn(0, glyphCharStart.size - 1)
        return glyphCharStart[g].coerceIn(0, text.length)
    }

    companion object {
        /**
         * Walks [rich] in [RichText.baseCodes] order: Run codes and Ruby base codes are decoded with
         * [BilingualTextDecoder.jpChar] and appended; Ruby readings are skipped; NewLine / PageBreak /
         * Wait produce no character (Japanese needs no separator between script lines).
         */
        fun fromRich(rich: RichText, decoder: BilingualTextDecoder): JpSentence {
            val sb = StringBuilder()
            val starts = ArrayList<Int>()
            val ruby = ArrayList<ScriptRuby>()
            fun add(code: Int) {
                starts += sb.length
                sb.append(decoder.jpChar(code))
            }
            for (item in rich.items) when (item) {
                is TextItem.Run -> item.codes.forEach(::add)
                is TextItem.Ruby -> {
                    val from = sb.length
                    item.base.forEach(::add)
                    val reading = item.reading.joinToString("") { decoder.jpChar(it) }.trim()
                    if (sb.length > from && reading.isNotEmpty()) ruby += ScriptRuby(CharSpan(from, sb.length), reading)
                }
                else -> {}
            }
            starts += sb.length
            return JpSentence(sb.toString(), starts.toIntArray(), ruby)
        }

        /** Plain-string sentence (tests / previews): one glyph per UTF-16 unit. */
        fun ofText(text: String): JpSentence = JpSentence(text, IntArray(text.length + 1) { it })

        /** CK-GAL CN line: plain text with `\n` → space, trimmed; null when empty. */
        fun officialCn(cn: RichText?, decoder: BilingualTextDecoder): String? {
            if (cn == null || cn.isEmpty) return null
            return decoder.toPlain(cn, TextLang.CN).replace('\n', ' ').replace(Regex(" {2,}"), " ").trim().ifEmpty { null }
        }

        /** Speaker name in [lang], trimmed; null when empty. */
        fun speaker(rich: RichText?, decoder: BilingualTextDecoder, lang: TextLang): String? {
            if (rich == null || rich.isEmpty) return null
            return decoder.toPlain(rich, lang).replace('\n', ' ').trim().ifEmpty { null }
        }
    }
}
