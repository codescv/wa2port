package com.wa2port.core.text

import com.wa2port.core.archive.Cp932

/** One item of a parsed scenario text token. Codes are raw engine codes (single byte or `lead shl 8 | trail`). */
sealed class TextItem {
    /** Plain glyph run; [size] is the `<Fnn>` font size (0 = default). */
    data class Run(val codes: IntArray, val size: Int = 0, val style: Char = ' ') : TextItem() {
        override fun equals(other: Any?) = other is Run && codes.contentEquals(other.codes) && size == other.size && style == other.style
        override fun hashCode() = codes.contentHashCode() * 31 + size
    }

    /** `<Rbase|reading>` ruby (furigana). */
    data class Ruby(val base: IntArray, val reading: IntArray, val size: Int = 0) : TextItem() {
        override fun equals(other: Any?) = other is Ruby && base.contentEquals(other.base) && reading.contentEquals(other.reading)
        override fun hashCode() = base.contentHashCode() * 31 + reading.contentHashCode()
    }

    /** Literal `\n` in the script. */
    data object NewLine : TextItem()

    /** Literal `\k` (page break / wait-for-click inside one message). */
    data object PageBreak : TextItem()

    /** `<Wn>` / `<wn>` per-character or pause timing. */
    data class Wait(val value: Int, val lower: Boolean) : TextItem()
}

/** A parsed text token (dialogue line, narration, speaker name, choice caption ...). */
data class RichText(val items: List<TextItem>) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** All glyph codes in display order (ruby readings excluded). */
    fun baseCodes(): IntArray {
        val out = ArrayList<Int>()
        for (it in items) when (it) {
            is TextItem.Run -> it.codes.forEach(out::add)
            is TextItem.Ruby -> it.base.forEach(out::add)
            else -> {}
        }
        return out.toIntArray()
    }

    companion object {
        val EMPTY = RichText(emptyList())
    }
}

/**
 * Shift-JIS-aware parser for WA2 `.txt` tokens. Accepts trail byte 0x7F (CK-GAL CN text uses 18
 * `0x??7F` codes, which generic SJIS decoders reject). ASCII markup is only recognised on lead
 * positions, so trail bytes 0x5C (`\`) and 0x7C (`|`) inside double-byte characters are safe.
 */
object ScenarioTextParser {
    fun parse(bytes: ByteArray): RichText {
        val p = Parser(bytes)
        return RichText(p.parseUntil(closer = false, size = 0, style = ' '))
    }

    private class Parser(val b: ByteArray) {
        var i = 0

        fun u(k: Int) = b[k].toInt() and 0xFF

        /** Reads one character code at [i] and advances. */
        fun readCode(): Int {
            val c = u(i)
            if (Wa2FontMap.isLeadByte(c) && i + 1 < b.size) {
                val t = u(i + 1)
                i += 2
                return (c shl 8) or t
            }
            i += 1
            return c
        }

        fun peekIs(s: String): Boolean {
            if (i + s.length > b.size) return false
            for (k in s.indices) if (u(i + k) != s[k].code) return false
            return true
        }

        fun readNumber(): Int {
            var v = 0
            var any = false
            var neg = false
            if (i < b.size && u(i) == '-'.code) { neg = true; i++ }
            while (i < b.size && u(i) in '0'.code..'9'.code) {
                v = v * 10 + (u(i) - '0'.code); i++; any = true
            }
            return if (!any) 0 else if (neg) -v else v
        }

        fun parseUntil(closer: Boolean, size: Int, style: Char): List<TextItem> {
            val out = ArrayList<TextItem>()
            val run = ArrayList<Int>()
            fun flush() {
                if (run.isNotEmpty()) {
                    out += TextItem.Run(run.toIntArray(), size, style); run.clear()
                }
            }
            while (i < b.size) {
                val c = u(i)
                when {
                    closer && c == '>'.code -> { i++; flush(); return out }
                    c == '\\'.code -> {
                        // Literal escapes; CK-GAL occasionally doubles the backslash.
                        var j = i
                        while (j < b.size && u(j) == '\\'.code) j++
                        val n = if (j < b.size) u(j) else 0
                        when (n) {
                            'n'.code -> { flush(); out += TextItem.NewLine; i = j + 1 }
                            'k'.code -> { flush(); out += TextItem.PageBreak; i = j + 1 }
                            else -> { run += c; i++ }
                        }
                    }
                    c == '<'.code && i + 1 < b.size -> {
                        val tag = u(i + 1).toChar()
                        when (tag) {
                            'R' -> {
                                flush(); i += 2
                                val base = ArrayList<Int>()
                                while (i < b.size && u(i) != '|'.code && u(i) != '>'.code) base += readCode()
                                val reading = ArrayList<Int>()
                                if (i < b.size && u(i) == '|'.code) {
                                    i++
                                    while (i < b.size && u(i) != '>'.code) reading += readCode()
                                }
                                if (i < b.size) i++ // '>'
                                out += TextItem.Ruby(base.toIntArray(), reading.toIntArray(), size)
                            }
                            'W', 'w' -> {
                                flush(); i += 2
                                val v = readNumber()
                                if (i < b.size && u(i) == '>'.code) i++
                                out += TextItem.Wait(v, tag == 'w')
                            }
                            'F', 'S' -> {
                                // <F14 ...> font size block / <S0 ...> style block (may nest <W..>/<R..>).
                                flush(); i += 2
                                val v = readNumber()
                                out += parseUntil(true, if (tag == 'F') v else size, if (tag == 'S') 'S' else style)
                            }
                            else -> { run += readCode() }
                        }
                    }
                    else -> run += readCode()
                }
            }
            flush()
            return out
        }
    }
}

/** Display language selection. */
enum class TextLang { JP, CN }

/**
 * Converts parsed tokens to Unicode / glyph cells for either language.
 *
 * * JP: CP932 per code, ruby kept as structure (renderers place furigana).
 * * CN: codes are pseudo-SJIS cell indices into `fon.pak`; Unicode comes from the recovered
 *   cell table ([CnCellTable]) — glyph rendering never needs it (the atlas is used directly).
 */
class BilingualTextDecoder(private val cnTable: CnCellTable = CnCellTable.load()) {

    fun jpChar(code: Int): String = codeToCp932(code)

    fun cnChar(code: Int): String {
        val cell = Wa2FontMap.cellOf(code)
        if (cell >= 0) cnTable.unicodeOf(cell)?.let { return it }
        return codeToCp932(code)
    }

    fun char(code: Int, lang: TextLang): String = if (lang == TextLang.JP) jpChar(code) else cnChar(code)

    /**
     * Flattens [text] to a plain string. Ruby is rendered as `base` (when [withRuby] false) or
     * `base(reading)`; `\n` → '\n'; page breaks → '\n'.
     */
    fun toPlain(text: RichText, lang: TextLang, withRuby: Boolean = false): String {
        val sb = StringBuilder()
        for (item in text.items) when (item) {
            is TextItem.Run -> item.codes.forEach { sb.append(char(it, lang)) }
            is TextItem.Ruby -> {
                item.base.forEach { sb.append(char(it, lang)) }
                if (withRuby && item.reading.isNotEmpty()) {
                    sb.append('(')
                    item.reading.forEach { sb.append(char(it, lang)) }
                    sb.append(')')
                }
            }
            TextItem.NewLine, TextItem.PageBreak -> sb.append('\n')
            is TextItem.Wait -> {}
        }
        return sb.toString()
    }

    fun decode(bytes: ByteArray, lang: TextLang, withRuby: Boolean = false): String =
        toPlain(ScenarioTextParser.parse(bytes), lang, withRuby)

    private companion object {
        fun codeToCp932(code: Int): String {
            if (code < 0x100) {
                if (code in 0x20..0x7E) return code.toChar().toString()
                return Cp932.decode(byteArrayOf(code.toByte()))
            }
            val trail = code and 0xFF
            if (trail == 0x7F) return "\u30FB" // CP932 cannot decode trail 0x7F: engine shows '・' in JP.
            val s = Cp932.decode(byteArrayOf((code shr 8).toByte(), trail.toByte()))
            return if (s == "\uFFFD" || s.isEmpty()) "\u3013" else s
        }
    }
}

/** Recovered CN atlas cell → Unicode table (`wa2/cn_cells.tsv`: `cell<TAB>U+XXXX<TAB>char<TAB>score`). */
class CnCellTable(private val map: Map<Int, String>) {
    val size: Int get() = map.size

    fun unicodeOf(cell: Int): String? = map[cell]

    companion object {
        fun parse(text: String): CnCellTable {
            val m = HashMap<Int, String>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size < 2) continue
                val cell = f[0].trim().toIntOrNull() ?: continue
                val cp = f[1].trim().removePrefix("U+").removePrefix("u+").toIntOrNull(16) ?: continue
                m[cell] = String(Character.toChars(cp))
            }
            return CnCellTable(m)
        }

        fun load(): CnCellTable {
            val text = CnCellTable::class.java.getResourceAsStream("/wa2/cn_cells.tsv")?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return CnCellTable(emptyMap())
            return parse(text)
        }
    }
}
