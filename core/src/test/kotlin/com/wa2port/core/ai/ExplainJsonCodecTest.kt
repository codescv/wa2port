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
        // Unknown keys / missing fields tolerated; legacy 2.0/2.5/blank models migrated to DEFAULT_MODEL.
        assertEquals(AiProviderSettings(model = "x"), ExplainJsonCodec.decodeSettings("""{"model":"x","future":1}"""))
        assertEquals(AiProviderSettings.DEFAULT_MODEL, ExplainJsonCodec.decodeSettings("""{"model":"gemini-2.5-flash"}""").model)
        assertEquals(AiProviderSettings.DEFAULT_MODEL, ExplainJsonCodec.decodeSettings("""{"model":"gemini-2.5-pro"}""").model)
        assertEquals(AiProviderSettings.DEFAULT_MODEL, ExplainJsonCodec.decodeSettings("""{"model":"gemini-2.0-flash"}""").model)
        assertEquals(AiProviderSettings.DEFAULT_MODEL, ExplainJsonCodec.decodeSettings("""{"model":"  \u200B "}""").model)
        assertEquals("gemini-3.8-flash", ExplainJsonCodec.decodeSettings("""{"model":"models/gemini-3.8-flash\uFEFF"}""").model)
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
        val schemaJson = s.toString()
        assertFalse(schemaJson.contains("\"nullable\""), "responseSchema must never emit nullable:true")
        assertFalse(schemaJson.contains("\"lemmaRuby\""), "vocab.items must not include lemmaRuby")
        assertFalse(schemaJson.contains("\"pitchAccent\""), "vocab.items must not include pitchAccent")

        val order = s["propertyOrdering"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, order.count { it == "officialTranslationNote" })
        assertFalse(s["required"]!!.jsonArray.any { it.jsonPrimitive.content == "officialTranslationNote" })
        val props = s["properties"]!!.jsonObject
        val vocabItem = props["chunks"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["vocab"]!!.jsonObject["items"]!!.jsonObject
        val vocabProps = vocabItem["properties"]!!.jsonObject
        assertEquals(17, vocabProps["partOfSpeech"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertEquals(5, vocabProps["jlpt"]!!.jsonObject["enum"]!!.jsonArray.size)
        assertEquals(
            listOf("surface", "lemma", "lemmaReading", "partOfSpeech", "meaning"),
            vocabItem["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        fun assertValidObjectSchema(node: JsonObject, path: String) {
            if (node["type"]?.jsonPrimitive?.content == "OBJECT") {
                val p = node["properties"]!!.jsonObject
                val reqKeys = node["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val ordKeys = node["propertyOrdering"]!!.jsonArray.map { it.jsonPrimitive.content }
                assertTrue(p.keys.containsAll(reqKeys), "$path required must be a subset of properties")
                assertEquals(p.keys, ordKeys.toSet(), "$path propertyOrdering must match properties keys")
                assertEquals(p.keys.size, ordKeys.size, "$path propertyOrdering must have no duplicates")
                p.forEach { (k, v) -> (v as? JsonObject)?.let { assertValidObjectSchema(it, "$path.$k") } }
            } else if (node["type"]?.jsonPrimitive?.content == "ARRAY") {
                (node["items"] as? JsonObject)?.let { assertValidObjectSchema(it, "$path[]") }
            }
        }
        assertValidObjectSchema(s, "root")
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

    @Test
    fun backfillsMissingKanjiRubyFromChunksAndVocabOnFlashLitePayload() {
        val drumSentence = "「ドラムどうすんだよ！他にいないんだぞ。放り出す気かよ？」"
        // Exact gemini-3.5-flash-lite failure mode (`ex_40f2c30308...`): raw.ruby has furigana on 他,
        // omits readings on 放り出す and 気, while chunks/vocab carry ほうりだす and き.
        val rawJson = """
            {
              "translation": "架子鼓怎么办啊！又没有其他人了。你打算就这样不管了吗？",
              "register": "casual",
              "tone": "急躁、质问",
              "ruby": [
                {"base": "「ドラムどうすんだよ！"},
                {"base": "他", "ruby": "ほか"},
                {"base": "にいないんだぞ。"},
                {"base": "放"},
                {"base": "り"},
                {"base": "出"},
                {"base": "す"},
                {"base": "気"},
                {"base": "かよ？」"}
              ],
              "chunks": [
                {"surface": "「ドラム", "reading": "どらむ", "textbookForm": "ドラム", "role": "宾语", "meaning": "架子鼓",
                 "vocab": [{"surface": "ドラム", "lemma": "ドラム", "lemmaReading": "どらむ", "partOfSpeech": "noun", "meaning": "架子鼓，鼓"}]},
                {"surface": "どうすんだよ！", "reading": "どうすんだよ", "textbookForm": "どうするのだよ",
                 "spokenEvolution": {"kind": "缩约", "derivation": "どうするの → どうすんの → どうすんだ", "formality": 0.2},
                 "role": "谓语核心", "meaning": "该怎么办啊",
                 "vocab": [{"surface": "どう", "lemma": "どう", "lemmaReading": "どう", "partOfSpeech": "adverb", "meaning": "如何，怎样", "jlpt": "N5"},
                           {"surface": "する", "lemma": "する", "lemmaReading": "する", "partOfSpeech": "verb", "meaning": "做，办", "jlpt": "N5"},
                           {"surface": "んだよ", "lemma": "のだよ", "lemmaReading": "のだよ", "partOfSpeech": "auxiliary", "meaning": "用于加强语气的断定和诘问"}]},
                {"surface": "他に", "reading": "ほかに", "textbookForm": "他に", "role": "状语", "meaning": "除此之外，别处",
                 "vocab": [{"surface": "他", "lemma": "他", "lemmaReading": "ほか", "partOfSpeech": "noun", "meaning": "其他，另外", "jlpt": "N3"},
                           {"surface": "に", "lemma": "に", "lemmaReading": "に", "partOfSpeech": "particle", "meaning": "在……中", "jlpt": "N5"}]},
                {"surface": "いないんだぞ。", "reading": "いないんだぞ", "textbookForm": "いないのだぞ",
                 "spokenEvolution": {"kind": "口语习惯", "derivation": "いないのだ → いないんだ", "formality": 0.2},
                 "role": "谓语", "meaning": "根本没有啊",
                 "vocab": [{"surface": "いる", "lemma": "いる", "lemmaReading": "いる", "partOfSpeech": "verb", "meaning": "在", "jlpt": "N5"},
                           {"surface": "ない", "lemma": "ない", "lemmaReading": "ない", "partOfSpeech": "auxiliary", "meaning": "表否定", "jlpt": "N5"},
                           {"surface": "んだぞ", "lemma": "のだぞ", "lemmaReading": "のだぞ", "partOfSpeech": "auxiliary", "meaning": "加强语气"}]},
                {"surface": "放り出す", "reading": "ほうりだす", "textbookForm": "放り出す", "role": "谓语动词", "meaning": "抛弃，撒手不管",
                 "vocab": [{"surface": "放り出す", "lemma": "放り出す", "lemmaReading": "ほうりだす", "partOfSpeech": "verb", "meaning": "抛弃，放弃", "jlpt": "N1"}]},
                {"surface": "気かよ？", "reading": "きかよ", "textbookForm": "気かよ", "role": "句尾终助词结构", "meaning": "打算……吗？",
                 "vocab": [{"surface": "気", "lemma": "気", "lemmaReading": "き", "partOfSpeech": "noun", "meaning": "心思，打算", "jlpt": "N4"},
                           {"surface": "か", "lemma": "か", "lemmaReading": "か", "partOfSpeech": "particle", "meaning": "表疑问", "jlpt": "N5"},
                           {"surface": "よ", "lemma": "よ", "lemmaReading": "よ", "partOfSpeech": "particle", "meaning": "表提醒或质问"}]}
              ],
              "grammar": []
            }
        """.trimIndent()
        val raw = ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson(rawJson))
        val e = ExplainJsonCodec.toSentenceExplanation(
            raw,
            ExplainRequest(drumSentence, speakerJp = "武也", officialCnTranslation = "「那鼓手怎么办啊！ 没有其他人了啊。你准备抛下不管了吗？」"),
            ExplanationMeta(id = "ex_40f2c30308", model = "gemini-3.5-flash-lite", now = 1L),
        )
        assertEquals(drumSentence, e.ruby.joinToString("") { it.base })
        assertEquals("ほか", e.ruby.first { it.base == "他" }.ruby)
        assertEquals("ほう", e.ruby.first { it.base == "放" }.ruby)
        assertNull(e.ruby.first { it.base == "り" }.ruby)
        assertEquals("だ", e.ruby.first { it.base == "出" }.ruby)
        assertNull(e.ruby.first { it.base == "す" }.ruby)
        assertEquals("き", e.ruby.first { it.base == "気" }.ruby)

        val c4 = e.chunks.first { it.surface == "放り出す" }
        assertEquals(listOf("放" to "ほう", "り" to null, "出" to "だ", "す" to null), c4.ruby.map { it.base to it.ruby })
        val c5 = e.chunks.first { it.surface == "気かよ？" }
        assertEquals(listOf("気" to "き", "かよ？" to null), c5.ruby.map { it.base to it.ruby })

        val v9 = e.vocab.first { it.lemma == "放り出す" }
        assertEquals(listOf("放" to "ほう", "り" to null, "出" to "だ", "す" to null), v9.lemmaRuby.map { it.base to it.ruby })
        val v10 = e.vocab.first { it.lemma == "気" }
        assertEquals(listOf("気" to "き"), v10.lemmaRuby.map { it.base to it.ruby })

        // Also verify vocab-only backfill when chunk.reading is blank, plus jukujikun normalization.
        val jukujikunRaw = ExplainJsonCodec.parseRawExplanation(
            ExplainJsonCodec.parseModelJson(
                """
                {
                  "translation": "明天也要扔下不管吗",
                  "ruby": [{"base": "明", "ruby": "あ"}, {"base": "日", "ruby": "ひ"}, {"base": "も"}],
                  "chunks": [
                    {"surface": "明日も", "reading": "あしたも", "textbookForm": "明日も", "role": "状语", "meaning": "明天也", "vocab": []},
                    {"surface": "放り出す", "reading": "", "textbookForm": "放り出す", "role": "谓语", "meaning": "不管",
                     "vocab": [{"surface": "放り出す", "lemma": "放り出す", "lemmaReading": "ほうりだす", "partOfSpeech": "verb", "meaning": "抛下"}]}
                  ],
                  "grammar": []
                }
                """.trimIndent(),
            ),
        )
        val e2 = ExplainJsonCodec.toSentenceExplanation(
            jukujikunRaw,
            ExplainRequest("明日も放り出す"),
            ExplanationMeta(id = "ex_juku", model = "gemini-3.5-flash-lite", now = 2L),
        )
        assertEquals("明日も放り出す", e2.ruby.joinToString("") { it.base })
        assertEquals("あした", e2.ruby.first { it.base == "明日" }.ruby)
        assertEquals("ほう", e2.ruby.first { it.base == "放" }.ruby)
        assertEquals("だ", e2.ruby.first { it.base == "出" }.ruby)
    }

    @Test
    fun decodeExplanationRepairsLegacyCachedMissingRuby() {
        val legacyCachedJson = """
            {"id":"ex_40f2c3030838d27630c880cba38d8240a1986cddff3b62556bab621c7738f01e","sentence":"「ドラムどうすんだよ！他にいないんだぞ。放り出す気かよ？」","ruby":[{"base":"「ドラムどうすんだよ！","span":{"start":0,"end":11}},{"base":"他","ruby":"ほか","span":{"start":11,"end":12}},{"base":"にいないんだぞ。","span":{"start":12,"end":20}},{"base":"放","span":{"start":20,"end":21}},{"base":"り","span":{"start":21,"end":22}},{"base":"出","span":{"start":22,"end":23}},{"base":"す","span":{"start":23,"end":24}},{"base":"気","span":{"start":24,"end":25}},{"base":"かよ？」","span":{"start":25,"end":29}}],"translation":"架子鼓怎么办啊！又没有其他人了。你打算就这样不管了吗？","chunks":[{"id":"c4","span":{"start":20,"end":24},"alignment":"EXACT","surface":"放り出す","reading":"ほうりだす","ruby":[{"base":"放","span":{"start":20,"end":21}},{"base":"り","span":{"start":21,"end":22}},{"base":"出","span":{"start":22,"end":23}},{"base":"す","span":{"start":23,"end":24}}],"textbookForm":"放り出す","role":"谓语动词","meaning":"抛弃","vocabIds":["v9"]},{"id":"c5","span":{"start":24,"end":28},"alignment":"EXACT","surface":"気かよ？","reading":"きかよ","ruby":[{"base":"気","span":{"start":24,"end":25}},{"base":"かよ？","span":{"start":25,"end":28}}],"textbookForm":"気かよ","role":"句尾","meaning":"打算……吗？","vocabIds":["v10"]}],"vocab":[{"id":"v9","span":{"start":20,"end":24},"alignment":"EXACT","surface":"放り出す","lemma":"放り出す","lemmaReading":"ほうりだす","lemmaRuby":[{"base":"放","span":{"start":0,"end":1}},{"base":"り","span":{"start":1,"end":2}},{"base":"出","span":{"start":2,"end":3}},{"base":"す","span":{"start":3,"end":4}}],"partOfSpeech":"verb","meaning":"抛弃"},{"id":"v10","span":{"start":24,"end":25},"alignment":"EXACT","surface":"気","lemma":"気","lemmaReading":"き","lemmaRuby":[{"base":"気","span":{"start":0,"end":1}}],"partOfSpeech":"noun","meaning":"心思"}],"grammar":[],"model":"gemini-3.5-flash-lite","promptVersion":"wa2-v1","createdAt":1793169190499}
        """.trimIndent()
        val decoded = ExplainJsonCodec.decodeExplanation(legacyCachedJson)
        assertEquals("ほか", decoded.ruby.first { it.base == "他" }.ruby)
        assertEquals("ほう", decoded.ruby.first { it.base == "放" }.ruby)
        assertEquals("だ", decoded.ruby.first { it.base == "出" }.ruby)
        assertEquals("き", decoded.ruby.first { it.base == "気" }.ruby)
        assertEquals("ほう", decoded.chunks.first { it.id == "c4" }.ruby.first { it.base == "放" }.ruby)
        assertEquals("き", decoded.chunks.first { it.id == "c5" }.ruby.first { it.base == "気" }.ruby)
        assertEquals("ほう", decoded.vocab.first { it.id == "v9" }.lemmaRuby.first { it.base == "放" }.ruby)
        assertEquals("き", decoded.vocab.first { it.id == "v10" }.lemmaRuby.first { it.base == "気" }.ruby)
    }

    @Suppress("unused")
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
}
