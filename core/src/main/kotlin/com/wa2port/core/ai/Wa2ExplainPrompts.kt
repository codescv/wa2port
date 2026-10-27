package com.wa2port.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Prompt builders (port of kotori `ai/prompts.ts`, specialised for WHITE ALBUM 2 and zh-CN output).
 * Bumping PROMPT_VERSION changes every cache key, so prompt upgrades invalidate stale explanations.
 */

const val PROMPT_VERSION = "wa2-v1"
const val TARGET_LANGUAGE = "zh-CN"
private const val TARGET_LANGUAGE_NAME = "Simplified Chinese (简体中文)"

/** One conversation turn sent to Gemini. [role] is `user` | `model`. */
data class GeminiContent(val role: String, val text: String)

/** System instruction + conversation. */
data class PromptParts(val systemInstruction: String, val contents: List<GeminiContent>)

/** Nudge appended after an invalid JSON answer (one retry). */
const val INVALID_JSON_NUDGE =
    "Your previous answer was not valid JSON for the required schema. Return ONLY one valid JSON object matching the schema, with no prose and no code fences."

/** Round-trip prompt of the connection test. */
const val TEST_PROMPT = "Reply with the single word: OK"

/** Max backlog lines given as context. */
const val MAX_CONTEXT_LINES = 3

/** System instruction for the sentence breakdown: kotori's 10 rules + WA2 additions. */
fun explainSystemInstruction(): String = listOf(
    "You are an expert Japanese teacher who explains real, spoken Japanese from anime, manga and visual novels to learners.",
    "Break the TARGET sentence down and answer ONLY with JSON matching the response schema.",
    "",
    "Rules:",
    "1. `chunks` are semantic chunks (意群/phrases) that cover the TARGET sentence from left to right, in order, without gaps or overlaps (punctuation may be left out).",
    "2. Every `surface` (chunk and vocab) MUST be a verbatim substring of the TARGET sentence, copied character-for-character (same kana/kanji, same width, same small kana, same long-vowel marks). Never normalise, never correct typos, never output indices.",
    "3. Each vocab `surface` lies inside its chunk's `surface`; list vocab in left-to-right order. `lemma` is the dictionary form.",
    "4. `ruby` segments the WHOLE TARGET sentence: concatenating every `base` must reproduce the sentence exactly. Give `ruby` (hiragana reading) ONLY on segments containing kanji; omit it for kana, Latin, digits and punctuation. Prefer short kanji-run segments (e.g. 食|べて, not 食べて).",
    "5. `textbookForm` is always present: the standard/textbook form of the chunk (e.g. surface てんじゃねぇ → textbookForm ているのではない). If the chunk is already standard, repeat the surface.",
    "6. `spokenEvolution` ONLY when surface differs from textbookForm: `kind` (short label such as 縮約, 音便, 俗语, 方言), `derivation` as steps \"A → B → C\", `formality` from 0 (very rough) to 1 (formal). Omit it otherwise.",
    "7. Write every explanation field (translation, literalTranslation, officialTranslationNote, tone, role, meaning, nuance, derivation, conjugation, notes, grammar explanation) in $TARGET_LANGUAGE_NAME.",
    "8. Context lines are for understanding only. Explain ONLY the TARGET sentence.",
    "9. `grammar.chunkSurfaces` lists the chunk surfaces (verbatim) that instantiate each pattern; write `pattern` in textbook notation such as 〜ばかりに.",
    "10. Add `literalTranslation` only when it differs meaningfully from `translation`.",
    "",
    "WHITE ALBUM 2 notes:",
    "- The TARGET sentence is a line from the visual novel WHITE ALBUM 2 (Leaf, 2010-2011): spoken dialogue or the inner monologue of 北原春希. The speaker, when known, is given; treat lines without a speaker as narration/monologue.",
    "- If an official fan Chinese translation (CK-GAL) is provided, use it only to disambiguate meaning; still write your own natural `translation`. When the official translation omits, softens or changes a nuance worth learning, explain it briefly in `officialTranslationNote` (简体中文); otherwise omit that field.",
    "- Script furigana may appear in the sentence as plain kanji; always produce `ruby` yourself.",
).joinToString("\n")

/** Builds the explain prompt; context lines are clearly separated from the verbatim TARGET sentence. */
fun buildExplainPrompt(req: ExplainRequest): PromptParts {
    val lines = ArrayList<String>()
    req.sceneHint?.takeIf { it.isNotBlank() }?.let { lines += "Scene: $it" }
    val jp = req.speakerJp?.trim().orEmpty()
    val cn = req.speakerCn?.trim().orEmpty()
    lines += when {
        jp.isNotEmpty() && cn.isNotEmpty() && cn != jp -> "Speaker: $jp ($cn)"
        jp.isNotEmpty() -> "Speaker: $jp"
        cn.isNotEmpty() -> "Speaker: $cn"
        else -> "Speaker: (narration / inner monologue)"
    }
    val ctx = req.contextBefore.filter { it.isNotBlank() }.takeLast(MAX_CONTEXT_LINES)
    if (ctx.isNotEmpty()) {
        lines += "Context before (do not explain):"
        ctx.forEach { lines += "- $it" }
    }
    req.officialCnTranslation?.takeIf { it.isNotBlank() }?.let {
        lines += "Official Chinese translation (CK-GAL, reference only): ${it.trim()}"
    }
    val script = req.scriptRuby.filter { it.span.start >= 0 && it.span.end <= req.sentence.length && !it.span.isEmpty }
    if (script.isNotEmpty()) {
        lines += "Script furigana (authoritative, keep these readings): " +
            script.joinToString(", ") { "${req.sentence.substring(it.span.start, it.span.end)}=${it.reading}" }
    }
    val f = req.focusCharIndex
    if (f != null && f >= 0 && f < req.sentence.length) {
        // Whole code point (surrogate pairs stay intact).
        val end = if (Character.isHighSurrogate(req.sentence[f]) && f + 1 < req.sentence.length) f + 2 else f + 1
        val ch = req.sentence.substring(f, end)
        lines += "The learner tapped the character 「$ch」 (pay extra attention to the chunk containing it, but still explain the whole sentence)."
    }
    lines += listOf("TARGET sentence (between the markers, verbatim):", "<<<", req.sentence, ">>>")
    return PromptParts(explainSystemInstruction(), listOf(GeminiContent("user", lines.joinToString("\n"))))
}

/** Compact JSON summary of an explanation used as follow-up context. */
fun summariseExplanation(expl: SentenceExplanation): String {
    val obj: JsonObject = buildJsonObject {
        put("sentence", expl.sentence)
        putOpt("speaker", expl.speakerJp)
        put("translation", expl.translation)
        putOpt("literalTranslation", expl.literalTranslation)
        putOpt("officialCnTranslation", expl.officialCnTranslation)
        putOpt("officialTranslationNote", expl.officialTranslationNote)
        expl.register?.let { put("register", it.wire) }
        putOpt("tone", expl.tone)
        put(
            "chunks",
            JsonArray(
                expl.chunks.map { c ->
                    buildJsonObject {
                        put("surface", c.surface)
                        put("reading", c.reading)
                        put("textbookForm", c.textbookForm)
                        c.spokenEvolution?.let { e ->
                            put(
                                "spokenEvolution",
                                buildJsonObject {
                                    put("kind", e.kind)
                                    put("derivation", e.derivation)
                                    e.formality?.let { put("formality", it) }
                                },
                            )
                        }
                        put("role", c.role)
                        put("meaning", c.meaning)
                        putOpt("nuance", c.nuance)
                    }
                },
            ),
        )
        put(
            "vocab",
            buildJsonArray {
                expl.vocab.forEach { v ->
                    add(
                        buildJsonObject {
                            put("surface", v.surface)
                            put("lemma", v.lemma)
                            put("reading", v.lemmaReading)
                            put("pos", v.partOfSpeech)
                            put("meaning", v.meaning)
                            putOpt("conjugation", v.conjugation)
                        },
                    )
                }
            },
        )
        put(
            "grammar",
            JsonArray(expl.grammar.map { g -> buildJsonObject { put("pattern", g.pattern); put("explanation", g.explanation) } }),
        )
    }
    return obj.toString()
}

/** Follow-up system instruction: kotori's teacher prompt + persona name/soul + accuracy clause + breakdown JSON. */
fun followUpSystemInstruction(expl: SentenceExplanation, persona: AiPersonaSettings): String {
    val lines = ArrayList<String>()
    lines += "You are a friendly, precise Japanese teacher answering follow-up questions about one sentence a learner is studying (from the visual novel WHITE ALBUM 2)."
    val name = persona.name.trim()
    if (name.isNotEmpty()) lines += "Your name/nickname is \"$name\"."
    val soul = persona.soul.trim()
    lines += "Your persona / soul (stay in character while keeping Japanese grammatical and lexical explanations accurate):\n" +
        (if (soul.isNotEmpty()) "$soul\n" else "") + AiPersonaSettings.ACCURACY_CLAUSE
    lines += "Answer in $TARGET_LANGUAGE_NAME. Be concise. Markdown-light only: **bold**, lists and `inline code`; no HTML, no headings, no tables."
    lines += "Quote Japanese verbatim. If the question is unrelated to Japanese learning, answer briefly and steer back."
    lines += ""
    lines += "Breakdown of the sentence being discussed (JSON):"
    lines += summariseExplanation(expl)
    return lines.joinToString("\n")
}

/** Follow-up conversation: prior thread (oldest first, blank turns dropped) then the new question. */
fun buildFollowUpPrompt(
    expl: SentenceExplanation,
    history: List<FollowUpMessage>,
    question: String,
    persona: AiPersonaSettings,
): PromptParts {
    val contents = history.filter { it.text.isNotBlank() }.map {
        GeminiContent(if (it.role == FollowUpMessage.ROLE_MODEL) "model" else "user", it.text)
    } + GeminiContent("user", question)
    return PromptParts(followUpSystemInstruction(expl, persona), contents)
}

/** Formats a backlog line for [ExplainRequest.contextBefore]: `話者: 台詞` (or just the line). */
fun formatContextLine(speaker: String?, text: String): String {
    val t = text.replace('\n', ' ').trim()
    val s = speaker?.trim().orEmpty()
    return if (s.isEmpty()) t else "$s: $t"
}

/** Puts [v] only when it is non-blank. */
private fun JsonObjectBuilder.putOpt(k: String, v: String?) {
    if (!v.isNullOrBlank()) put(k, v)
}
