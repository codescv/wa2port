package com.wa2port.core.ai

import com.wa2port.core.ai.AiProviderSettings.Companion.PROVIDER_VERTEX_AI
import com.wa2port.core.ai.AiProviderSettings.Companion.VERTEX_AUTH_ACCESS_TOKEN
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException

class GeminiAiClientTest {
    @TempDir
    lateinit var tmp: File

    private val sampleJson: String by lazy {
        // Model output equivalent to the sample breakdown (re-serialised from its raw form).
        val e = AiSampleExplanation.RAW
        kotlinx.serialization.json.buildJsonObject {
            put("translation", kotlinx.serialization.json.JsonPrimitive(e.translation))
            put("literalTranslation", kotlinx.serialization.json.JsonPrimitive(e.literalTranslation))
            put("officialTranslationNote", kotlinx.serialization.json.JsonPrimitive(e.officialTranslationNote))
            put("register", kotlinx.serialization.json.JsonPrimitive("casual"))
            put(
                "ruby",
                kotlinx.serialization.json.JsonArray(
                    e.ruby.map { r ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("base", kotlinx.serialization.json.JsonPrimitive(r.base))
                            r.ruby?.let { put("ruby", kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    },
                ),
            )
            put(
                "chunks",
                kotlinx.serialization.json.JsonArray(
                    e.chunks.map { c ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("surface", kotlinx.serialization.json.JsonPrimitive(c.surface))
                            put("reading", kotlinx.serialization.json.JsonPrimitive(c.reading))
                            put("textbookForm", kotlinx.serialization.json.JsonPrimitive(c.textbookForm))
                            put("role", kotlinx.serialization.json.JsonPrimitive(c.role))
                            put("meaning", kotlinx.serialization.json.JsonPrimitive(c.meaning))
                            put("vocab", kotlinx.serialization.json.JsonArray(emptyList()))
                        }
                    },
                ),
            )
            put("grammar", kotlinx.serialization.json.JsonArray(emptyList()))
        }.toString()
    }

    private val req = AiSampleExplanation.request(focusCharIndex = 5)

    private fun client(s: AiProviderSettings, t: FakeTransport, clock: Long = 100L) = GeminiAiClient({ s }, t, { clock })

    @Test
    fun customBaseUrlReachesTransportForAllThreeCalls() {
        val s = AiProviderSettings(geminiApiKey = "K", customBaseUrl = "https://proxy.example.com/v1beta/")
        val t = FakeTransport(FakeTransport.geminiBody("OK"), FakeTransport.geminiBody(sampleJson), FakeTransport.geminiBody("因为…"))
        val c = client(s, t)
        assertTrue(c.testConnection().ok)
        val expl = c.explainSentence(req, null)
        val answer = c.askFollowUp(expl, emptyList(), "为什么？", AiPersonaSettings.SETSUNA, null)
        assertEquals("因为…", answer.text)
        assertEquals(3, t.calls.size)
        t.calls.forEach {
            assertEquals("https://proxy.example.com/v1beta/models/${AiProviderSettings.DEFAULT_MODEL}:generateContent", it.url)
            assertEquals("K", it.headers["x-goog-api-key"])
            assertEquals("application/json", it.headers["Content-Type"])
        }
    }

    @Test
    fun vertexAccessTokenHeaders() {
        val s = AiProviderSettings(providerType = PROVIDER_VERTEX_AI, vertexAuthMode = VERTEX_AUTH_ACCESS_TOKEN, vertexAccessToken = "fake-access-token", vertexLocation = "us-central1")
        val t = FakeTransport(FakeTransport.geminiBody("OK"))
        assertTrue(client(s, t).testConnection().ok)
        assertEquals("Bearer fake-access-token", t.calls[0].headers["Authorization"])
        assertTrue(t.calls[0].url.startsWith("https://us-central1-aiplatform.googleapis.com/v1/projects/gps-babel-tower/locations/us-central1/"))
    }

    @Test
    fun vertexServiceAccountExchangesToken() {
        val s = AiProviderSettings(providerType = PROVIDER_VERTEX_AI, vertexServiceAccountJson = ServiceAccountJwtTest.saJson("https://tok.example.com/t"))
        val t = FakeTransport("""{"access_token":"SA-TOKEN","expires_in":3600}""", FakeTransport.geminiBody("OK"), FakeTransport.geminiBody("OK"))
        val c = client(s, t)
        assertTrue(c.testConnection().ok)
        assertTrue(c.testConnection().ok)
        assertEquals(3, t.calls.size) // token fetched once, reused
        assertEquals("https://tok.example.com/t", t.calls[0].url)
        assertEquals("Bearer SA-TOKEN", t.calls[2].headers["Authorization"])
    }

    @Test
    fun explainParsesAndCaches() {
        val cache = ExplainCache(tmp)
        val s = AiProviderSettings(geminiApiKey = "K")
        val t = FakeTransport(FakeTransport.geminiBody(sampleJson, modelVersion = "gemini-3.8-flash-001"))
        val c = client(s, t)
        val e = c.explainSentence(req, cache)
        assertFalse(e.fromCache)
        assertEquals("gemini-3.8-flash-001", e.model)
        assertEquals(AiSampleExplanation.SENTENCE, e.sentence)
        assertEquals(ExplainCache.key(req, s.model), e.id)
        assertEquals(c.cacheKey(req), e.id)
        assertEquals(AiSampleExplanation.OFFICIAL_CN, e.officialCnTranslation)
        assertTrue(e.chunks.all { it.alignment == SpanAlignment.EXACT })
        // Request body: schema + prompt (focus char, speaker, CK-GAL).
        val body = AiJson.parseToJsonElement(t.calls[0].body).jsonObject
        val gen = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", gen["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(gen.containsKey("responseSchema"))
        val userText = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(userText.contains("「雪」") && userText.contains("Speaker: 雪菜") && userText.contains("CK-GAL"))

        val again = c.explainSentence(req, cache)
        assertTrue(again.fromCache)
        assertEquals(1, t.calls.size) // cache hit: no transport call
        assertEquals(e.copy(fromCache = true), again)
    }

    @Test
    fun invalidJsonRetriesOnceWithNudge() {
        val t = FakeTransport(FakeTransport.geminiBody("Sure! here is {broken"), FakeTransport.geminiBody("```json\n$sampleJson\n```"))
        val e = client(AiProviderSettings(geminiApiKey = "K"), t).explainSentence(req, null)
        assertEquals(AiSampleExplanation.SENTENCE, e.sentence)
        assertEquals(2, t.calls.size)
        val contents = AiJson.parseToJsonElement(t.calls[1].body).jsonObject["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(INVALID_JSON_NUDGE, contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun maxTokensSkipsRetry() {
        val t = FakeTransport(FakeTransport.geminiBody("{\"translation\":\"tru", finishReason = "MAX_TOKENS"))
        val e = assertThrows(AiException::class.java) { client(AiProviderSettings(geminiApiKey = "K"), t).explainSentence(req, null) }
        assertEquals(AiErrorCode.INVALID_RESPONSE, e.code)
        assertTrue(e.message!!.contains("截断"))
        assertEquals(1, t.calls.size)
    }

    @Test
    fun httpErrorsMapToCodes() {
        val s = AiProviderSettings(geminiApiKey = "K")
        val auth = assertThrows(AiException::class.java) {
            client(s, FakeTransport(HttpTransport.Response(401, """{"error":{"code":401,"message":"API key expired"}}"""))).explainSentence(req, null)
        }
        assertEquals(AiErrorCode.AUTH, auth.code)
        assertEquals("API key expired", auth.message)
        assertEquals(401, auth.httpStatus)
        val quota = assertThrows(AiException::class.java) { client(s, FakeTransport(HttpTransport.Response(429, "{}"))).explainSentence(req, null) }
        assertEquals(AiErrorCode.QUOTA, quota.code)
    }

    @Test
    fun transportFailuresNeverEscapeRaw() {
        val s = AiProviderSettings(geminiApiKey = "K", customBaseUrl = "https://proxy.example.com")
        fun codeOf(failure: Exception) = assertThrows(AiException::class.java) { client(s, FakeTransport(failure)).generate(GeminiRequest("m", null, emptyList())) }
        assertEquals(AiErrorCode.TIMEOUT, codeOf(SocketTimeoutException("t")).code)
        val net = codeOf(IOException("reset"))
        assertEquals(AiErrorCode.NETWORK, net.code)
        assertTrue(net.message!!.contains("proxy.example.com"))
        assertFalse(net.message!!.contains("key="))
        assertTrue(codeOf(UnknownServiceException("CLEARTEXT communication not permitted")).message!!.contains("https"))
        assertEquals(AiErrorCode.INVALID_ARGUMENT, codeOf(IllegalArgumentException("bad header")).code)
        assertEquals(AiErrorCode.NETWORK, codeOf(IllegalStateException("weird")).code)
    }

    @Test
    fun cancellationReportsCancelled() {
        val token = CancelToken()
        val t = FakeTransport(FakeTransport.geminiBody("OK"))
        t.onPost = { _, c -> c?.cancel() }
        val e = assertThrows(AiException::class.java) { client(AiProviderSettings(geminiApiKey = "K"), t).explainSentence(req, null, token) }
        assertEquals(AiErrorCode.CANCELLED, e.code)
        // Already cancelled → no I/O at all.
        val t2 = FakeTransport(FakeTransport.geminiBody("OK"))
        assertEquals(AiErrorCode.CANCELLED, assertThrows(AiException::class.java) { client(AiProviderSettings(geminiApiKey = "K"), t2).generate(GeminiRequest("m", null, emptyList()), token) }.code)
        assertEquals(0, t2.calls.size)
        // Cancel-before-attach aborts immediately.
        var aborted = false
        assertThrows(IOException::class.java) { token.attach { aborted = true } }
        assertTrue(aborted)
    }

    @Test
    fun notConfiguredBeforeAnyIo() {
        val t = FakeTransport()
        val e = assertThrows(AiException::class.java) { client(AiProviderSettings(), t).explainSentence(req, null) }
        assertEquals(AiErrorCode.NOT_CONFIGURED, e.code)
        assertEquals(0, t.calls.size)
        val r = client(AiProviderSettings(), t).testConnection()
        assertFalse(r.ok)
        assertTrue(r.message.startsWith("NOT_CONFIGURED"))
    }

    @Test
    fun testConnectionNeverThrows() {
        val r = client(AiProviderSettings(geminiApiKey = "K"), FakeTransport(HttpTransport.Response(403, """{"error":{"message":"denied"}}"""))).testConnection()
        assertFalse(r.ok)
        assertEquals("AUTH: denied", r.message)
        val ok = client(AiProviderSettings(geminiApiKey = "K"), FakeTransport(FakeTransport.geminiBody(" OK \n", modelVersion = "gemini-x"))).testConnection()
        assertTrue(ok.ok)
        assertEquals("gemini-x", ok.model)
        assertEquals("OK", ok.message)
        val boom = client(AiProviderSettings(geminiApiKey = "K"), FakeTransport(RuntimeException("boom"))).testConnection()
        assertFalse(boom.ok)
    }

    @Test
    fun followUpPersistsOnlyOnSuccess() {
        val cache = ExplainCache(tmp)
        val expl = AiSampleExplanation.build()
        val s = AiProviderSettings(geminiApiKey = "K", temperature = 0.1f)
        val t = FakeTransport(FakeTransport.geminiBody("答1"), HttpTransport.Response(500, "oops"), FakeTransport.geminiBody("   "))
        val c = client(s, t)
        val a1 = c.askFollowUp(expl, emptyList(), "  问1 ", AiPersonaSettings.KOTORI, cache)
        assertEquals("model", a1.role)
        assertThrows(AiException::class.java) { c.askFollowUp(expl, cache.thread(expl.id), "问2", AiPersonaSettings.KOTORI, cache) }
        assertEquals(AiErrorCode.INVALID_RESPONSE, assertThrows(AiException::class.java) { c.askFollowUp(expl, cache.thread(expl.id), "问3", AiPersonaSettings.KOTORI, cache) }.code)
        assertEquals(listOf("user" to "问1", "model" to "答1"), cache.thread(expl.id).map { it.role to it.text })
        // Temperature floor 0.4 for follow-ups; history sent before the question.
        val body = AiJson.parseToJsonElement(t.calls[1].body).jsonObject
        assertEquals(0.4f, body["generationConfig"]!!.jsonObject["temperature"]!!.jsonPrimitive.content.toFloat())
        assertEquals(3, body["contents"]!!.jsonArray.size)
        assertThrows(AiException::class.java) { c.askFollowUp(expl, emptyList(), "   ", AiPersonaSettings.KOTORI, cache) }
    }

    @Test
    fun forceBypassesCacheAndClearsThread() {
        val cache = ExplainCache(tmp)
        val s = AiProviderSettings(geminiApiKey = "K")
        val t = FakeTransport(FakeTransport.geminiBody(sampleJson), FakeTransport.geminiBody(sampleJson))
        val c = client(s, t)
        val e = c.explainSentence(req, cache)
        cache.appendThread(e.id, FollowUpMessage("1", "user", "q", 1))
        val forced = c.explainSentence(req.copy(force = true), cache)
        assertFalse(forced.fromCache)
        assertEquals(2, t.calls.size)
        assertEquals(emptyList<FollowUpMessage>(), cache.thread(e.id))
        // cacheEnabled = false → never cached.
        val t2 = FakeTransport(FakeTransport.geminiBody(sampleJson), FakeTransport.geminiBody(sampleJson))
        val noCache = GeminiAiClient({ s.copy(cacheEnabled = false, model = "other") }, t2)
        noCache.explainSentence(req, cache)
        noCache.explainSentence(req, cache)
        assertEquals(2, t2.calls.size)
    }
}
