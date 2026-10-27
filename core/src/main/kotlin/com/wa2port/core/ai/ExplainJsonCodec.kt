package com.wa2port.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Lenient JSON used for cache files and settings. */
val AiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

// ------------------------------------------------------------------------------------- raw model output

/** Raw model ruby unit. */
data class RawRuby(val base: String, val ruby: String?)
data class RawSpokenEvolution(val kind: String, val derivation: String, val formality: Float?)
data class RawVocab(
    val surface: String, val lemma: String, val lemmaReading: String, val lemmaRuby: List<RawRuby>,
    val partOfSpeech: String, val meaning: String, val conjugation: String?, val jlpt: String?,
    val pitchAccent: Float?, val notes: String?,
)
data class RawChunk(
    val surface: String, val reading: String, val textbookForm: String, val spokenEvolution: RawSpokenEvolution?,
    val role: String, val meaning: String, val nuance: String?, val vocab: List<RawVocab>,
)
data class RawGrammar(val pattern: String, val chunkSurfaces: List<String>, val explanation: String, val jlpt: String?)

/** Validated, tolerant mirror of kotori's `RawExplanationSchema` (+ WA2 `officialTranslationNote`). */
data class RawExplanation(
    val translation: String,
    val literalTranslation: String?,
    val officialTranslationNote: String?,
    val register: RegisterTag?,
    val tone: String?,
    val ruby: List<RawRuby>,
    val chunks: List<RawChunk>,
    val grammar: List<RawGrammar>,
)

/** Metadata stamped onto a [SentenceExplanation]. */
data class ExplanationMeta(
    val id: String,
    val model: String,
    val usage: TokenUsage? = null,
    val now: Long,
    val promptVersion: String = PROMPT_VERSION,
    val fromCache: Boolean = false,
)

// ------------------------------------------------------------------------------------- Gemini wire types

/** Provider-neutral request accepted by [GeminiAiClient.generate]. */
data class GeminiRequest(
    val model: String,
    val systemInstruction: String?,
    val contents: List<GeminiContent>,
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
    val temperature: Float? = null,
    /** −1/null = model default; ≥ 0 sent as `thinkingConfig.thinkingBudget`. */
    val thinkingBudget: Int? = null,
    val maxOutputTokens: Int? = null,
    val timeoutMs: Int = 60_000,
)

data class GeminiResponse(val text: String, val model: String, val finishReason: String?, val usage: TokenUsage?)

/** Model output parsing, schema, explanation assembly and Gemini REST (de)serialisation. */
object ExplainJsonCodec {
    private val SAFETY_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY")

    // ---------------------------------------------------------------------------------- schema

    private fun str() = buildJsonObject { put("type", "STRING") }
    private fun strings(vararg v: String) = JsonArray(v.map { JsonPrimitive(it) })
    private fun rubyItems() = buildJsonObject {
        put("type", "ARRAY")
        putJsonObject("items") {
            put("type", "OBJECT")
            putJsonObject("properties") { put("base", str()); put("ruby", str()) }
            put("required", strings("base"))
            put("propertyOrdering", strings("base", "ruby"))
        }
    }

    /** Gemini `responseSchema` (OpenAPI subset): kotori's explain schema + optional `officialTranslationNote`. */
    val EXPLAIN_RESPONSE_SCHEMA: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            put("translation", str())
            put("literalTranslation", str())
            put("officialTranslationNote", str())
            putJsonObject("register") {
                put("type", "STRING")
                put("enum", JsonArray(RegisterTag.entries.map { JsonPrimitive(it.wire) }))
            }
            put("tone", str())
            put("ruby", rubyItems())
            putJsonObject("chunks") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        put("surface", str())
                        put("reading", str())
                        put("textbookForm", str())
                        putJsonObject("spokenEvolution") {
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                put("kind", str())
                                put("derivation", str())
                                putJsonObject("formality") { put("type", "NUMBER") }
                            }
                            put("required", strings("kind", "derivation"))
                            put("propertyOrdering", strings("kind", "derivation", "formality"))
                        }
                        put("role", str())
                        put("meaning", str())
                        put("nuance", str())
                        putJsonObject("vocab") {
                            put("type", "ARRAY")
                            putJsonObject("items") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    put("surface", str())
                                    put("lemma", str())
                                    put("lemmaReading", str())
                                    put("lemmaRuby", rubyItems())
                                    putJsonObject("partOfSpeech") {
                                        put("type", "STRING")
                                        put("enum", JsonArray(PARTS_OF_SPEECH.map { JsonPrimitive(it) }))
                                    }
                                    put("meaning", str())
                                    put("conjugation", str())
                                    putJsonObject("jlpt") {
                                        put("type", "STRING")
                                        put("enum", JsonArray(JLPT_LEVELS.map { JsonPrimitive(it) }))
                                    }
                                    putJsonObject("pitchAccent") { put("type", "INTEGER") }
                                    put("notes", str())
                                }
                                put("required", strings("surface", "lemma", "lemmaReading", "lemmaRuby", "partOfSpeech", "meaning"))
                                put(
                                    "propertyOrdering",
                                    strings("surface", "lemma", "lemmaReading", "lemmaRuby", "partOfSpeech", "meaning", "conjugation", "jlpt", "pitchAccent", "notes"),
                                )
                            }
                        }
                    }
                    put("required", strings("surface", "reading", "textbookForm", "role", "meaning", "vocab"))
                    put("propertyOrdering", strings("surface", "reading", "textbookForm", "spokenEvolution", "role", "meaning", "nuance", "vocab"))
                }
            }
            putJsonObject("grammar") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        put("pattern", str())
                        putJsonObject("chunkSurfaces") { put("type", "ARRAY"); put("items", str()) }
                        put("explanation", str())
                        putJsonObject("jlpt") {
                            put("type", "STRING")
                            put("enum", JsonArray(JLPT_LEVELS.map { JsonPrimitive(it) }))
                        }
                    }
                    put("required", strings("pattern", "chunkSurfaces", "explanation"))
                    put("propertyOrdering", strings("pattern", "chunkSurfaces", "explanation", "jlpt"))
                }
            }
        }
        put("required", strings("translation", "ruby", "chunks", "grammar"))
        put(
            "propertyOrdering",
            strings("translation", "literalTranslation", "officialTranslationNote", "register", "tone", "ruby", "chunks", "grammar"),
        )
    }

    // ---------------------------------------------------------------------------------- tolerant parsing

    private val FENCE = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)

    /** Strips optional Markdown code fences and parses JSON; [AiException] INVALID_RESPONSE on failure. */
    fun parseModelJson(text: String): JsonElement {
        val trimmed = text.trim()
        val body = FENCE.find(trimmed)?.groupValues?.get(1) ?: trimmed
        if (body.isEmpty()) throw AiException(AiErrorCode.INVALID_RESPONSE, "模型返回了空内容 Empty model output")
        return try {
            AiJson.parseToJsonElement(body)
        } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "模型返回了无法解析的 JSON: ${e.message?.take(120)}", e)
        }
    }

    private fun JsonElement?.primitiveOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

    /** Required string: null/missing → "" (numbers/booleans stringified). */
    private fun JsonObject.reqString(k: String): String = this[k].primitiveOrNull()?.contentOrNull ?: ""

    /** Optional string: null/missing/blank → null. */
    private fun JsonObject.optString(k: String): String? = this[k].primitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Optional finite number; numeric strings accepted. */
    private fun JsonObject.optNumber(k: String): Float? {
        val p = this[k].primitiveOrNull() ?: return null
        if (p.booleanOrNull != null && !p.isString) return null
        return p.content.trim().toFloatOrNull()?.takeIf { it.isFinite() }
    }

    private fun JsonObject.array(k: String): List<JsonElement> = (this[k] as? JsonArray)?.toList() ?: emptyList()
    private fun JsonObject.objects(k: String): List<JsonObject> = array(k).filterIsInstance<JsonObject>()

    private fun normJlpt(v: String?): String? = v?.trim()?.uppercase()?.takeIf { it in JLPT_LEVELS }

    private val POS_SEP = Regex("[\\s_]+")

    /** Lower-cased, `[\s_]+` → `-`; unknown → `other`. */
    fun normPartOfSpeech(v: String?): String {
        val k = v?.trim()?.lowercase()?.replace(POS_SEP, "-") ?: return "other"
        return if (k in PARTS_OF_SPEECH) k else "other"
    }

    private fun rubyList(o: JsonObject, k: String) = o.objects(k).map { RawRuby(it.reqString("base"), it.optString("ruby")) }

    /** Tolerant parse of the model output; throws INVALID_RESPONSE only when the root is not an object or `translation` is missing. */
    fun parseRawExplanation(el: JsonElement): RawExplanation {
        val root = el as? JsonObject ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "模型输出不是 JSON 对象 Output is not an object")
        val translation = root["translation"].primitiveOrNull()?.contentOrNull
            ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "模型输出缺少 translation Missing translation")
        val chunks = root.objects("chunks").map { c ->
            val evo = (c["spokenEvolution"] as? JsonObject)?.let { e ->
                RawSpokenEvolution(e.reqString("kind"), e.reqString("derivation"), e.optNumber("formality"))
            }
            RawChunk(
                surface = c.reqString("surface"),
                reading = c.reqString("reading"),
                textbookForm = c.reqString("textbookForm"),
                spokenEvolution = evo,
                role = c.reqString("role"),
                meaning = c.reqString("meaning"),
                nuance = c.optString("nuance"),
                vocab = c.objects("vocab").map { v ->
                    RawVocab(
                        surface = v.reqString("surface"),
                        lemma = v.reqString("lemma"),
                        lemmaReading = v.reqString("lemmaReading"),
                        lemmaRuby = rubyList(v, "lemmaRuby"),
                        partOfSpeech = normPartOfSpeech(v.optString("partOfSpeech")),
                        meaning = v.reqString("meaning"),
                        conjugation = v.optString("conjugation"),
                        jlpt = normJlpt(v.optString("jlpt")),
                        pitchAccent = v.optNumber("pitchAccent"),
                        notes = v.optString("notes"),
                    )
                },
            )
        }
        val grammar = root.objects("grammar").map { g ->
            RawGrammar(
                pattern = g.reqString("pattern"),
                chunkSurfaces = g.array("chunkSurfaces").mapNotNull { it.primitiveOrNull()?.contentOrNull },
                explanation = g.reqString("explanation"),
                jlpt = normJlpt(g.optString("jlpt")),
            )
        }
        return RawExplanation(
            translation = translation,
            literalTranslation = root.optString("literalTranslation"),
            officialTranslationNote = root.optString("officialTranslationNote"),
            register = RegisterTag.parse(root.optString("register")),
            tone = root.optString("tone"),
            ruby = rubyList(root, "ruby"),
            chunks = chunks,
            grammar = grammar,
        )
    }

    // ---------------------------------------------------------------------------------- explanation assembly

    /** Spoken evolution is kept only when the surface really differs from the textbook form. */
    private fun evolutionFor(surface: String, textbookForm: String, raw: RawSpokenEvolution?): SpokenEvolution? {
        if (raw == null || (raw.kind.isBlank() && raw.derivation.isBlank())) return null
        if (JapaneseText.normalise(surface) == JapaneseText.normalise(textbookForm)) return null
        return SpokenEvolution(raw.kind, raw.derivation, raw.formality?.coerceIn(0f, 1f))
    }

    /** Grammar `chunkSurfaces` → chunk ids: exact, then normalised, then containment either way; deduped. */
    private fun resolveChunkIds(surfaces: List<String>, chunks: List<SemanticChunk>): List<String> {
        val ids = ArrayList<String>()
        for (s in surfaces) {
            if (s.isEmpty()) continue
            val n = JapaneseText.normalise(s)
            val hit = chunks.firstOrNull { it.surface == s }
                ?: chunks.firstOrNull { JapaneseText.normalise(it.surface) == n }
                ?: if (n.isNotEmpty()) {
                    chunks.firstOrNull {
                        val cn = JapaneseText.normalise(it.surface)
                        cn.isNotEmpty() && (cn.contains(n) || n.contains(cn))
                    }
                } else {
                    null
                }
            if (hit != null && hit.id !in ids) ids += hit.id
        }
        return ids
    }

    /**
     * Builds a [SentenceExplanation] from validated model output (port of kotori `explanation.ts`).
     * Chunks are aligned on the sentence; vocab is aligned inside its chunk and offset to sentence
     * coordinates. Ids are `c0..`, `v0..`, `g0..`. [ExplainRequest.scriptRuby] (authoritative script
     * furigana) overrides the model's readings on exactly those spans.
     */
    fun toSentenceExplanation(
        raw: RawExplanation,
        req: ExplainRequest,
        meta: ExplanationMeta,
    ): SentenceExplanation {
        val sentence = req.sentence
        val ruby = RubyBuilder.overlayScriptRuby(
            sentence,
            RubyBuilder.buildRuby(sentence, raw.ruby.map { RubyBuilder.RawRuby(it.base, it.ruby) }),
            req.scriptRuby,
        )
        val chunkAlign = SpanAlign.alignSpans(sentence, raw.chunks.map { it.surface })
        val chunks = ArrayList<SemanticChunk>()
        val vocab = ArrayList<VocabEntry>()
        raw.chunks.forEachIndexed { ci, rc ->
            val span = chunkAlign.spans[ci]
            val alignment = chunkAlign.alignment[ci]
            val surface = if (alignment == SpanAlignment.UNALIGNED) rc.surface else sentence.substring(span.start, span.end)
            val textbookForm = rc.textbookForm.trim().ifEmpty { surface }
            val chunkText = sentence.substring(span.start, span.end)
            val vAlign = SpanAlign.alignSpans(chunkText, rc.vocab.map { it.surface })
            val vocabIds = ArrayList<String>()
            rc.vocab.forEachIndexed { vi, rv ->
                val local = vAlign.spans[vi]
                val vAlignment = if (alignment == SpanAlignment.UNALIGNED) SpanAlignment.UNALIGNED else vAlign.alignment[vi]
                val vSpan = CharSpan(span.start + local.start, span.start + local.end)
                val id = "v${vocab.size}"
                vocabIds += id
                val lemma = rv.lemma.ifBlank { rv.surface }
                vocab += VocabEntry(
                    id = id,
                    span = vSpan,
                    alignment = vAlignment,
                    surface = if (vAlignment == SpanAlignment.UNALIGNED) rv.surface else sentence.substring(vSpan.start, vSpan.end),
                    lemma = lemma,
                    lemmaReading = rv.lemmaReading,
                    lemmaRuby = RubyBuilder.buildRuby(lemma, rv.lemmaRuby.map { RubyBuilder.RawRuby(it.base, it.ruby) }),
                    partOfSpeech = rv.partOfSpeech,
                    meaning = rv.meaning,
                    conjugation = rv.conjugation,
                    jlpt = rv.jlpt,
                    pitchAccent = rv.pitchAccent?.toInt(),
                    notes = rv.notes,
                )
            }
            chunks += SemanticChunk(
                id = "c$ci",
                span = span,
                alignment = alignment,
                surface = surface,
                reading = rc.reading,
                ruby = RubyBuilder.sliceRuby(ruby, span),
                textbookForm = textbookForm,
                spokenEvolution = evolutionFor(surface, textbookForm, rc.spokenEvolution),
                role = rc.role,
                meaning = rc.meaning,
                nuance = rc.nuance,
                vocabIds = vocabIds,
            )
        }
        val grammar = raw.grammar.mapIndexed { gi, g ->
            GrammarPoint(id = "g$gi", pattern = g.pattern, chunkIds = resolveChunkIds(g.chunkSurfaces, chunks), explanation = g.explanation, jlpt = g.jlpt)
        }
        return SentenceExplanation(
            id = meta.id,
            sentence = sentence,
            ruby = ruby,
            translation = raw.translation,
            literalTranslation = raw.literalTranslation,
            officialCnTranslation = req.officialCnTranslation?.takeIf { it.isNotBlank() },
            officialTranslationNote = raw.officialTranslationNote,
            chunks = chunks,
            vocab = vocab,
            grammar = grammar,
            register = raw.register,
            tone = raw.tone,
            speakerJp = req.speakerJp,
            speakerCn = req.speakerCn,
            model = meta.model,
            promptVersion = meta.promptVersion,
            createdAt = meta.now,
            fromCache = meta.fromCache,
            usage = meta.usage,
        )
    }

    // ---------------------------------------------------------------------------------- Gemini REST

    /** REST JSON body for `generateContent`. `thinkingConfig` only when the budget is ≥ 0. */
    fun buildGenerateBody(req: GeminiRequest): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                req.contents.forEach { c ->
                    add(
                        buildJsonObject {
                            put("role", c.role)
                            putJsonArray("parts") { add(buildJsonObject { put("text", c.text) }) }
                        },
                    )
                }
            }
            if (!req.systemInstruction.isNullOrEmpty()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", req.systemInstruction) }) }
                }
            }
            val gen = buildJsonObject {
                req.temperature?.let { put("temperature", it) }
                req.maxOutputTokens?.let { put("maxOutputTokens", it) }
                req.responseMimeType?.let { put("responseMimeType", it) }
                req.responseSchema?.let { put("responseSchema", it) }
                req.thinkingBudget?.takeIf { it >= 0 }?.let { b -> putJsonObject("thinkingConfig") { put("thinkingBudget", b) } }
            }
            if (gen.isNotEmpty()) put("generationConfig", gen)
        }
        return body.toString()
    }

    /**
     * Parses a `generateContent` response: concatenated non-thought text of candidate 0, safety
     * blocks → SAFETY, no candidates → INVALID_RESPONSE, usage (thinking tokens count as output).
     */
    fun parseGenerateResponse(body: String, requestedModel: String): GeminiResponse {
        val root = try {
            AiJson.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw AiException(AiErrorCode.INVALID_RESPONSE, "Gemini 返回了非 JSON 内容 Non-JSON body: ${body.take(120)}")
        val block = (root["promptFeedback"] as? JsonObject)?.optString("blockReason")
        if (block != null) throw AiException(AiErrorCode.SAFETY, "Prompt blocked: $block")
        val candidates = root.objects("candidates")
        val first = candidates.firstOrNull()
        val parts = ((first?.get("content") as? JsonObject)?.objects("parts")) ?: emptyList()
        val text = parts.filter { it["thought"].primitiveOrNull()?.booleanOrNull != true }
            .mapNotNull { it["text"].primitiveOrNull()?.contentOrNull }
            .joinToString("")
        val finish = first?.optString("finishReason")
        if (finish != null && finish in SAFETY_REASONS && text.isEmpty()) throw AiException(AiErrorCode.SAFETY, "Response blocked: $finish")
        if (first == null) throw AiException(AiErrorCode.INVALID_RESPONSE, "Gemini 未返回候选结果 No candidates")
        val usage = (root["usageMetadata"] as? JsonObject)?.let { u ->
            TokenUsage(
                promptTokens = u.optNumber("promptTokenCount")?.toInt() ?: 0,
                outputTokens = (u.optNumber("candidatesTokenCount")?.toInt() ?: 0) + (u.optNumber("thoughtsTokenCount")?.toInt() ?: 0),
                cachedTokens = u.optNumber("cachedContentTokenCount")?.toInt(),
            )
        }
        return GeminiResponse(text, root.optString("modelVersion") ?: requestedModel, finish, usage)
    }

    /** `error.message` of a Google API error body (array-wrapped tolerated), else the first 300 chars / `HTTP status`. */
    fun extractErrorMessage(body: String, status: Int): String {
        val msg = runCatching {
            var el = AiJson.parseToJsonElement(body)
            if (el is JsonArray) el = el.firstOrNull() ?: JsonNull
            ((el as? JsonObject)?.get("error") as? JsonObject)?.optString("message")
        }.getOrNull()
        if (!msg.isNullOrBlank()) return msg
        return body.take(300).ifBlank { "HTTP $status" }
    }

    private val AUTH_400 = Regex("API_KEY_INVALID|API key not valid|UNAUTHENTICATED", RegexOption.IGNORE_CASE)

    /** HTTP status (+ body) → error code. */
    fun mapHttpError(status: Int, body: String): AiErrorCode = when {
        status == 401 || status == 403 -> AiErrorCode.AUTH
        status == 429 -> AiErrorCode.QUOTA
        status == 400 && AUTH_400.containsMatchIn(body) -> AiErrorCode.AUTH
        status == 400 -> AiErrorCode.INVALID_ARGUMENT
        status == 404 -> AiErrorCode.NOT_FOUND
        status == 408 || status == 504 -> AiErrorCode.TIMEOUT
        else -> AiErrorCode.NETWORK
    }

    /** Thread / explanation JSON helpers (cache files). */
    fun encodeExplanation(e: SentenceExplanation): String = AiJson.encodeToString(SentenceExplanation.serializer(), e)
    fun decodeExplanation(s: String): SentenceExplanation = AiJson.decodeFromString(SentenceExplanation.serializer(), s)
    fun encodeSettings(s: AiProviderSettings): String = AiJson.encodeToString(AiProviderSettings.serializer(), s)
    fun decodeSettings(s: String): AiProviderSettings = AiJson.decodeFromString(AiProviderSettings.serializer(), s)
    fun encodeThread(t: List<FollowUpMessage>): String =
        buildJsonArray { t.forEach { add(AiJson.encodeToJsonElement(FollowUpMessage.serializer(), it)) } }.toString()
    fun decodeThread(s: String): List<FollowUpMessage> =
        (AiJson.parseToJsonElement(s) as? JsonArray)?.mapNotNull { runCatching { AiJson.decodeFromJsonElement(FollowUpMessage.serializer(), it) }.getOrNull() } ?: emptyList()
}
