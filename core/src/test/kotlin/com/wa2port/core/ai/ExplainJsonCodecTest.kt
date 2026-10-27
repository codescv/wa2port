package com.wa2port.core.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExplainJsonCodecTest {
    /** Model output for 「てめぇ、何してんだよ」 with quirks (nulls, "", odd enum casing, numeric strings). */
    private val fixture = """
        ```json
        {
          "translation": "你这家伙，在干什么啊",
          "literalTranslation": "",
          "officialTranslationNote": null,
          "register": " ROUGH ",
          "tone": "愤怒",
          "ruby": [{"base":"てめぇ、"},{"base":"何","ruby":"ナニ"},{"base":"してんだよ"}],
          "chunks": [
            {"surface":"てめぇ","reading":"てめぇ","textbookForm":"手前","spokenEvolution":{"kind":"音便","derivation":"てまえ → てめえ → てめぇ","formality":"0.1"},
             "role":"代词","meaning":"你这家伙","nuance":null,
             "vocab":[{"surface":"てめぇ","lemma":"手前","lemmaReading":"てまえ","lemmaRuby":[{"base":"手前","ruby":"てまえ"}],"partOfSpeech":"Pronoun","meaning":"你（粗鲁）","jlpt":"n1","pitchAccent":"0"}]},
            {"surface":"何してんだよ","reading":"なにしてんだよ","textbookForm":"何をしているのだよ","spokenEvolution":{"kind":"縮約","derivation":"している → してる → してん","formality":0.2},
             "role":"谓语","meaning":"在干什么",
             "vocab":[{"surface":"何","lemma":"何","lemmaReading":"なに","lemmaRuby":null,"partOfSpeech":"Proper Noun","meaning":"什么","jlpt":"N9"},
                      {"surface":"してん","lemma":"する","lemmaReading":"する","lemmaRuby":[],"partOfSpeech":"verb","meaning":"做","conjugation":"して+いる→してる→してん"}]},
            {"surface":"きえた","reading":"","textbookForm":"","role":"","meaning":"","vocab":[{"surface":"き","lemma":"","lemmaReading":"","partOfSpeech":null,"meaning":""}]}
          ],
          "grammar": [{"pattern":"〜てる","chunkSurfaces":["何してんだよ","何してんだよ"],"explanation":"ている的口语缩约","jlpt":"N4"},
                      {"pattern":"x","chunkSurfaces":null,"explanation":"y"}]
        }
        ```
    """.trimIndent()

    private val sentence = "てめぇ、何してんだよ"

    private fun build(): SentenceExplanation {
        val raw = ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson(fixture))
        return ExplainJsonCodec.toSentenceExplanation(
            raw,
            ExplainRequest(sentence, speakerJp = "武也", officialCnTranslation = "你在干嘛啊"),
            ExplanationMeta(id = "ex_test", model = "m", now = 42L),
        )
    }

    @Test
    fun tolerantRawParse() {
        val raw = ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson(fixture))
        assertNull(raw.literalTranslation) // "" → null
        assertNull(raw.officialTranslationNote)
        assertEquals(RegisterTag.ROUGH, raw.register)
        assertEquals(0.1f, raw.chunks[0].spokenEvolution!!.formality!!, 1e-6f)
        assertEquals("pronoun", raw.chunks[0].vocab[0].partOfSpeech)
        assertEquals("N1", raw.chunks[0].vocab[0].jlpt)
        assertEquals(0f, raw.chunks[0].vocab[0].pitchAccent)
        assertEquals("proper-noun", raw.chunks[1].vocab[0].partOfSpeech)
        assertNull(raw.chunks[1].vocab[0].jlpt) // unknown level dropped
        assertEquals("other", raw.chunks[2].vocab[0].partOfSpeech)
        assertEquals(emptyList<String>(), raw.grammar[1].chunkSurfaces)
        assertEquals("ナニ", raw.ruby[1].ruby)
    }

    @Test
    fun assemblesAlignedExplanation() {
        val e = build()
        assertEquals(sentence, e.ruby.joinToString("") { it.base })
        assertEquals("なに", e.ruby.first { it.base == "何" }.ruby)
        assertEquals(CharSpan(0, 3), e.chunks[0].span)
        assertEquals(CharSpan(4, 10), e.chunks[1].span)
        assertEquals(SpanAlignment.UNALIGNED, e.chunks[2].alignment)
        assertEquals("きえた", e.chunks[2].textbookForm) // blank → surface
        assertEquals(SpanAlignment.UNALIGNED, e.vocab.last().alignment) // vocab of an unaligned chunk
        assertEquals(listOf("v0"), e.chunks[0].vocabIds)
        assertEquals(listOf("v1", "v2"), e.chunks[1].vocabIds)
        assertEquals(CharSpan(4, 5), e.vocab[1].span)
        assertEquals(CharSpan(5, 8), e.vocab[2].span)
        assertEquals(1, e.chunks[0].spokenEvolution!!.formalityLevel)
        assertTrue(e.chunks[1].isEvolved)
        assertEquals(listOf("c1"), e.grammar[0].chunkIds) // deduped
        assertEquals("你在干嘛啊", e.officialCnTranslation)
        assertEquals("武也", e.speakerJp)
        assertEquals(PROMPT_VERSION, e.promptVersion)
        assertEquals("ex_test", e.id)
        assertEquals("手前", e.vocab[0].lemma)
        assertEquals("き", e.vocab.last().lemma) // blank lemma → surface
    }

    @Test
    fun evolutionDroppedWhenSurfaceIsStandard() {
        val raw = ExplainJsonCodec.parseRawExplanation(
            ExplainJsonCodec.parseModelJson(
                """{"translation":"t","chunks":[{"surface":"ハル","textbookForm":"はる","spokenEvolution":{"kind":"x","derivation":"y"}}],"ruby":[],"grammar":[]}""",
            ),
        )
        val e = ExplainJsonCodec.toSentenceExplanation(raw, ExplainRequest("ハル"), ExplanationMeta("id", "m", now = 0))
        assertNull(e.chunks[0].spokenEvolution)
    }

    @Test
    fun roundTripSerialisation() {
        for (e in listOf(build(), AiSampleExplanation.build(7L))) {
            val json = ExplainJsonCodec.encodeExplanation(e)
            assertEquals(e, ExplainJsonCodec.decodeExplanation(json))
        }
        val s = AiProviderSettings(geminiApiKey = "k", customBaseUrl = "https://p", temperature = 0.7f)
        assertEquals(s, ExplainJsonCodec.decodeSettings(ExplainJsonCodec.encodeSettings(s)))
        // Unknown keys / missing fields tolerated.
        assertEquals(AiProviderSettings(model = "x"), ExplainJsonCodec.decodeSettings("""{"model":"x","future":1}"""))
        val thread = listOf(FollowUpMessage("a", "user", "q", 1), FollowUpMessage("b", "model", "a", 2, TokenUsage(1, 2)))
        assertEquals(thread, ExplainJsonCodec.decodeThread(ExplainJsonCodec.encodeThread(thread)))
    }

    @Test
    fun invalidOutputs() {
        assertThrows(AiException::class.java) { ExplainJsonCodec.parseModelJson("not json at all {") }
        assertThrows(AiException::class.java) { ExplainJsonCodec.parseModelJson("   ") }
        val noTranslation = assertThrows(AiException::class.java) { ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson("""{"chunks":[]}""")) }
        assertEquals(AiErrorCode.INVALID_RESPONSE, noTranslation.code)
        assertThrows(AiException::class.java) { ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson("[1,2]")) }
    }

    @Test
    fun schemaShape() {
        val s = ExplainJsonCodec.EXPLAIN_RESPONSE_SCHEMA
        val order = s["propertyOrdering"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, order.count { it == "officialTranslationNote" })
        assertFalse(s["required"]!!.jsonArray.any { it.jsonPrimitive.content == "officialTranslationNote" })
        val props = s["properties"]!!.jsonObject
        assertEquals(17, props["chunks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["vocab"]!!.jsonObject["items"]!!
            .jsonObject["properties"]!!.jsonObject["partOfSpeech"]!!.jsonObject["enum"]!!.jsonArray.size)
    }

    @Test
    fun generateBody() {
        val body = ExplainJsonCodec.buildGenerateBody(
            GeminiRequest("m", "SYS", listOf(GeminiContent("user", "hi")), "application/json", ExplainJsonCodec.EXPLAIN_RESPONSE_SCHEMA, 0.2f, -1),
        )
        val o = AiJson.parseToJsonElement(body).jsonObject
        val gen = o["generationConfig"]!!.jsonObject
        assertFalse(gen.containsKey("thinkingConfig"))
        assertTrue(gen.containsKey("responseSchema"))
        assertEquals("application/json", gen["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("SYS", o["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("hi", o["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val withThinking = AiJson.parseToJsonElement(ExplainJsonCodec.buildGenerateBody(GeminiRequest("m", null, emptyList(), thinkingBudget = 0))).jsonObject
        assertEquals("0", withThinking["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        assertFalse(withThinking.containsKey("systemInstruction"))
    }

    @Test
    fun generateResponse() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"思考中","thought":true},{"text":"Hello "},{"text":"world"}]},"finishReason":"STOP"}],
            "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":4,"thoughtsTokenCount":5,"cachedContentTokenCount":1},"modelVersion":"gemini-x-001"}"""
        val r = ExplainJsonCodec.parseGenerateResponse(body, "req")
        assertEquals("Hello world", r.text)
        assertEquals("gemini-x-001", r.model)
        assertEquals(TokenUsage(3, 9, 1), r.usage)
        val blocked = assertThrows(AiException::class.java) { ExplainJsonCodec.parseGenerateResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""", "m") }
        assertEquals(AiErrorCode.SAFETY, blocked.code)
        val safety = assertThrows(AiException::class.java) {
            ExplainJsonCodec.parseGenerateResponse("""{"candidates":[{"finishReason":"PROHIBITED_CONTENT"}]}""", "m")
        }
        assertEquals(AiErrorCode.SAFETY, safety.code)
        assertEquals(AiErrorCode.INVALID_RESPONSE, assertThrows(AiException::class.java) { ExplainJsonCodec.parseGenerateResponse("""{"candidates":[]}""", "m") }.code)
        assertEquals(AiErrorCode.INVALID_RESPONSE, assertThrows(AiException::class.java) { ExplainJsonCodec.parseGenerateResponse("<html>", "m") }.code)
        assertEquals("req", ExplainJsonCodec.parseGenerateResponse("""{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""", "req").model)
    }

    @Test
    fun httpErrors() {
        assertEquals(AiErrorCode.AUTH, ExplainJsonCodec.mapHttpError(401, ""))
        assertEquals(AiErrorCode.AUTH, ExplainJsonCodec.mapHttpError(403, ""))
        assertEquals(AiErrorCode.QUOTA, ExplainJsonCodec.mapHttpError(429, ""))
        assertEquals(AiErrorCode.AUTH, ExplainJsonCodec.mapHttpError(400, "API key not valid. Please pass a valid API key."))
        assertEquals(AiErrorCode.INVALID_ARGUMENT, ExplainJsonCodec.mapHttpError(400, "bad"))
        assertEquals(AiErrorCode.NOT_FOUND, ExplainJsonCodec.mapHttpError(404, ""))
        assertEquals(AiErrorCode.TIMEOUT, ExplainJsonCodec.mapHttpError(504, ""))
        assertEquals(AiErrorCode.NETWORK, ExplainJsonCodec.mapHttpError(502, ""))
        assertEquals("quota", ExplainJsonCodec.extractErrorMessage("""[{"error":{"message":"quota"}}]""", 429))
        assertEquals("plain", ExplainJsonCodec.extractErrorMessage("plain", 500))
        assertEquals("HTTP 401", ExplainJsonCodec.extractErrorMessage("", 401))
    }

    @Suppress("unused")
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}
