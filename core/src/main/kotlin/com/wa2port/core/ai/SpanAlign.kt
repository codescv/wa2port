package com.wa2port.core.ai

/**
 * Deterministic alignment of model-provided `surface` strings back onto the source sentence (port
 * of kotori `ai/span-align.ts` + the click lookups of `ai/explanation.ts`). The model never emits
 * indices; this is the only place where character spans are created.
 */
object SpanAlign {
    data class AlignResult(val spans: List<CharSpan>, val alignment: List<SpanAlignment>)

    /**
     * Aligns [surfaces] (in order) onto [sentence] with a monotonic cursor:
     * 1. verbatim `indexOf(surface, cursor)` → [SpanAlignment.EXACT];
     * 2. match on normalised forms (NFKC, no spaces, 〜/ー unified, katakana → hiragana, composed
     *    half-width dakuten) projected back to source offsets → [SpanAlignment.FUZZY];
     *    whichever of 1/2 starts earlier wins (exact on ties);
     * 3. otherwise the empty span `[cursor, cursor)` → [SpanAlignment.UNALIGNED].
     * Spans never overlap and are non-decreasing (so repeated particles bind left to right).
     */
    fun alignSpans(sentence: String, surfaces: List<String>): AlignResult {
        val spans = ArrayList<CharSpan>(surfaces.size)
        val alignment = ArrayList<SpanAlignment>(surfaces.size)
        var norm: JapaneseText.Normalised? = null // computed lazily, only for fuzzy matches
        var cursor = 0
        for (surface in surfaces) {
            if (surface.isEmpty()) {
                spans += CharSpan(cursor, cursor)
                alignment += SpanAlignment.UNALIGNED
                continue
            }
            // Pass 1: verbatim.
            val idx = sentence.indexOf(surface, cursor)
            // Pass 2: normalised comparison mapped back to source offsets. Both are computed so an
            // exact match far ahead cannot swallow a nearer fuzzy one (the earlier start wins;
            // exact wins ties).
            val n = norm ?: JapaneseText.normaliseWithMap(sentence).also { norm = it }
            val needle = JapaneseText.normalise(surface)
            var fuzzy: CharSpan? = null
            if (needle.isNotEmpty()) {
                // First normalised position whose source offset is at/after the cursor.
                var from = 0
                while (from < n.text.length && n.map[from] < cursor) from++
                val nIdx = n.text.indexOf(needle, from)
                if (nIdx >= 0) fuzzy = CharSpan(n.map[nIdx], n.endMap[nIdx + needle.length - 1])
            }
            if (idx >= 0 && (fuzzy == null || idx <= fuzzy.start)) {
                val end = idx + surface.length
                spans += CharSpan(idx, end)
                alignment += SpanAlignment.EXACT
                cursor = end
                continue
            }
            if (fuzzy != null) {
                spans += fuzzy
                alignment += SpanAlignment.FUZZY
                cursor = fuzzy.end
                continue
            }
            spans += CharSpan(cursor, cursor)
            alignment += SpanAlignment.UNALIGNED
        }
        return AlignResult(spans, alignment)
    }

    /** Clamps spans into `[0, length]`, fixes inverted spans and enforces monotonic, non-overlapping order. */
    fun clampSpans(spans: List<CharSpan>, length: Int): List<CharSpan> {
        var prevEnd = 0
        return spans.map { (s, e) ->
            val cs = s.coerceIn(0, length)
            val ce = e.coerceIn(0, length)
            val start = maxOf(cs, prevEnd)
            val end = maxOf(start, ce)
            prevEnd = end
            CharSpan(start, end)
        }
    }

    /**
     * Fraction (0..1) of the sentence's non-space, non-punctuation UTF-16 units covered by [spans];
     * a sentence without such characters counts as fully covered.
     */
    fun coverage(sentence: String, spans: List<CharSpan>): Float {
        val covered = BooleanArray(sentence.length)
        for ((s, e) in spans) for (i in maxOf(0, s) until minOf(e, sentence.length)) covered[i] = true
        var total = 0
        var hit = 0
        var i = 0
        while (i < sentence.length) {
            val cp = sentence.codePointAt(i)
            val n = Character.charCount(cp)
            if (!JapaneseText.isPunctuationOrSpace(cp)) {
                total += n
                for (k in 0 until n) if (covered[i + k]) hit++
            }
            i += n
        }
        return if (total == 0) 1f else hit.toFloat() / total
    }

    /**
     * Chunk for a tap at [charIndex]: a tap inside a non-empty span returns that chunk; a tap in a
     * gap (punctuation / whitespace / uncovered text) returns the nearest chunk to the right, else
     * the last chunk to the left; null when no chunk has a non-empty span.
     */
    fun findChunkAtChar(chunks: List<SemanticChunk>, charIndex: Int): SemanticChunk? {
        val sorted = chunks.filter { !it.span.isEmpty }.sortedBy { it.span.start }
        if (sorted.isEmpty()) return null
        sorted.firstOrNull { charIndex in it.span }?.let { return it }
        return sorted.firstOrNull { it.span.start > charIndex } ?: sorted.last()
    }

    /** Vocab entry whose span contains [charIndex] (no nearest fallback). */
    fun findVocabAtChar(vocab: List<VocabEntry>, charIndex: Int): VocabEntry? =
        vocab.firstOrNull { !it.span.isEmpty && charIndex in it.span }

    /**
     * Vocabulary of [chunk]: its `vocabIds` in order; when that list is empty (e.g. hand-edited cache)
     * falls back to the aligned vocab whose span lies inside the chunk span.
     */
    fun findVocabForChunk(chunk: SemanticChunk, vocab: List<VocabEntry>): List<VocabEntry> {
        if (chunk.vocabIds.isNotEmpty()) {
            val byId = vocab.associateBy { it.id }
            return chunk.vocabIds.mapNotNull { byId[it] }
        }
        if (chunk.span.isEmpty) return emptyList()
        return vocab.filter { !it.span.isEmpty && it.span.start >= chunk.span.start && it.span.end <= chunk.span.end }
    }

    /** Grammar points that reference [chunk]. */
    fun grammarForChunk(chunk: SemanticChunk, grammar: List<GrammarPoint>): List<GrammarPoint> =
        grammar.filter { chunk.id in it.chunkIds }

    /** True when the character at [charIndex] is whitespace / punctuation (or out of range). */
    fun isGapCharAt(sentence: String, charIndex: Int): Boolean {
        if (charIndex !in sentence.indices) return true
        return JapaneseText.isPunctuationOrSpace(sentence.codePointAt(charIndex))
    }
}
