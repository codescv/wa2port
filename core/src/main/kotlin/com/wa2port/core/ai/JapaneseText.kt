package com.wa2port.core.ai

import java.text.Normalizer

/** Japanese text helpers (port of kotori `util/text.ts`). Pure; offsets are UTF-16 units. */
object JapaneseText {
    /** CJK ideograph test (incl. ext. A/B+, compatibility ideographs, 々 〆 ヶ 〇). */
    fun isKanji(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2EBEF || cp in 0xF900..0xFAFF ||
            cp == 0x3005 || cp == 0x3006 || cp == 0x30F6 || cp == 0x3007

    fun isHiragana(cp: Int): Boolean = cp in 0x3041..0x309F

    /** Katakana incl. phonetic extensions and half-width forms. */
    fun isKatakana(cp: Int): Boolean = cp in 0x30A0..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F

    /** Kana (hiragana or katakana, incl. ー); ヶ counts as kanji-like. */
    fun isKana(cp: Int): Boolean = cp != 0x30F6 && (isHiragana(cp) || isKatakana(cp))

    fun hasKanji(text: String): Boolean = text.codePoints().anyMatch { isKanji(it) }

    /** Full-width katakana (ァ–ヶ, ヽヾ) → hiragana; other characters unchanged. */
    fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) sb.appendCodePoint(cp - 0x60) else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Whitespace incl. the ideographic space U+3000. */
    fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x3000

    /** Unicode punctuation / symbol (`\p{P}` / `\p{S}`) or whitespace. */
    fun isPunctuationOrSpace(cp: Int): Boolean {
        if (isWhitespace(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true
            else -> false
        }
    }

    private const val LONG_VOWEL = "ーｰ―‐−-"
    private const val WAVE = "～〜~"

    /** Folds one NFKC'd code point for fuzzy comparison; "" when dropped (whitespace). */
    private fun foldCodePoint(cp: Int): String {
        if (isWhitespace(cp)) return ""
        val s = String(Character.toChars(cp))
        if (WAVE.contains(s)) return "〜"
        if (LONG_VOWEL.contains(s)) return "ー"
        return katakanaToHiragana(s).lowercase()
    }

    /** Normalised text plus per-unit source offsets ([map] start, [endMap] end; sentinel = source length). */
    class Normalised(val text: String, val map: IntArray, val endMap: IntArray)

    /** Fuzzy-comparison normal form: NFKC, no whitespace, unified 〜 / ー, katakana → hiragana, lower-case. */
    fun normalise(text: String): String = normaliseWithMap(text).text

    /**
     * Same as [normalise] but also returns, for every UTF-16 unit of the normalised text, the source
     * offset range of the character that produced it (used to project fuzzy matches back).
     */
    fun normaliseWithMap(source: String): Normalised {
        val sb = StringBuilder()
        val map = ArrayList<Int>()
        val endMap = ArrayList<Int>()
        var i = 0
        while (i < source.length) {
            val cp = source.codePointAt(i)
            val start = i
            i += Character.charCount(cp)
            val nfkc = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
            var k = 0
            while (k < nfkc.length) {
                val c = nfkc.codePointAt(k)
                k += Character.charCount(c)
                val folded = foldCodePoint(c)
                // Half-width dakuten (ｶﾞ → カ + U+3099): compose with the preceding kana so it
                // matches the precomposed form; the composed unit's end covers both source chars.
                // Only with the immediately preceding source char (never across dropped whitespace).
                if ((c == 0x3099 || c == 0x309A) && sb.isNotEmpty() && endMap.last() == start) {
                    val composed = Normalizer.normalize("${sb[sb.length - 1]}${folded}", Normalizer.Form.NFC)
                    if (composed.length == 1) {
                        sb.setCharAt(sb.length - 1, composed[0])
                        endMap[endMap.size - 1] = i
                        continue
                    }
                }
                repeat(folded.length) {
                    map.add(start)
                    endMap.add(i)
                }
                sb.append(folded)
            }
        }
        map.add(source.length)
        endMap.add(source.length)
        return Normalised(sb.toString(), map.toIntArray(), endMap.toIntArray())
    }
}
