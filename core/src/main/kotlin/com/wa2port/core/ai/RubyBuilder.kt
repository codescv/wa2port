package com.wa2port.core.ai

/**
 * Furigana segmentation that always reproduces the sentence (port of kotori `ai/ruby.ts`):
 * `buildRuby(s, raw).joinToString("") { it.base } == s` holds for every input.
 */
object RubyBuilder {
    /** Model-provided ruby unit. */
    data class RawRuby(val base: String, val ruby: String? = null)

    /**
     * Trim + katakana → hiragana; null when empty, equal to the base, or [base] contains no kanji
     * (so kana-only words such as `ドラム` never get redundant `どらむ` furigana).
     */
    fun cleanReading(ruby: String?, base: String): String? {
        if (ruby == null || !JapaneseText.hasKanji(base)) return null
        val r = JapaneseText.katakanaToHiragana(ruby.trim())
        if (r.isEmpty() || r == JapaneseText.katakanaToHiragana(base)) return null
        return r
    }

    private fun seg(base: String, start: Int, ruby: String? = null) = RubyToken(base, ruby, CharSpan(start, start + base.length))

    /** Splits [text] (located at [offset] in the sentence) into alternating kanji / non-kanji runs without readings. */
    fun plainRuns(text: String, offset: Int): List<RubyToken> {
        val out = ArrayList<RubyToken>()
        var runStart = 0
        var runKanji: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val k = JapaneseText.isKanji(cp)
            if (runKanji != null && k != runKanji) {
                out += seg(text.substring(runStart, i), offset + runStart)
                runStart = i
            }
            runKanji = k
            i += Character.charCount(cp)
        }
        if (i > runStart) out += seg(text.substring(runStart, i), offset + runStart)
        return out
    }

    /**
     * Builds ruby tokens for [sentence] from the model's [raw] segmentation. Fast path: bases
     * concatenate to the sentence → used verbatim (readings only on kanji-bearing tokens).
     * Otherwise kanji-bearing bases with a reading are aligned ([SpanAlign.alignSpans]) and every
     * gap is filled with plain runs.
     */
    fun buildRuby(sentence: String, raw: List<RawRuby>): List<RubyToken> {
        if (sentence.isEmpty()) return emptyList()
        if (raw.joinToString("") { it.base } == sentence) {
            val out = ArrayList<RubyToken>()
            var pos = 0
            for (r in raw) {
                if (r.base.isEmpty()) continue
                out += seg(r.base, pos, if (JapaneseText.hasKanji(r.base)) cleanReading(r.ruby, r.base) else null)
                pos += r.base.length
            }
            return out
        }
        // Only kanji-bearing segments with a reading are aligned: they are distinctive, so a stray
        // kana base cannot drag the monotonic cursor far ahead.
        val candidates = raw.filter { it.ruby != null && JapaneseText.hasKanji(it.base) }
        val (spans, alignment) = SpanAlign.alignSpans(sentence, candidates.map { it.base })
        val out = ArrayList<RubyToken>()
        var pos = 0
        candidates.forEachIndexed { k, r ->
            val span = spans[k]
            if (alignment[k] == SpanAlignment.UNALIGNED) return@forEachIndexed
            val (s, e) = span
            if (e <= s || s < pos) return@forEachIndexed
            val base = sentence.substring(s, e)
            val reading = if (JapaneseText.hasKanji(base)) cleanReading(r.ruby, base) else null
            if (reading == null) return@forEachIndexed // leave to plain-run filling
            if (s > pos) out += plainRuns(sentence.substring(pos, s), pos)
            out += seg(base, s, reading)
            pos = e
        }
        if (pos < sentence.length) out += plainRuns(sentence.substring(pos), pos)
        return out
    }

    /**
     * Restricts sentence-level ruby to [span], splitting tokens that straddle the boundary (split
     * pieces lose their reading). Coordinates stay in sentence space.
     */
    fun sliceRuby(ruby: List<RubyToken>, span: CharSpan): List<RubyToken> {
        val out = ArrayList<RubyToken>()
        for (r in ruby) {
            val (s, e) = r.span
            if (e <= span.start || s >= span.end) continue
            val cs = maxOf(s, span.start)
            val ce = minOf(e, span.end)
            if (cs == s && ce == e) out += r else out += plainRuns(r.base.substring(cs - s, ce - s), cs)
        }
        return out
    }

    /**
     * Overlays authoritative script furigana ([script], e.g. WA2 `<R本気|マジ>`) onto [ruby]: each
     * valid script span becomes exactly one token with the script reading; model tokens that
     * straddle a script span keep only their outside parts (as plain runs, no reading). The
     * concatenation invariant is preserved.
     */
    fun overlayScriptRuby(sentence: String, ruby: List<RubyToken>, script: List<ScriptRuby>): List<RubyToken> {
        // Valid, in-range, non-overlapping script spans in order.
        val spans = ArrayList<ScriptRuby>()
        for (sr in script.sortedBy { it.span.start }) {
            val (s, e) = sr.span
            if (s < 0 || e > sentence.length || e <= s || sr.reading.isBlank()) continue
            if (spans.isNotEmpty() && s < spans.last().span.end) continue
            spans += sr
        }
        if (spans.isEmpty()) return ruby
        val out = ArrayList<RubyToken>()
        var si = 0
        for (token in ruby) {
            val (ts, te) = token.span
            var pos = ts
            while (pos < te) {
                while (si < spans.size && spans[si].span.end <= pos) si++
                val sp = spans.getOrNull(si)
                when {
                    sp == null || sp.span.start >= te -> {
                        // Rest of the token lies outside every script span.
                        if (pos == ts) out += token else out += plainRuns(sentence.substring(pos, te), pos)
                        pos = te
                    }
                    sp.span.start > pos -> {
                        out += plainRuns(sentence.substring(pos, sp.span.start), pos)
                        pos = sp.span.start
                    }
                    else -> {
                        if (pos == sp.span.start) {
                            val base = sentence.substring(sp.span.start, sp.span.end)
                            // Script readings keep their kana (katakana gikun such as マジ is meaningful).
                            val reading = sp.reading.trim().takeIf { JapaneseText.katakanaToHiragana(it) != JapaneseText.katakanaToHiragana(base) }
                            out += RubyToken(base, reading, sp.span)
                        }
                        pos = minOf(te, sp.span.end)
                    }
                }
            }
        }
        return out
    }
}
