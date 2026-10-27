package com.wa2port.core.ai

/**
 * Offline fallback breakdown (no network, no key): splits the sentence into punctuation-delimited
 * phrases, keeps the script's own furigana and shows the official CK-GAL translation. Lets the AI
 * sheet stay useful (and testable on real lines) before a provider is configured.
 */
object OfflineExplainer {
    const val MODEL = "offline"

    /** Builds the offline breakdown of [req]. */
    fun build(req: ExplainRequest, now: Long = 0L): SentenceExplanation {
        val s = req.sentence
        val chunks = ArrayList<SemanticChunk>()
        val ruby = RubyBuilder.overlayScriptRuby(s, RubyBuilder.plainRuns(s, 0), req.scriptRuby)
        var i = 0
        while (i < s.length) {
            // Skip punctuation / whitespace gaps, then take the next run of content characters.
            while (i < s.length && JapaneseText.isPunctuationOrSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
            val start = i
            while (i < s.length && !JapaneseText.isPunctuationOrSpace(s.codePointAt(i))) i += Character.charCount(s.codePointAt(i))
            if (i > start) {
                val span = CharSpan(start, i)
                val surface = s.substring(start, i)
                val chunkRuby = RubyBuilder.sliceRuby(ruby, span)
                chunks += SemanticChunk(
                    id = "c${chunks.size}",
                    span = span,
                    alignment = SpanAlignment.EXACT,
                    surface = surface,
                    reading = chunkRuby.joinToString("") { it.ruby ?: it.base },
                    ruby = chunkRuby,
                    textbookForm = surface,
                    role = "离线分段 Offline phrase",
                    meaning = "配置 AI 服务后可查看逐词解析",
                )
            }
        }
        return SentenceExplanation(
            id = "offline_" + ExplainCache.sha256Hex(s).take(16),
            sentence = s,
            ruby = ruby,
            translation = req.officialCnTranslation?.takeIf { it.isNotBlank() } ?: "（离线模式：尚未配置 AI 服务）",
            officialCnTranslation = req.officialCnTranslation,
            chunks = chunks,
            speakerJp = req.speakerJp,
            speakerCn = req.speakerCn,
            model = MODEL,
            promptVersion = PROMPT_VERSION,
            createdAt = now,
        )
    }
}
