package com.wa2port.core.ai

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/*
 * Data contract of the Kotori-style AI sentence breakdown (port of kotori `packages/core/src/types.ts`).
 *
 * All indices are UTF-16 code-unit offsets into [SentenceExplanation.sentence] (Kotlin `String`
 * indexing, identical to JS `string.length` semantics used by kotori). Spans are half-open.
 */

/** Half-open `[start, end)` range in the explained sentence. */
@Serializable
data class CharSpan(val start: Int, val end: Int) {
    val isEmpty: Boolean get() = end <= start
    val length: Int get() = (end - start).coerceAtLeast(0)
    operator fun contains(i: Int): Boolean = i in start until end
}

/** How a model-provided `surface` was located in the sentence (see [SpanAlign.alignSpans]). */
@Serializable
enum class SpanAlignment { EXACT, FUZZY, UNALIGNED }

/** One furigana unit; concatenating [base] over a list reproduces the covered text. [ruby] only on kanji. */
@Serializable
data class RubyToken(val base: String, val ruby: String? = null, val span: CharSpan)

/** 🔥 How the textbook form evolved into the spoken surface (contraction, sound change, slang…). */
@Serializable
data class SpokenEvolution(
    /** Short label, e.g. 縮約 / 音便 / 俗语 / 方言 / 省略. */
    val kind: String,
    /** Step-by-step derivation `A → B → C` (zh-CN prose). */
    val derivation: String,
    /** 0 (very rough) .. 1 (formal); clamped. */
    val formality: Float? = null,
) {
    /** 1..5 bar for the UI: `1 + round(formality × 4)`; 3 when unknown. */
    val formalityLevel: Int
        get() = formality?.let { 1 + (it.coerceIn(0f, 1f) * 4f).roundToInt() } ?: 3
}

/** A semantic chunk (意群) of the sentence with its 📘 textbook form and optional 🔥 spoken evolution. */
@Serializable
data class SemanticChunk(
    val id: String,
    val span: CharSpan,
    val alignment: SpanAlignment,
    val surface: String,
    val reading: String,
    val ruby: List<RubyToken>,
    /** 📘 always present; equals [surface] when already standard. */
    val textbookForm: String,
    /** 🔥 only when [surface] differs from [textbookForm]. */
    val spokenEvolution: SpokenEvolution? = null,
    val role: String,
    val meaning: String,
    val nuance: String? = null,
    val vocabIds: List<String> = emptyList(),
) {
    val isEvolved: Boolean get() = spokenEvolution != null
}

/** One vocabulary item (dictionary form, reading, POS, JLPT, pitch accent…). */
@Serializable
data class VocabEntry(
    val id: String,
    val span: CharSpan,
    val alignment: SpanAlignment,
    val surface: String,
    val lemma: String,
    val lemmaReading: String,
    val lemmaRuby: List<RubyToken> = emptyList(),
    /** One of [PARTS_OF_SPEECH]; `other` fallback. */
    val partOfSpeech: String = "other",
    val meaning: String,
    val conjugation: String? = null,
    /** `N5`..`N1`. */
    val jlpt: String? = null,
    val pitchAccent: Int? = null,
    val notes: String? = null,
)

/** A grammar pattern instantiated by one or more chunks. */
@Serializable
data class GrammarPoint(
    val id: String,
    val pattern: String,
    val chunkIds: List<String> = emptyList(),
    val explanation: String,
    val jlpt: String? = null,
)

/** Speech register of the whole sentence. */
@Serializable
enum class RegisterTag(val wire: String, val labelCn: String) {
    POLITE("polite", "丁宁体"),
    PLAIN("plain", "常体"),
    CASUAL("casual", "随意"),
    ROUGH("rough", "粗野"),
    HONORIFIC("honorific", "尊敬语"),
    HUMBLE("humble", "谦让语"),
    DIALECT("dialect", "方言"),
    LITERARY("literary", "书面语"),
    ;

    companion object {
        /** Lower-cased / trimmed wire value → tag; null when unknown. */
        fun parse(s: String?): RegisterTag? {
            val k = s?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == k || it.name.lowercase() == k }
        }
    }
}

@Serializable
data class TokenUsage(val promptTokens: Int, val outputTokens: Int, val cachedTokens: Int? = null)

/** The full breakdown of one sentence. */
@Serializable
data class SentenceExplanation(
    /** [ExplainCache.key] of the request. */
    val id: String,
    /** Verbatim [ExplainRequest.sentence]; all spans index into this. */
    val sentence: String,
    /** Whole sentence; bases concatenate to [sentence]. */
    val ruby: List<RubyToken>,
    val translation: String,
    val literalTranslation: String? = null,
    /** CK-GAL text passed in the request (stored for the UI). */
    val officialCnTranslation: String? = null,
    /** Model's 对照说明 between its translation and the official one (WA2 schema addition). */
    val officialTranslationNote: String? = null,
    val chunks: List<SemanticChunk> = emptyList(),
    val vocab: List<VocabEntry> = emptyList(),
    val grammar: List<GrammarPoint> = emptyList(),
    val register: RegisterTag? = null,
    val tone: String? = null,
    val speakerJp: String? = null,
    val speakerCn: String? = null,
    val model: String,
    val promptVersion: String,
    val createdAt: Long,
    val fromCache: Boolean = false,
    val usage: TokenUsage? = null,
)

/** One turn of the follow-up Q&A thread. */
@Serializable
data class FollowUpMessage(
    val id: String,
    /** `user` | `model`. */
    val role: String,
    val text: String,
    val createdAt: Long,
    val usage: TokenUsage? = null,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_MODEL = "model"
    }
}

/** Tutor persona used for follow-up answers. */
@Serializable
data class AiPersonaSettings(val id: String, val name: String, val soul: String) {
    /** Short label for chips. */
    val shortName: String get() = name.substringBefore('（').substringBefore('(')

    companion object {
        /** Appended to every soul by the prompt builder. */
        const val ACCURACY_CLAUSE = "无论何种人设，日语语法与词汇解释必须准确；用简体中文回答；日文原文逐字引用。"

        /** Id of the user-customisable persona (name + soul edited in settings). */
        const val ID_CUSTOM = "custom"
        const val DEFAULT_CUSTOM_NAME = "小鳥"
        const val DEFAULT_CUSTOM_SOUL =
            "温柔耐心的日语家教。语气亲切、鼓励学习者，常用「〜ですね」「一緒に見てみましょう」这类表达。解释先给结论再给例句，简洁。"

        /** The custom persona with its default name/soul. */
        val CUSTOM = AiPersonaSettings(ID_CUSTOM, DEFAULT_CUSTOM_NAME, DEFAULT_CUSTOM_SOUL)

        /** Legacy alias: the former hardcoded Kotori preset is now the default custom persona. */
        val KOTORI = CUSTOM
        val KAZUSA = AiPersonaSettings(
            "kazusa", "冬马和纱",
            "冬马和纱。口吻直率、略带傲娇、话少，偶尔用钢琴/音乐作比喻，会说「…別に」「ちゃんと聞いてる？」。讲解虽冷淡但一针见血、准确。",
        )
        val SETSUNA = AiPersonaSettings(
            "setsuna", "小木曾雪菜",
            "小木曾雪菜。开朗体贴、爱笑，会鼓励人、偶尔提到唱歌和社团；用词温和，像学姐在旁边陪着学习。",
        )
        val PRESETS = listOf(KAZUSA, SETSUNA, CUSTOM)

        /** Custom persona with [name]/[soul]; blank values fall back to the defaults. */
        fun custom(name: String, soul: String): AiPersonaSettings = AiPersonaSettings(
            ID_CUSTOM,
            name.trim().ifEmpty { DEFAULT_CUSTOM_NAME },
            soul.trim().ifEmpty { DEFAULT_CUSTOM_SOUL },
        )

        /**
         * Persona for a stored [id]: the built-in characters by id; `custom`, legacy `kotori`,
         * unknown or null ids all resolve to the user's custom persona.
         */
        fun resolve(id: String?, customName: String = "", customSoul: String = ""): AiPersonaSettings = when (id) {
            KAZUSA.id -> KAZUSA
            SETSUNA.id -> SETSUNA
            else -> custom(customName, customSoul)
        }

        fun byId(id: String?): AiPersonaSettings = resolve(id)
    }
}

/** Input of [GeminiAiClient.explainSentence]. */
data class ExplainRequest(
    /** Plain Japanese sentence ([JpSentence.text]). */
    val sentence: String,
    /** `話者: 台詞` strings, oldest first, ≤ 3. */
    val contextBefore: List<String> = emptyList(),
    val speakerJp: String? = null,
    val speakerCn: String? = null,
    /** CK-GAL CN line (plain, `\n` → space). */
    val officialCnTranslation: String? = null,
    /** e.g. `WHITE ALBUM 2 · scene 1001 (IC)`. */
    val sceneHint: String? = null,
    val focusCharIndex: Int? = null,
    /** Script furigana of the sentence ([JpSentence.scriptRuby]); authoritative readings. */
    val scriptRuby: List<ScriptRuby> = emptyList(),
    val persona: AiPersonaSettings = AiPersonaSettings.KOTORI,
    /** Bypass the cache. */
    val force: Boolean = false,
)

data class AiConnectionTestResult(val ok: Boolean, val model: String, val latencyMs: Long, val message: String)

enum class AiErrorCode { NOT_CONFIGURED, AUTH, QUOTA, TIMEOUT, NETWORK, INVALID_ARGUMENT, NOT_FOUND, SAFETY, INVALID_RESPONSE, CANCELLED }

class AiException(
    val code: AiErrorCode,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : RuntimeException(message, cause)

/** Kotori's 17 coarse part-of-speech classes. */
val PARTS_OF_SPEECH: List<String> = listOf(
    "noun", "verb", "i-adjective", "na-adjective", "adverb", "particle", "auxiliary", "conjunction",
    "pronoun", "counter", "interjection", "expression", "prefix", "suffix", "proper-noun", "onomatopoeia", "other",
)

val JLPT_LEVELS: List<String> = listOf("N5", "N4", "N3", "N2", "N1")

/** zh-CN label for a [PARTS_OF_SPEECH] value (UI chips). */
fun partOfSpeechLabel(pos: String): String = when (pos) {
    "noun" -> "名词"
    "verb" -> "动词"
    "i-adjective" -> "い形容词"
    "na-adjective" -> "な形容词"
    "adverb" -> "副词"
    "particle" -> "助词"
    "auxiliary" -> "助动词"
    "conjunction" -> "接续词"
    "pronoun" -> "代词"
    "counter" -> "量词"
    "interjection" -> "感叹词"
    "expression" -> "惯用语"
    "prefix" -> "接头词"
    "suffix" -> "接尾词"
    "proper-noun" -> "专有名词"
    "onomatopoeia" -> "拟声拟态"
    else -> "其他"
}
