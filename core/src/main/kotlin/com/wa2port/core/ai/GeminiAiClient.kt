package com.wa2port.core.ai

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Cooperative cancellation for blocking HTTP calls: [cancel] aborts the attached connection. */
class CancelToken {
    @Volatile
    var isCancelled: Boolean = false
        private set
    private var abort: (() -> Unit)? = null

    /** Cancels; the currently attached request (if any) is aborted immediately. */
    fun cancel() {
        val a: (() -> Unit)?
        synchronized(this) {
            isCancelled = true
            a = abort
            abort = null
        }
        runCatching { a?.invoke() }
    }

    /**
     * Registers [onAbort] for the running request. A cancel that arrived before attaching aborts
     * right away and throws [IOException] (cancel-before-attach race).
     */
    fun attach(onAbort: () -> Unit) {
        synchronized(this) {
            if (!isCancelled) {
                abort = onAbort
                return
            }
        }
        runCatching { onAbort() }
        throw IOException("cancelled")
    }

    fun detach() {
        synchronized(this) { abort = null }
    }
}

/** Minimal blocking HTTP POST seam (faked in tests). */
interface HttpTransport {
    data class Response(val status: Int, val body: String)

    /** POSTs [body]; throws [IOException] on transport failure. */
    fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): Response
}

/** [HttpTransport] over `java.net.HttpURLConnection` (works on the JVM and on Android). */
class HttpUrlConnectionTransport : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, timeoutMs: Int, cancel: CancelToken?): HttpTransport.Response {
        if (cancel?.isCancelled == true) throw IOException("cancelled")
        // URL(String) is deprecated, but URI(...).toURL() is stricter and would reject URLs that work today.
        @Suppress("DEPRECATION")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            cancel?.attach { conn.disconnect() }
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs.coerceAtLeast(1_000)
            conn.readTimeout = timeoutMs.coerceAtLeast(1_000)
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpTransport.Response(status, text)
        } catch (e: HttpRetryException) {
            // Streaming mode cannot replay the body for an auth challenge (401 + WWW-Authenticate) or a
            // redirect: report the status so it maps to AUTH / etc. instead of NETWORK.
            return HttpTransport.Response(e.responseCode(), e.reason ?: "")
        } finally {
            cancel?.detach()
            conn.disconnect()
        }
    }
}

/**
 * REST-only Gemini client for the Gemini API and Vertex AI (port of kotori `ai/gemini-client.ts` +
 * `ai/explain-service.ts`). Blocking: call from a background thread (`Dispatchers.IO` in the app).
 * Settings are read per call, so edits take effect on the next request. Every failure surfaces as
 * an [AiException] — nothing else escapes.
 */
class GeminiAiClient(
    private val settings: () -> AiProviderSettings,
    private val transport: HttpTransport = HttpUrlConnectionTransport(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var saProvider: ServiceAccountTokenProvider? = null

    /** Bearer token for Vertex SA / access-token modes (null otherwise). */
    private fun bearerToken(s: AiProviderSettings, cancel: CancelToken?): String? {
        if (!s.isVertex) return null
        return when (s.vertexAuthMode) {
            AiProviderSettings.VERTEX_AUTH_ACCESS_TOKEN -> s.vertexAccessToken.trim()
            AiProviderSettings.VERTEX_AUTH_EXPRESS -> null
            else -> serviceAccount(s).token(cancel)
        }
    }

    /** Service-account provider, recreated when the key JSON changes (cached token reused otherwise). */
    @Synchronized
    private fun serviceAccount(s: AiProviderSettings): ServiceAccountTokenProvider {
        val fp = ExplainCache.sha256Hex(s.vertexServiceAccountJson.trim()).take(16)
        val viaProxy = s.customBaseUrl.isNotBlank()
        saProvider?.takeIf { it.keyFingerprint == fp }?.let { return it }
        return ServiceAccountTokenProvider(s.vertexServiceAccountJson, transport, now, viaProxy).also { saProvider = it }
    }

    /**
     * Models that rejected an explicit `thinkingConfig` with HTTP 400 (e.g. a model that refuses
     * `thinkingBudget = 0` or only accepts `thinkingLevel`). Requests for them omit `thinkingConfig`.
     * In-memory only: a new client (app restart) probes again with a single extra request.
     */
    private val thinkingUnsupportedModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when [model] was memoized as rejecting `thinkingConfig` (see [generate]). */
    fun isThinkingUnsupported(model: String): Boolean = model in thinkingUnsupportedModels

    /**
     * One `generateContent` round trip. If the request carries a `thinkingConfig` (budget ≥ 0) and
     * the server answers HTTP 400 mentioning "thinking", the model is memoized in
     * [thinkingUnsupportedModels] and the call is retried ONCE without `thinkingConfig`; later
     * requests for that model drop `thinkingConfig` up front.
     */
    fun generate(req: GeminiRequest, cancel: CancelToken? = null): GeminiResponse {
        val sendsThinking = (req.thinkingBudget ?: -1) >= 0
        if (sendsThinking && req.model in thinkingUnsupportedModels) return generateOnce(req.copy(thinkingBudget = null), cancel)
        return try {
            generateOnce(req, cancel)
        } catch (e: AiException) {
            if (!sendsThinking || !isThinkingRejection(e) || cancel?.isCancelled == true) throw e
            thinkingUnsupportedModels += req.model
            generateOnce(req.copy(thinkingBudget = null), cancel)
        }
    }

    /** HTTP 400 whose error message / body mentions `thinking` (case-insensitive). */
    private fun isThinkingRejection(e: AiException): Boolean =
        e.httpStatus == 400 && e.message?.contains("thinking", ignoreCase = true) == true

    private fun generateOnce(req: GeminiRequest, cancel: CancelToken?): GeminiResponse {
        val s = settings()
        s.validationError()?.let { throw AiException(AiErrorCode.NOT_CONFIGURED, it) }
        var host = ""
        try {
            if (cancel?.isCancelled == true) throw AiException(AiErrorCode.CANCELLED, "Request cancelled")
            val url = AiEndpoints.generateContentUrl(s, req.model)
            host = AiEndpoints.hostOf(url)
            val headers = AiEndpoints.headers(s, bearerToken(s, cancel))
            val body = ExplainJsonCodec.buildGenerateBody(req).toByteArray(Charsets.UTF_8)
            val res = transport.post(url, headers, body, req.timeoutMs, cancel)
            if (cancel?.isCancelled == true) throw AiException(AiErrorCode.CANCELLED, "Request cancelled")
            if (res.status !in 200..299) {
                throw AiException(ExplainJsonCodec.mapHttpError(res.status, res.body), ExplainJsonCodec.extractErrorMessage(res.body, res.status), httpStatus = res.status)
            }
            return ExplainJsonCodec.parseGenerateResponse(res.body, req.model)
        } catch (e: Exception) {
            throw mapError(e, cancel, host, req.timeoutMs)
        }
    }

    /** Translates any failure into an [AiException] (cancellation wins over the I/O error it causes). */
    private fun mapError(e: Exception, cancel: CancelToken?, host: String, timeoutMs: Int): AiException {
        if (e is AiException) return e
        if (cancel?.isCancelled == true) return AiException(AiErrorCode.CANCELLED, "Request cancelled", e)
        val where = if (host.isNotEmpty()) " ($host)" else ""
        return when (e) {
            is SocketTimeoutException -> AiException(AiErrorCode.TIMEOUT, "请求超时 Request timed out after $timeoutMs ms$where", e)
            is UnknownServiceException -> AiException(AiErrorCode.NETWORK, "系统禁止明文 http：请使用 https 地址$where", e)
            is UnknownHostException -> AiException(AiErrorCode.NETWORK, "无法解析服务器地址 Unknown host$where", e)
            is IOException -> AiException(AiErrorCode.NETWORK, "网络错误 Network error$where: ${e.message ?: e.javaClass.simpleName}", e)
            is SerializationException -> AiException(AiErrorCode.INVALID_RESPONSE, "模型返回了无法解析的内容: ${e.message?.take(160)}", e)
            is IllegalArgumentException -> AiException(AiErrorCode.INVALID_ARGUMENT, "请求参数无效 Invalid request$where: ${e.message}", e)
            else -> AiException(AiErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}$where", e)
        }
    }

    /** Cache id of [req] under the current model. */
    fun cacheKey(req: ExplainRequest): String = ExplainCache.key(req, settings().model)

    /**
     * Sentence breakdown: cache lookup (unless `force` / cache disabled) → structured-output call
     * → tolerant parse; an invalid answer is retried ONCE with [INVALID_JSON_NUDGE] (not when the
     * output was truncated at MAX_TOKENS) → span alignment → cache. `force` also drops the cached
     * follow-up thread, whose context was the old breakdown.
     */
    fun explainSentence(req: ExplainRequest, cache: ExplainCache?, cancel: CancelToken? = null): SentenceExplanation {
        val s = settings()
        s.validationError()?.let { throw AiException(AiErrorCode.NOT_CONFIGURED, it) }
        if (req.sentence.isBlank()) throw AiException(AiErrorCode.INVALID_ARGUMENT, "这一行没有日文 No Japanese text")
        val id = ExplainCache.key(req, s.model)
        val useCache = cache != null && s.cacheEnabled
        if (useCache && !req.force) {
            runCatching { cache!!.get(id) }.getOrNull()?.let { return it }
        }
        val prompt = buildExplainPrompt(req)
        val base = GeminiRequest(
            model = s.model,
            systemInstruction = prompt.systemInstruction,
            contents = prompt.contents,
            responseMimeType = "application/json",
            responseSchema = ExplainJsonCodec.EXPLAIN_RESPONSE_SCHEMA,
            temperature = s.temperature,
            // Structured extraction against a strict schema: no dynamic thinking (−1 → 0).
            thinkingBudget = s.explainThinkingBudget,
            timeoutMs = s.timeoutMs,
        )
        var res = generate(base, cancel)
        var raw = try {
            ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson(res.text))
        } catch (e: AiException) {
            if (e.code != AiErrorCode.INVALID_RESPONSE) throw e
            if (res.finishReason == "MAX_TOKENS") {
                throw AiException(AiErrorCode.INVALID_RESPONSE, "回答被截断：请提高 maxOutputTokens / 降低思考预算 (output truncated at MAX_TOKENS)", e)
            }
            null
        }
        if (raw == null) {
            val retry = base.copy(contents = base.contents + GeminiContent("model", res.text) + GeminiContent("user", INVALID_JSON_NUDGE))
            res = generate(retry, cancel)
            raw = ExplainJsonCodec.parseRawExplanation(ExplainJsonCodec.parseModelJson(res.text))
        }
        val expl = try {
            ExplainJsonCodec.toSentenceExplanation(raw, req, ExplanationMeta(id = id, model = res.model, usage = res.usage, now = now()))
        } catch (e: Exception) {
            throw AiException(AiErrorCode.INVALID_RESPONSE, "无法对齐模型输出 Cannot assemble explanation: ${e.message}", e)
        }
        if (useCache) {
            runCatching {
                cache!!.put(expl)
                if (req.force) cache.clearThread(id)
            }
        }
        return expl
    }

    /**
     * Follow-up question about [expl] in the voice of [persona]. Temperature ≥ 0.4; an empty answer
     * is INVALID_RESPONSE. On success the user + model turns are appended to the cached thread
     * (failed questions are never persisted, so they never reach the next history).
     */
    fun askFollowUp(
        expl: SentenceExplanation,
        history: List<FollowUpMessage>,
        question: String,
        persona: AiPersonaSettings,
        cache: ExplainCache?,
        cancel: CancelToken? = null,
    ): FollowUpMessage {
        val s = settings()
        val q = question.trim()
        if (q.isEmpty()) throw AiException(AiErrorCode.INVALID_ARGUMENT, "问题为空 Empty question")
        val prompt = buildFollowUpPrompt(expl, history, q, persona)
        val res = generate(
            GeminiRequest(
                model = s.model,
                systemInstruction = prompt.systemInstruction,
                contents = prompt.contents,
                temperature = maxOf(s.temperature, 0.4f),
                // Free-form Q&A benefits from thinking: keep the user's budget (default −1 = dynamic).
                thinkingBudget = s.thinkingBudget,
                timeoutMs = s.timeoutMs,
            ),
            cancel,
        )
        val answer = res.text.trim()
        if (answer.isEmpty()) throw AiException(AiErrorCode.INVALID_RESPONSE, "模型没有回答 Empty answer")
        val t = now()
        val userMsg = FollowUpMessage(newId(), FollowUpMessage.ROLE_USER, q, t)
        val modelMsg = FollowUpMessage(newId(), FollowUpMessage.ROLE_MODEL, answer, t, res.usage)
        if (cache != null && s.cacheEnabled) {
            runCatching {
                cache.appendThread(expl.id, userMsg)
                cache.appendThread(expl.id, modelMsg)
            }
        }
        return modelMsg
    }

    /** Round-trips [TEST_PROMPT] through the configured provider. Never throws. */
    fun testConnection(): AiConnectionTestResult {
        val started = now()
        val model = runCatching { settings().model }.getOrDefault("")
        return try {
            val s = settings()
            val res = generate(GeminiRequest(model = s.model, systemInstruction = null, contents = listOf(GeminiContent("user", TEST_PROMPT)), temperature = 0f, thinkingBudget = s.explainThinkingBudget, timeoutMs = s.timeoutMs))
            AiConnectionTestResult(true, res.model, now() - started, res.text.trim().ifEmpty { "OK" })
        } catch (e: Exception) {
            val err = e as? AiException ?: mapError(e, null, "", 0)
            AiConnectionTestResult(false, model, now() - started, "${err.code}: ${err.message}")
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().take(12)
}
