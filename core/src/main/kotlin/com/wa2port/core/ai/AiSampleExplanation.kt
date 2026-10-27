package com.wa2port.core.ai

/**
 * Hand-written, fully aligned sample breakdown used by the settings preview (示例预览), the
 * not-configured state of the AI sheet and the unit tests. Built through the same assembly path as
 * real model output ([ExplainJsonCodec.toSentenceExplanation]), so spans are guaranteed to match.
 */
object AiSampleExplanation {
    const val SENTENCE = "「……あ、雪だね。今年の冬も、きっと寒くなるね」"
    const val SPEAKER_JP = "雪菜"
    const val SPEAKER_CN = "雪菜"
    const val OFFICIAL_CN = "「……啊，下雪了呢。今年的冬天，好像也会很冷吧。」"
    const val MODEL = "sample"

    private fun r(base: String, ruby: String? = null) = RawRuby(base, ruby)

    /** The raw model output the sample is built from (also a JSON-codec test fixture). */
    val RAW: RawExplanation = RawExplanation(
        translation = "……啊，下雪了呢。今年冬天，也一定会很冷吧。",
        literalTranslation = "……啊，是雪呢。今年的冬天也，一定，会变冷呢。",
        officialTranslationNote = "官方汉化把「きっと」（一定、肯定）译成了「好像」，语气变弱了；原文是雪菜很笃定的预感。",
        register = RegisterTag.CASUAL,
        tone = "轻声的自言自语，带一点感慨",
        ruby = listOf(
            r("「……あ、"), r("雪", "ゆき"), r("だね。"), r("今年", "ことし"), r("の"), r("冬", "ふゆ"),
            r("も、きっと"), r("寒", "さむ"), r("くなるね」"),
        ),
        chunks = listOf(
            RawChunk(
                surface = "あ", reading = "あ", textbookForm = "あ", spokenEvolution = null,
                role = "感叹词", meaning = "啊（突然注意到什么）", nuance = "前面的「……」表示停顿、出神",
                vocab = listOf(RawVocab("あ", "あ", "あ", emptyList(), "interjection", "啊", null, "N5", null, null)),
            ),
            RawChunk(
                surface = "雪だね", reading = "ゆきだね", textbookForm = "雪ですね",
                spokenEvolution = RawSpokenEvolution("常体", "雪ですね → 雪だね（です → だ）", 0.4f),
                role = "谓语（名词 + 断定 + 终助词）", meaning = "是雪呢", nuance = "「だね」是熟人之间的随意口吻",
                vocab = listOf(
                    RawVocab("雪", "雪", "ゆき", listOf(r("雪", "ゆき")), "noun", "雪", null, "N5", 2f, null),
                    RawVocab("だ", "だ", "だ", emptyList(), "auxiliary", "断定助动词（です的常体）", null, "N5", null, null),
                    RawVocab("ね", "ね", "ね", emptyList(), "particle", "终助词：寻求共鸣", null, "N5", null, null),
                ),
            ),
            RawChunk(
                surface = "今年の冬も", reading = "ことしのふゆも", textbookForm = "今年の冬も", spokenEvolution = null,
                role = "主题（名词短语 + も）", meaning = "今年的冬天也", nuance = null,
                vocab = listOf(
                    RawVocab("今年", "今年", "ことし", listOf(r("今年", "ことし")), "noun", "今年", null, "N5", 0f, null),
                    RawVocab("冬", "冬", "ふゆ", listOf(r("冬", "ふゆ")), "noun", "冬天", null, "N5", 2f, null),
                    RawVocab("も", "も", "も", emptyList(), "particle", "也", null, "N5", null, null),
                ),
            ),
            RawChunk(
                surface = "きっと", reading = "きっと", textbookForm = "きっと", spokenEvolution = null,
                role = "副词（推测）", meaning = "一定、肯定", nuance = "说话人很有把握的推测",
                vocab = listOf(RawVocab("きっと", "きっと", "きっと", emptyList(), "adverb", "一定", null, "N4", 0f, null)),
            ),
            RawChunk(
                surface = "寒くなるね", reading = "さむくなるね", textbookForm = "寒くなりますね",
                spokenEvolution = RawSpokenEvolution("常体", "寒くなりますね → 寒くなるね（ます形 → 辞书形）", 0.4f),
                role = "谓语（い形容词连用形 + なる + ね）", meaning = "会变冷呢", nuance = null,
                vocab = listOf(
                    RawVocab("寒く", "寒い", "さむい", listOf(r("寒", "さむ"), r("い")), "i-adjective", "冷的", "寒い → 寒く + なる", "N5", 2f, null),
                    RawVocab("なる", "なる", "なる", emptyList(), "verb", "变成", null, "N5", 1f, null),
                    RawVocab("ね", "ね", "ね", emptyList(), "particle", "终助词：寻求共鸣", null, "N5", null, null),
                ),
            ),
        ),
        grammar = listOf(
            RawGrammar("〜くなる", listOf("寒くなるね"), "い形容词去「い」加「く」再接「なる」，表示状态变化：变得……", "N5"),
            RawGrammar("終助詞「ね」", listOf("雪だね", "寒くなるね"), "句末的「ね」向对方寻求共鸣或确认，语气柔和。", "N5"),
        ),
    )

    /** The sample request (speaker, CK-GAL line, scene hint). */
    fun request(focusCharIndex: Int? = null): ExplainRequest = ExplainRequest(
        sentence = SENTENCE,
        contextBefore = listOf("春希: ……寒いな", "雪菜: うん"),
        speakerJp = SPEAKER_JP,
        speakerCn = SPEAKER_CN,
        officialCnTranslation = OFFICIAL_CN,
        sceneHint = "WHITE ALBUM 2 · 示例 Sample",
        focusCharIndex = focusCharIndex,
    )

    /** The sample breakdown. */
    fun build(now: Long = 0L): SentenceExplanation = ExplainJsonCodec.toSentenceExplanation(
        RAW,
        request(),
        ExplanationMeta(id = ExplainCache.key(request(), MODEL), model = MODEL, now = now),
    )
}
