package com.wa2port.core.ai

/** A chunk or vocabulary span whose reading can normalize jukujikun or backfill missing furigana. */
data class RubyReadingSpan(
    val span: CharSpan,
    val reading: String,
    val alignment: SpanAlignment = SpanAlignment.EXACT,
)

/**
 * Furigana segmentation that always reproduces the sentence (port of kotori `ai/ruby.ts` +
 * `dict/lemma-ruby.ts`): `buildRuby(s, raw).joinToString("") { it.base } == s` holds for every input.
 */
object RubyBuilder {
    /** Model-provided ruby unit. */
    data class RawRuby(val base: String, val ruby: String? = null)

    /**
     * Whole-word irregular readings (jukujikun / ateji / irregular compounds) annotated as one
     * group (port of kotori `JUKUJIKUN_READINGS`).
     */
    private val JUKUJIKUN_READINGS: Map<String, String> = mapOf(
        "明日" to "あした",
        "今日" to "きょう",
        "昨日" to "きのう",
        "一昨日" to "おととい",
        "明後日" to "あさって",
        "今朝" to "けさ",
        "今年" to "ことし",
        "去年" to "きょねん",
        "一昨年" to "おととし",
        "今夜" to "こんや",
        "大人" to "おとな",
        "一人" to "ひとり",
        "二人" to "ふたり",
        "一日" to "ついたち",
        "二日" to "ふつか",
        "二十日" to "はつか",
        "二十歳" to "はたち",
        "上手" to "じょうず",
        "下手" to "へた",
        "時計" to "とけい",
        "眼鏡" to "めがね",
        "果物" to "くだもの",
        "部屋" to "へや",
        "景色" to "けしき",
        "土産" to "みやげ",
        "浴衣" to "ゆかた",
        "素人" to "しろうと",
        "玄人" to "くろうと",
        "仲人" to "なこうど",
        "若人" to "わこうど",
        "田舎" to "いなか",
        "息子" to "むすこ",
        "真面目" to "まじめ",
        "為替" to "かわせ",
        "七夕" to "たなばた",
        "梅雨" to "つゆ",
        "五月雨" to "さみだれ",
        "時雨" to "しぐれ",
        "吹雪" to "ふぶき",
        "雪崩" to "なだれ",
        "紅葉" to "もみじ",
        "海老" to "えび",
        "河豚" to "ふぐ",
        "煙草" to "たばこ",
        "相撲" to "すもう",
        "竹刀" to "しない",
        "草履" to "ぞうり",
        "足袋" to "たび",
        "木綿" to "もめん",
        "老舗" to "しにせ",
        "八百屋" to "やおや",
        "八百長" to "やおちょう",
        "心地" to "ここち",
        "居士" to "こじ",
        "為体" to "ていたらく",
        "所謂" to "いわゆる",
        "流石" to "さすが",
        "一寸" to "ちょっと",
        "何卒" to "なにとぞ",
        "寿司" to "すし",
        "山車" to "だし",
        "蚊帳" to "かや",
        "太刀" to "たち",
        "欠伸" to "あくび",
        "息吹" to "いぶき",
        "乳母" to "うば",
        "叔父" to "おじ",
        "伯父" to "おじ",
        "叔母" to "おば",
        "伯母" to "おば",
        "清水" to "しみず",
        "日和" to "ひより",
        "白髪" to "しらが",
        "三味線" to "しゃみせん",
        "芝生" to "しばふ",
        "数珠" to "じゅず",
        "砂利" to "じゃり",
        "数寄屋" to "すきや",
        "師走" to "しわす",
        "稚児" to "ちご",
        "投網" to "とあみ",
        "名残" to "なごり",
        "雪洞" to "ぼんぼり",
        "迷子" to "まいご",
        "土筆" to "つくし",
        "百合" to "ゆり",
        "行方" to "ゆくえ",
        "寄席" to "よせ",
        "若布" to "わかめ",
        "早乙女" to "さおとめ",
        "桟敷" to "さじき",
        "博士" to "はかせ",
        "波止場" to "はとば",
        "一言" to "ひとこと",
    )

    private data class Run(val text: String, val start: Int, val kanji: Boolean)

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

    private fun hasNonKanji(text: String): Boolean = text.codePoints().anyMatch { !JapaneseText.isKanji(it) }
    private fun isPureKanji(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch { JapaneseText.isKanji(it) }
    private fun allKana(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch { JapaneseText.isKana(it) }

    private fun stripWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!JapaneseText.isWhitespace(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun nonWhitespaceCodePointCount(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!JapaneseText.isWhitespace(cp)) count++
            i += Character.charCount(cp)
        }
        return count
    }

    /** Comparable reading: katakana → hiragana, punctuation / whitespace removed. */
    private fun readingKey(text: String): String {
        val hira = JapaneseText.katakanaToHiragana(text)
        val sb = StringBuilder(hira.length)
        var i = 0
        while (i < hira.length) {
            val cp = hira.codePointAt(i)
            if (!JapaneseText.isPunctuationOrSpace(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun runs(text: String): List<Run> {
        val out = ArrayList<Run>()
        var runStart = 0
        var runKanji: Boolean? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val k = JapaneseText.isKanji(cp)
            if (runKanji != null && k != runKanji) {
                out += Run(text.substring(runStart, i), runStart, runKanji)
                runStart = i
            }
            runKanji = k
            i += Character.charCount(cp)
        }
        if (i > runStart && runKanji != null) out += Run(text.substring(runStart, i), runStart, runKanji)
        return out
    }

    /** Splits [text] (located at [offset] in the sentence) into alternating kanji / non-kanji runs without readings. */
    fun plainRuns(text: String, offset: Int): List<RubyToken> = runs(text).map { seg(it.text, offset + it.start) }

    /**
     * Aligns kanji runs of [parts] to [reading] (already folded to hiragana without whitespace) by
     * matching non-kanji runs literally (with boundary/adjacent punctuation optional so chunk
     * surfaces like `気かよ？` match `きかよ`). Returns the reading of each kanji run, or null.
     */
    private fun alignRuns(parts: List<Run>, reading: String): List<String>? {
        val pattern = StringBuilder("^")
        parts.forEachIndexed { idx, p ->
            if (p.kanji) {
                pattern.append("(.+?)")
            } else {
                val hasNonPunct = p.text.codePoints().anyMatch { !JapaneseText.isPunctuationOrSpace(it) }
                val isBoundary = idx == 0 || idx == parts.lastIndex
                if (!hasNonPunct && !isBoundary && p.text.codePoints().allMatch { JapaneseText.isWhitespace(it) }) {
                    return null
                }
                var i = 0
                while (i < p.text.length) {
                    val cp = p.text.codePointAt(i)
                    val ch = String(Character.toChars(cp))
                    when {
                        JapaneseText.isWhitespace(cp) -> {}
                        JapaneseText.isPunctuationOrSpace(cp) -> {
                            val esc = Regex.escape(ch)
                            if (hasNonPunct || isBoundary) pattern.append("(?:").append(esc).append(")?") else pattern.append(esc)
                        }
                        else -> pattern.append(Regex.escape(JapaneseText.katakanaToHiragana(ch)))
                    }
                    i += Character.charCount(cp)
                }
            }
        }
        pattern.append('$')
        val m = Regex(pattern.toString()).matchEntire(reading) ?: return null
        return m.groupValues.drop(1)
    }

    private fun codePointSlices(s: String): Pair<List<String>, IntArray> {
        val chars = ArrayList<String>()
        val starts = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            starts += i
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            chars += s.substring(i, i + n)
            i += n
        }
        starts += s.length
        return chars to starts.toIntArray()
    }

    /** Fallback: strip leading/trailing punctuation and matching kana prefix/suffix; group the middle. */
    private fun prefixSuffixFallback(surface: String, reading: String, offset: Int): List<RubyToken> {
        val (sChars, sOffsets) = codePointSlices(surface)
        val (rChars, _) = codePointSlices(reading)
        var sLeft = 0
        var sRight = sChars.size
        var rLeft = 0
        var rRight = rChars.size

        // Strip leading/trailing punctuation or whitespace on surface (and matching punctuation on reading).
        while (sLeft < sRight && JapaneseText.isPunctuationOrSpace(sChars[sLeft].codePointAt(0))) {
            if (rLeft < rRight && rChars[rLeft] == sChars[sLeft]) rLeft++
            sLeft++
        }
        while (sRight > sLeft && JapaneseText.isPunctuationOrSpace(sChars[sRight - 1].codePointAt(0))) {
            if (rRight > rLeft && rChars[rRight - 1] == sChars[sRight - 1]) rRight--
            sRight--
        }

        // Strip matching kana prefix and suffix.
        while (sLeft < sRight && rLeft < rRight) {
            val cp = sChars[sLeft].codePointAt(0)
            if (JapaneseText.isKanji(cp) || !JapaneseText.isKana(cp)) break
            if (JapaneseText.katakanaToHiragana(sChars[sLeft]) != rChars[rLeft]) break
            sLeft++
            rLeft++
        }
        while (sRight > sLeft && rRight > rLeft) {
            val cp = sChars[sRight - 1].codePointAt(0)
            if (JapaneseText.isKanji(cp) || !JapaneseText.isKana(cp)) break
            if (JapaneseText.katakanaToHiragana(sChars[sRight - 1]) != rChars[rRight - 1]) break
            sRight--
            rRight--
        }

        val midStart = sOffsets[sLeft]
        val midEnd = sOffsets[sRight]
        val prefix = surface.substring(0, midStart)
        val middle = surface.substring(midStart, midEnd)
        val suffix = surface.substring(midEnd)
        val middleReading = readingKey(rChars.subList(rLeft, rRight).joinToString(""))
        val cleaned = cleanReading(middleReading, middle)
        if (middle.isEmpty() || cleaned == null) return plainRuns(surface, offset)
        val out = ArrayList<RubyToken>()
        if (prefix.isNotEmpty()) out += plainRuns(prefix, offset)
        out += seg(middle, offset + prefix.length, cleaned)
        if (suffix.isNotEmpty()) out += plainRuns(suffix, offset + prefix.length + middle.length)
        return out
    }

    /**
     * Computes deterministic furigana tokens for a word or phrase [surface] given its [reading]
     * (port of kotori `computeLemmaRuby`), splitting okurigana cleanly from kanji stems so e.g.
     * `放り出す` + `ほうりだす` → `放(ほう)` + `り` + `出(だ)` + `す`, and `他に` + `ほかに` → `他(ほか)` + `に`.
     */
    fun computeWordRuby(surface: String, reading: String, offset: Int = 0): List<RubyToken> {
        if (surface.isEmpty()) return emptyList()
        if (!JapaneseText.hasKanji(surface)) return plainRuns(surface, offset)
        val table = JUKUJIKUN_READINGS[surface]
        val folded = JapaneseText.katakanaToHiragana(reading.trim())
        if (folded.isEmpty()) {
            return if (table != null) listOf(seg(surface, offset, JapaneseText.katakanaToHiragana(table))) else plainRuns(surface, offset)
        }
        if (table != null && JapaneseText.katakanaToHiragana(table) == folded) {
            return listOf(seg(surface, offset, folded))
        }
        val noWs = stripWhitespace(folded)
        if (noWs.isEmpty()) return plainRuns(surface, offset)

        val parts = runs(surface)
        val kanjiReadings = alignRuns(parts, noWs)
        if (kanjiReadings != null) {
            val out = ArrayList<RubyToken>(parts.size)
            var k = 0
            for (p in parts) {
                if (p.kanji) {
                    val r = cleanReading(kanjiReadings[k++], p.text)
                    out += seg(p.text, offset + p.start, r)
                } else {
                    out += seg(p.text, offset + p.start)
                }
            }
            return out
        }
        return prefixSuffixFallback(surface, noWs, offset)
    }

    private fun emitRawSegment(base: String, start: Int, rawRuby: String?, out: MutableList<RubyToken>) {
        if (base.isEmpty()) return
        if (!JapaneseText.hasKanji(base)) {
            out += plainRuns(base, start)
            return
        }
        val reading = cleanReading(rawRuby, base)
        if (reading == null) {
            out += plainRuns(base, start)
            return
        }
        if (hasNonKanji(base)) {
            out += computeWordRuby(base, reading, start)
        } else {
            out += seg(base, start, reading)
        }
    }

    /**
     * Builds ruby tokens for [sentence] from the model's [raw] segmentation:
     * 1. Fast path when bases concatenate to [sentence] directly, or when they concatenate after
     *    ignoring whitespace differences (e.g. full-width `\u3000` after `！` omitted by the model).
     * 2. Mixed kanji+okurigana `RawRuby` tokens are split via [computeWordRuby] so okurigana is
     *    never trapped inside a kanji ruby column.
     * 3. Fallback aligns kanji-bearing segments with readings via [SpanAlign.alignSpans] and fills
     *    gaps with plain runs.
     */
    fun buildRuby(sentence: String, raw: List<RawRuby>): List<RubyToken> {
        if (sentence.isEmpty()) return emptyList()
        val rawConcat = raw.joinToString("") { it.base }
        if (rawConcat == sentence) {
            val out = ArrayList<RubyToken>()
            var pos = 0
            for (r in raw) {
                if (r.base.isEmpty()) continue
                emitRawSegment(r.base, pos, r.ruby, out)
                pos += r.base.length
            }
            return out
        }

        val sentenceNoWs = stripWhitespace(sentence)
        if (sentenceNoWs.isNotEmpty() && stripWhitespace(rawConcat) == sentenceNoWs) {
            val out = ArrayList<RubyToken>()
            var sPos = 0
            for (r in raw) {
                val needed = nonWhitespaceCodePointCount(r.base)
                if (needed == 0) continue
                var wsEnd = sPos
                while (wsEnd < sentence.length && JapaneseText.isWhitespace(sentence.codePointAt(wsEnd))) {
                    wsEnd += Character.charCount(sentence.codePointAt(wsEnd))
                }
                if (wsEnd > sPos) {
                    out += plainRuns(sentence.substring(sPos, wsEnd), sPos)
                    sPos = wsEnd
                }
                var segEnd = sPos
                var seen = 0
                while (segEnd < sentence.length && seen < needed) {
                    val cp = sentence.codePointAt(segEnd)
                    if (!JapaneseText.isWhitespace(cp)) seen++
                    segEnd += Character.charCount(cp)
                }
                emitRawSegment(sentence.substring(sPos, segEnd), sPos, r.ruby, out)
                sPos = segEnd
            }
            if (sPos < sentence.length) out += plainRuns(sentence.substring(sPos), sPos)
            return out
        }

        // Only kanji-bearing segments with a reading are aligned: they are distinctive, so a stray
        // kana base cannot drag the monotonic cursor far ahead.
        val candidates = raw.filter { !it.ruby.isNullOrBlank() && JapaneseText.hasKanji(it.base) }
        val (spans, alignment) = SpanAlign.alignSpans(sentence, candidates.map { it.base })
        val out = ArrayList<RubyToken>()
        var pos = 0
        candidates.forEachIndexed { k, r ->
            val span = spans[k]
            if (alignment[k] == SpanAlignment.UNALIGNED) return@forEachIndexed
            val (s, e) = span
            if (e <= s || s < pos) return@forEachIndexed
            val base = sentence.substring(s, e)
            if (!JapaneseText.hasKanji(base)) return@forEachIndexed
            if (hasNonKanji(base)) {
                val split = computeWordRuby(base, r.ruby.orEmpty(), s)
                if (split.none { it.ruby != null }) return@forEachIndexed
                if (s > pos) out += plainRuns(sentence.substring(pos, s), pos)
                out += split
                pos = e
            } else {
                val reading = cleanReading(r.ruby, base) ?: return@forEachIndexed
                if (s > pos) out += plainRuns(sentence.substring(pos, s), pos)
                out += seg(base, s, reading)
                pos = e
            }
        }
        if (pos < sentence.length) out += plainRuns(sentence.substring(pos), pos)
        return out
    }

    /**
     * Repairs jukujikun / ateji readings that the model split per kanji (port of kotori
     * `normalizeJukujikunRuby`, e.g. `明(あ)日(ひ)` + chunk reading `あした` → `明日(あした)`).
     */
    fun normalizeJukujikunRuby(
        sentence: String,
        sentenceRuby: List<RubyToken>,
        chunks: List<RubyReadingSpan>,
    ): List<RubyToken> {
        val out = ArrayList(sentenceRuby)
        for (chunk in chunks) {
            if (chunk.alignment == SpanAlignment.UNALIGNED) continue
            val (from, to) = chunk.span
            val reading = readingKey(chunk.reading)
            if (from < 0 || to > sentence.length || to <= from || reading.isEmpty()) continue
            val first = out.indexOfFirst { it.span.end > from }
            if (first < 0) continue
            var end = first
            while (end < out.size && out[end].span.start < to) end++
            val segs = out.subList(first, end).toList()
            if (segs.isEmpty()) continue
            val kanjiIdx = segs.indices.filter { JapaneseText.hasKanji(segs[it].base) }
            if (kanjiIdx.size < 2) continue
            val k0 = kanjiIdx.first()
            val k1 = kanjiIdx.last()
            if (k1 - k0 + 1 != kanjiIdx.size) continue
            val runStart = segs[k0].span.start
            val runEnd = segs[k1].span.end
            if (runStart < from || runEnd > to) continue
            if (segs.first().span.start < from && segs.first().ruby != null) continue
            if (segs.last().span.end > to && segs.last().ruby != null) continue
            val prefix = readingKey(sentence.substring(from, runStart))
            val suffix = readingKey(sentence.substring(runEnd, to))
            val currentRunReading = readingKey(segs.subList(k0, k1 + 1).joinToString("") { it.ruby ?: it.base })
            if (prefix + currentRunReading + suffix == reading) continue
            if (!reading.startsWith(prefix) || !reading.endsWith(suffix) || reading.length <= prefix.length + suffix.length) continue
            val middle = reading.substring(prefix.length, reading.length - suffix.length)
            if (!allKana(middle)) continue
            val merged = seg(sentence.substring(runStart, runEnd), runStart, middle)
            repeat(k1 - k0 + 1) { out.removeAt(first + k0) }
            out.add(first + k0, merged)
        }
        return out
    }

    fun normalizeJukujikunRuby(sentenceRuby: List<RubyToken>, chunks: List<RubyReadingSpan>): List<RubyToken> =
        normalizeJukujikunRuby(sentenceRuby.joinToString("") { it.base }, sentenceRuby, chunks)

    /**
     * Backfills missing kanji furigana (`ruby == null`) from aligned [sources] (chunks and vocab)
     * without overwriting any existing readings.
     */
    fun backfillMissingRuby(
        sentence: String,
        sentenceRuby: List<RubyToken>,
        sources: List<RubyReadingSpan>,
    ): List<RubyToken> {
        val out = ArrayList(sentenceRuby)
        for (src in sources) {
            if (src.alignment == SpanAlignment.UNALIGNED) continue
            val (from, to) = src.span
            if (from < 0 || to > sentence.length || to <= from || src.reading.isBlank()) continue
            val spanText = sentence.substring(from, to)
            if (!JapaneseText.hasKanji(spanText)) continue
            val hasMissingKanji = out.any { it.span.end > from && it.span.start < to && it.ruby == null && JapaneseText.hasKanji(it.base) }
            if (!hasMissingKanji) continue

            val computed = computeWordRuby(spanText, src.reading, from)
            for (ct in computed) {
                val ctRuby = ct.ruby ?: continue
                if (!isPureKanji(ct.base)) continue
                val (cs, ce) = ct.span
                val i0 = out.indexOfFirst { it.span.end > cs }
                if (i0 < 0) continue
                var i1 = i0
                while (i1 < out.size && out[i1].span.start < ce) i1++
                if (i1 <= i0) continue
                if (out.subList(i0, i1).any { it.ruby != null }) continue
                val firstTok = out[i0]
                val lastTok = out[i1 - 1]
                val rep = ArrayList<RubyToken>(3)
                if (firstTok.span.start < cs) rep += plainRuns(sentence.substring(firstTok.span.start, cs), firstTok.span.start)
                rep += seg(sentence.substring(cs, ce), cs, ctRuby)
                if (lastTok.span.end > ce) rep += plainRuns(sentence.substring(ce, lastTok.span.end), ce)
                repeat(i1 - i0) { out.removeAt(i0) }
                out.addAll(i0, rep)
            }
        }
        return out
    }

    /**
     * Normalizes split jukujikun readings against [chunks] and backfills any kanji tokens whose
     * `ruby` was omitted by the model using aligned [chunks] and [vocab] readings.
     */
    fun backfillAndNormalizeRuby(
        sentence: String,
        ruby: List<RubyToken>,
        chunks: List<RubyReadingSpan>,
        vocab: List<RubyReadingSpan>,
    ): List<RubyToken> {
        val normalized = normalizeJukujikunRuby(sentence, ruby, chunks)
        return backfillMissingRuby(sentence, normalized, chunks + vocab)
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
