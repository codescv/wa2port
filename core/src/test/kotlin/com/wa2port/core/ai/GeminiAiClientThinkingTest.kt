package com.wa2port.core.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Thinking budget policy (plan §3.1 / M3) over the real [HttpUrlConnectionTransport] against a
 * local mock server: breakdown defaults to `thinkingBudget = 0`, follow-ups keep dynamic thinking,
 * and an HTTP 400 that rejects `thinkingConfig` triggers one retry without it plus per-model memo.
 */
class GeminiAiClientThinkingTest {
    private lateinit var server: HttpServer

    /** (request path, request body) in arrival order. */
    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val replies = ArrayDeque<Pair<Int, String>>()

    private val base get() = "http://127.0.0.1:${server.address.port}/"

    private val explainJson =
        """{"translation":"是雪呢","ruby":[],"chunks":[{"surface":"雪だね","reading":"ゆきだね","textbookForm":"雪ですね","role":"r","meaning":"m","vocab":[]}],"grammar":[]}"""

    private val req = AiSampleExplanation.request()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += ex.requestURI.path to body
            val (status, text) = synchronized(replies) { replies.removeFirstOrNull() } ?: (200 to FakeTransport.geminiBody("OK"))
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun settings(model: String = AiProviderSettings.DEFAULT_MODEL, budget: Int = -1) =
        AiProviderSettings(geminiApiKey = "K", customBaseUrl = base, model = model, thinkingBudget = budget)

    private fun reply(vararg r: Pair<Int, String>) = synchronized(replies) { replies.addAll(r) }

    private fun generationConfig(body: String): JsonObject =
        AiJson.parseToJsonElement(body).jsonObject["generationConfig"]?.jsonObject ?: JsonObject(emptyMap())

    /** `thinkingConfig.thinkingBudget` of a request body, or null when `thinkingConfig` is absent. */
    private fun sentBudget(body: String): Int? =
        generationConfig(body)["thinkingConfig"]?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.content?.toInt()

    private fun error400(message: String) =
        400 to """{"error":{"code":400,"message":"$message","status":"INVALID_ARGUMENT"}}"""

    @Test
    fun explainDefaultsToZeroWhileFollowUpKeepsDynamicThinking() {
        reply(200 to FakeTransport.geminiBody(explainJson), 200 to FakeTransport.geminiBody("回答"), 200 to FakeTransport.geminiBody("OK"))
        val c = GeminiAiClient({ settings(budget = -1) })
        val expl = c.explainSentence(req, null)
        c.askFollowUp(expl, emptyList(), "为什么？", AiPersonaSettings.KOTORI, null)
        assertTrue(c.testConnection().ok)
        assertEquals(3, seen.size)
        assertEquals(0, sentBudget(seen[0].second), "explainSentence: -1 → thinkingBudget 0")
        assertTrue(seen[0].second.contains("\"thinkingConfig\":{\"thinkingBudget\":0}"))
        // −1 = model default (dynamic thinking): no thinkingConfig at all.
        assertNull(sentBudget(seen[1].second), "askFollowUp keeps dynamic thinking")
        assertFalse(generationConfig(seen[1].second).containsKey("thinkingConfig"))
        assertEquals(0, sentBudget(seen[2].second), "testConnection uses the explain budget")
    }

    @Test
    fun explicitBudgetIsKeptForBothCalls() {
        reply(200 to FakeTransport.geminiBody(explainJson), 200 to FakeTransport.geminiBody("回答"))
        val c = GeminiAiClient({ settings(budget = 512) })
        val expl = c.explainSentence(req, null)
        c.askFollowUp(expl, emptyList(), "为什么？", AiPersonaSettings.KOTORI, null)
        assertEquals(512, sentBudget(seen[0].second))
        assertEquals(512, sentBudget(seen[1].second))
        assertEquals(0, settings(budget = -1).explainThinkingBudget)
        assertEquals(0, settings(budget = 0).explainThinkingBudget)
        assertEquals(512, settings(budget = 512).explainThinkingBudget)
    }

    @Test
    fun thinking400RetriesOnceWithoutThinkingConfigAndMemoizesModel() {
        val pro = "gemini-2.5-pro"
        reply(
            error400("Unable to submit request because thinkingBudget is not supported by this model."),
            200 to FakeTransport.geminiBody(explainJson),
        )
        val c = GeminiAiClient({ settings(model = pro) })
        assertFalse(c.isThinkingUnsupported(pro))
        val expl = c.explainSentence(req, null)
        assertEquals("雪だね", expl.chunks.single().surface)
        assertEquals(2, seen.size, "one rejected request + one retry")
        assertEquals(0, sentBudget(seen[0].second))
        assertFalse(generationConfig(seen[1].second).containsKey("thinkingConfig"), "retry omits thinkingConfig")
        assertTrue(generationConfig(seen[1].second).containsKey("responseSchema"), "retry keeps the rest of the config")
        assertTrue(c.isThinkingUnsupported(pro))

        // Second call for the same model: thinkingConfig omitted on the first (and only) try.
        seen.clear()
        reply(200 to FakeTransport.geminiBody(explainJson))
        c.explainSentence(req.copy(force = true), null)
        assertEquals(1, seen.size)
        assertFalse(generationConfig(seen[0].second).containsKey("thinkingConfig"))
        assertTrue(seen[0].first.contains(pro))
    }

    @Test
    fun memoIsPerModel() {
        reply(error400("Thinking level is not supported; use thinkingLevel."), 200 to FakeTransport.geminiBody(explainJson))
        var model = "gemini-2.5-pro"
        val c = GeminiAiClient({ settings(model = model) })
        c.explainSentence(req, null)
        assertTrue(c.isThinkingUnsupported("gemini-2.5-pro"))
        model = "gemini-3.8-flash"
        seen.clear()
        reply(200 to FakeTransport.geminiBody(explainJson))
        c.explainSentence(req, null)
        assertEquals(1, seen.size)
        assertEquals(0, sentBudget(seen[0].second), "other models still send thinkingBudget 0")
        assertFalse(c.isThinkingUnsupported("gemini-3.8-flash"))
    }

    @Test
    fun opaqueProxy400RetriesOnceWithoutThinkingConfigAndMemoizesOnSuccess() {
        val proxyModel = "gemini-proxy-flash"
        reply(
            error400("Request contains an invalid argument."),
            200 to FakeTransport.geminiBody(explainJson),
        )
        val c = GeminiAiClient({ settings(model = proxyModel) })
        val expl = c.explainSentence(req, null)
        assertEquals("雪だね", expl.chunks.single().surface)
        assertEquals(2, seen.size, "opaque 400 with thinkingConfig retries once without thinkingConfig")
        assertEquals(0, sentBudget(seen[0].second))
        assertFalse(generationConfig(seen[1].second).containsKey("thinkingConfig"))
        assertTrue(generationConfig(seen[1].second).containsKey("responseSchema"))
        assertTrue(c.isThinkingUnsupported(proxyModel), "successful no-thinking retry memoizes model")

        // Next call omits thinkingConfig up front.
        seen.clear()
        reply(200 to FakeTransport.geminiBody(explainJson))
        c.explainSentence(req.copy(force = true), null)
        assertEquals(1, seen.size)
        assertFalse(generationConfig(seen[0].second).containsKey("thinkingConfig"))
    }

    @Test
    fun persistentNonThinking400DoesNotPoisonThinkingMemo() {
        val badErr = error400("Invalid JSON payload received. Unknown name \\\"foo\\\".")
        reply(badErr, badErr, badErr, badErr)
        val c = GeminiAiClient({ settings() })
        val e = assertThrows(AiException::class.java) { c.explainSentence(req, null) }
        assertEquals(AiErrorCode.INVALID_ARGUMENT, e.code)
        assertEquals(400, e.httpStatus)
        assertFalse(c.isThinkingUnsupported(AiProviderSettings.DEFAULT_MODEL), "genuine 400 must not poison thinkingUnsupportedModels")
    }

    @Test
    fun responseSchema400FallsBackToJsonMimeWithSchemaHint() {
        // Simulate a proxy that rejects generationConfig.responseSchema with HTTP 400 (with or without thinkingConfig),
        // then succeeds when responseSchema is omitted and EXPLAIN_JSON_SCHEMA_HINT is in systemInstruction.
        val schemaErr = error400("Invalid JSON payload received at response_schema.")
        reply(schemaErr, schemaErr, 200 to FakeTransport.geminiBody(explainJson))
        val c = GeminiAiClient({ settings() })
        val expl = c.explainSentence(req, null)
        assertEquals("雪だね", expl.chunks.single().surface)
        assertEquals(3, seen.size)
        val fallbackBody = AiJson.parseToJsonElement(seen[2].second).jsonObject
        val fallbackGen = fallbackBody["generationConfig"]!!.jsonObject
        assertEquals("application/json", fallbackGen["responseMimeType"]!!.jsonPrimitive.content)
        assertFalse(fallbackGen.containsKey("responseSchema"), "fallback drops responseSchema")
        val sysText = fallbackBody["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(sysText.contains("officialTranslationNote") && sysText.contains("spokenEvolution"), "fallback appends JSON schema hint")
        assertFalse(c.isThinkingUnsupported(AiProviderSettings.DEFAULT_MODEL), "schema rejection must not mark thinking unsupported when budget=0 succeeded")
    }

    @Test
    fun thinking400WithoutThinkingConfigIsNotRetried() {
        // Follow-up with the default −1 sends no thinkingConfig: a "thinking" 400 is not ours to fix.
        reply(error400("thinking is unavailable in this region"))
        val c = GeminiAiClient({ settings(budget = -1) })
        val expl = AiSampleExplanation.build(0L)
        assertThrows(AiException::class.java) { c.askFollowUp(expl, emptyList(), "为什么？", AiPersonaSettings.KOTORI, null) }
        assertEquals(1, seen.size)
        assertFalse(c.isThinkingUnsupported(AiProviderSettings.DEFAULT_MODEL))
    }

    @Test
    fun non400MentioningThinkingDoesNotRetry() {
        reply(500 to """{"error":{"code":500,"message":"internal error while thinking","status":"INTERNAL"}}""")
        val c = GeminiAiClient({ settings() })
        val e = assertThrows(AiException::class.java) { c.explainSentence(req, null) }
        assertEquals(500, e.httpStatus)
        assertEquals(1, seen.size)
        assertFalse(c.isThinkingUnsupported(AiProviderSettings.DEFAULT_MODEL))
    }
}
