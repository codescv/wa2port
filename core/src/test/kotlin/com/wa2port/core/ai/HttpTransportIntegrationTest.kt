package com.wa2port.core.ai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** End-to-end over the real [HttpUrlConnectionTransport] against a local mock "proxy". */
class HttpTransportIntegrationTest {
    private lateinit var server: HttpServer
    private val seen = Collections.synchronizedList(mutableListOf<Triple<String, Map<String, String>, String>>())
    private var replies = ArrayDeque<Pair<Int, String>>()
    private var stall: CountDownLatch? = null

    private val base get() = "http://127.0.0.1:${server.address.port}/gemini/v1beta/"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            seen += Triple(ex.requestURI.toString(), headers, body)
            stall?.await(5, TimeUnit.SECONDS)
            val (status, text) = synchronized(replies) { replies.removeFirstOrNull() } ?: (200 to FakeTransport.geminiBody("OK"))
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            if (bytes.isEmpty()) ex.sendResponseHeaders(status, -1) else ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun stop() {
        stall?.countDown()
        server.stop(0)
    }

    @Test
    fun customBaseUrlEndToEnd() {
        val s = AiProviderSettings(geminiApiKey = "K", customBaseUrl = base, extraHeaders = "X-Proxy-Token: {apiKey}")
        val sample = AiSampleExplanation.RAW
        val explainJson = """{"translation":"${sample.translation}","ruby":[],"chunks":[{"surface":"雪だね","reading":"ゆきだね","textbookForm":"雪ですね","role":"r","meaning":"m","vocab":[]}],"grammar":[]}"""
        replies.addAll(listOf(200 to FakeTransport.geminiBody("OK"), 200 to FakeTransport.geminiBody(explainJson), 200 to FakeTransport.geminiBody("回答「ね」")))
        val c = GeminiAiClient({ s })
        val test = c.testConnection()
        assertTrue(test.ok, test.message)
        val expl = c.explainSentence(AiSampleExplanation.request(), null)
        assertEquals("雪だね", expl.chunks.single().surface)
        assertEquals(SpanAlignment.EXACT, expl.chunks.single().alignment)
        val answer = c.askFollowUp(expl, emptyList(), "为什么用ね？", AiPersonaSettings.KOTORI, null)
        assertEquals("回答「ね」", answer.text) // UTF-8 both ways
        assertEquals(3, seen.size)
        seen.forEach { (uri, headers, body) ->
            assertEquals("/gemini/v1beta/models/gemini-3.8-flash:generateContent", uri)
            assertEquals("K", headers["x-goog-api-key"])
            assertEquals("K", headers["x-proxy-token"])
            assertTrue(headers["content-type"]!!.startsWith("application/json"))
            assertTrue(body.contains("\"contents\""))
        }
        assertTrue(seen[1].third.contains("雪だね"))
    }

    @Test
    fun errorBodiesAndEmptyErrorStream() {
        val s = AiProviderSettings(geminiApiKey = "K", customBaseUrl = base, proxyAuthStyle = AiProviderSettings.AUTH_STYLE_QUERY)
        replies.addAll(listOf(429 to """{"error":{"message":"Resource exhausted"}}""", 401 to ""))
        val c = GeminiAiClient({ s })
        val quota = assertThrows(AiException::class.java) { c.generate(GeminiRequest("m", null, listOf(GeminiContent("user", "x")))) }
        assertEquals(AiErrorCode.QUOTA, quota.code)
        assertEquals("Resource exhausted", quota.message)
        val auth = assertThrows(AiException::class.java) { c.generate(GeminiRequest("m", null, listOf(GeminiContent("user", "x")))) }
        assertEquals(AiErrorCode.AUTH, auth.code)
        assertEquals("HTTP 401", auth.message)
        assertTrue(seen[0].first.endsWith(":generateContent?key=K"))
        assertFalse(seen[0].second.containsKey("x-goog-api-key"))
    }

    @Test
    fun cancelMidRequestReportsCancelled() {
        stall = CountDownLatch(1)
        val token = CancelToken()
        val c = GeminiAiClient({ AiProviderSettings(geminiApiKey = "K", customBaseUrl = base) })
        thread {
            while (seen.isEmpty()) Thread.sleep(5)
            token.cancel()
        }
        val e = assertThrows(AiException::class.java) { c.generate(GeminiRequest("m", null, listOf(GeminiContent("user", "x")), timeoutMs = 10_000), token) }
        assertEquals(AiErrorCode.CANCELLED, e.code)
    }

    @Test
    fun unreachableHostIsNetworkWithHost() {
        val port = server.address.port
        server.stop(0)
        val c = GeminiAiClient({ AiProviderSettings(geminiApiKey = "K", customBaseUrl = "http://127.0.0.1:$port", timeoutMs = 2_000) })
        val e = assertThrows(AiException::class.java) { c.generate(GeminiRequest("m", null, emptyList(), timeoutMs = 2_000)) }
        assertEquals(AiErrorCode.NETWORK, e.code)
        assertTrue(e.message!!.contains("127.0.0.1"))
        start() // keep @AfterEach happy
    }
}
