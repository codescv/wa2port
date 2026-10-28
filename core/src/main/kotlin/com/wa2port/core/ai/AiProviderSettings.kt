package com.wa2port.core.ai

import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * AI provider configuration (persisted inside the app's `settings.json`).
 *
 * Two provider types: [PROVIDER_GEMINI_API] (Google AI Studio API key) and [PROVIDER_VERTEX_AI]
 * (Google Cloud Vertex AI: service account JSON / access token / Express API key). Both honour
 * [customBaseUrl] (自定义服务器地址 / reverse proxy); see [AiEndpoints] for the URL rules.
 */
@Serializable
data class AiProviderSettings(
    /** `gemini-api` | `vertex-ai`. */
    val providerType: String = PROVIDER_GEMINI_API,
    // --- Gemini API (Google AI Studio)
    val geminiApiKey: String = "",
    /** `v1beta` | `v1`. */
    val geminiApiVersion: String = "v1beta",
    // --- 自定义服务器地址 / 代理 (applies to BOTH provider types)
    val customBaseUrl: String = "",
    /** Gemini API only: `x-goog-api-key` | `bearer` | `query-key`. */
    val proxyAuthStyle: String = AUTH_STYLE_HEADER,
    /** Advanced: one `Name: value` per line, `{apiKey}` placeholder allowed; sent on every request. */
    val extraHeaders: String = "",
    // --- Vertex AI
    val vertexProject: String = "gps-babel-tower",
    val vertexLocation: String = "global",
    /** `service-account` | `access-token` | `express-api-key`. */
    val vertexAuthMode: String = VERTEX_AUTH_SERVICE_ACCOUNT,
    val vertexServiceAccountJson: String = "",
    val vertexAccessToken: String = "",
    val vertexApiKey: String = "",
    // --- Generation
    val model: String = DEFAULT_MODEL,
    val temperature: Float = 0.2f,
    /**
     * −1 = model default (dynamic), 0 = off, > 0 tokens. Used as-is by follow-up questions; the
     * sentence breakdown and the connection test use [explainThinkingBudget] instead.
     */
    val thinkingBudget: Int = -1,
    val timeoutMs: Int = 60_000,
    val cacheEnabled: Boolean = true,
) {
    val isVertex: Boolean get() = providerType == PROVIDER_VERTEX_AI

    /**
     * Thinking budget for `explainSentence` / `testConnection`: structured JSON extraction against
     * a strict `responseSchema` gains nothing from dynamic thinking, so the default −1 becomes 0
     * (5–15 s → 2–5 s on Flash, far fewer thinking tokens); an explicit budget ≥ 0 is kept. Models
     * that reject `thinkingConfig` are handled by the HTTP 400 fallback in [GeminiAiClient.generate].
     * Not part of the cache key, so existing cached breakdowns stay valid.
     */
    val explainThinkingBudget: Int get() = if (thinkingBudget < 0) 0 else thinkingBudget

    /** True when a request could be attempted (credentials present for the selected provider). */
    val isConfigured: Boolean get() = validationError() == null

    /** Human-readable (zh / en) reason why the settings are incomplete, or null when usable. */
    fun validationError(): String? {
        if (AiEndpoints.sanitizeModel(model).isEmpty()) return "未设置模型 Model is empty"
        if (!isVertex) {
            // A proxy may inject the key server-side, so a custom base URL alone is enough.
            return if (AiEndpoints.sanitizeValue(geminiApiKey).isEmpty() && AiEndpoints.normalizeBaseUrl(customBaseUrl).isEmpty()) {
                "未填写 Gemini API Key"
            } else {
                null
            }
        }
        return when (vertexAuthMode) {
            VERTEX_AUTH_EXPRESS -> if (AiEndpoints.sanitizeValue(vertexApiKey).isEmpty()) "未填写 Vertex Express API Key" else null
            VERTEX_AUTH_ACCESS_TOKEN -> when {
                AiEndpoints.sanitizeValue(vertexProject).isEmpty() -> "未填写 Vertex 项目 Project"
                AiEndpoints.sanitizeValue(vertexAccessToken).isEmpty() -> "未填写 Access Token"
                else -> null
            }
            else -> when {
                AiEndpoints.sanitizeValue(vertexProject).isEmpty() -> "未填写 Vertex 项目 Project"
                vertexServiceAccountJson.isBlank() -> "未填写服务账号 JSON Service account JSON"
                else -> null
            }
        }
    }

    /**
     * Normalises [model] on settings load: strips invisible paste characters / `models/` prefix,
     * and migrates blank or retired Gemini 1.x / 2.0 / 2.5 model IDs (e.g. `gemini-2.5-flash`,
     * `gemini-2.5-pro`, `gemini-2.0-flash`) to [DEFAULT_MODEL].
     */
    fun withNormalizedModel(): AiProviderSettings {
        val cleaned = AiEndpoints.sanitizeModel(model)
        val normalized = if (cleaned.isEmpty() || RETIRED_GEMINI_MODEL.matches(cleaned)) DEFAULT_MODEL else cleaned
        return if (normalized == model) this else copy(model = normalized)
    }

    /**
     * Non-blocking warning for the settings UI: plain `http://` is only allowed by the app's network
     * security config for localhost / 127.0.0.1 / 10.0.2.2 (emulator host).
     */
    fun connectionWarning(): String? {
        val base = AiEndpoints.normalizeBaseUrl(customBaseUrl)
        if (!base.startsWith("http://", ignoreCase = true)) return null
        val host = AiEndpoints.hostOf(base).lowercase()
        return if (host in AiEndpoints.CLEARTEXT_HOSTS) null else "系统禁止明文 http：请使用 https 地址 (cleartext http is blocked)"
    }

    /** Redacts every secret so settings can never leak through logs / crash reports. */
    override fun toString(): String =
        "AiProviderSettings(providerType=$providerType, geminiApiKey=${redact(geminiApiKey)}, geminiApiVersion=$geminiApiVersion, " +
            "customBaseUrl=${AiEndpoints.normalizeBaseUrl(customBaseUrl)}, proxyAuthStyle=$proxyAuthStyle, extraHeaders=${redact(extraHeaders)}, " +
            "vertexProject=$vertexProject, vertexLocation=$vertexLocation, vertexAuthMode=$vertexAuthMode, " +
            "vertexServiceAccountJson=${redact(vertexServiceAccountJson)}, vertexAccessToken=${redact(vertexAccessToken)}, vertexApiKey=${redact(vertexApiKey)}, " +
            "model=$model, temperature=$temperature, thinkingBudget=$thinkingBudget, timeoutMs=$timeoutMs, cacheEnabled=$cacheEnabled)"

    /** API key used for the `{apiKey}` placeholder in [extraHeaders]. */
    val placeholderKey: String get() = if (isVertex) vertexApiKey else geminiApiKey

    companion object {
        const val PROVIDER_GEMINI_API = "gemini-api"
        const val PROVIDER_VERTEX_AI = "vertex-ai"
        const val AUTH_STYLE_HEADER = "x-goog-api-key"
        const val AUTH_STYLE_BEARER = "bearer"
        const val AUTH_STYLE_QUERY = "query-key"
        const val VERTEX_AUTH_SERVICE_ACCOUNT = "service-account"
        const val VERTEX_AUTH_ACCESS_TOKEN = "access-token"
        const val VERTEX_AUTH_EXPRESS = "express-api-key"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        val MODEL_PRESETS = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash")
        val VERTEX_LOCATION_PRESETS = listOf("global", "us-central1", "asia-northeast1", "europe-west1")
        val API_VERSIONS = listOf("v1beta", "v1")

        private val RETIRED_GEMINI_MODEL = Regex("^gemini-(?:1\\.[05]|2\\.[05])(?:$|[-.].*)", RegexOption.IGNORE_CASE)

        private fun redact(v: String): String = if (v.isEmpty()) "\"\"" else "***(${v.length})"
    }
}

/** URL and header construction for both providers, including the custom base URL / proxy rules. */
object AiEndpoints {
    const val DEFAULT_GEMINI_BASE = "https://generativelanguage.googleapis.com"

    /** Hosts for which the app's network security config permits cleartext `http://`. */
    val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    private val VERSION_SEGMENT = Regex("/(v1beta1|v1beta|v1alpha|v1)$", RegexOption.IGNORE_CASE)
    private val ENDPOINT_TAIL = Regex("/(models|publishers|projects)(/.*)?$", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val HEADER_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    private val MODEL_PREFIX = Regex("^models/", RegexOption.IGNORE_CASE)

    /** Cleans paste artefacts: whitespace/invisible chars anywhere, `?query` / `#fragment`, scheme (`https://` added when missing), trailing `/`, pasted endpoint tails. */
    private fun cleanBase(raw: String): String {
        var s = sanitizeValue(raw).filterNot { it.isWhitespace() }
        if (s.isEmpty()) return s
        s = s.substringBefore('#').substringBefore('?')
        s = if (SCHEME.containsMatchIn(s)) {
            val scheme = s.substringBefore("://").lowercase()
            "$scheme://" + s.substringAfter("://")
        } else {
            "https://$s"
        }
        s = s.trimEnd('/')
        // Pasted full endpoint (…/v1beta/models/x:generateContent, …/v1/projects/p/…): keep the prefix.
        val authorityEnd = s.indexOf('/', s.indexOf("://") + 3).let { if (it < 0) s.length else it }
        val path = s.substring(authorityEnd)
        val tail = ENDPOINT_TAIL.find(path)
        if (tail != null) s = s.substring(0, authorityEnd + tail.range.first)
        return s.trimEnd('/')
    }

    /**
     * Normalises a user-entered base URL: strip whitespace, `?query` and `#fragment` (a pasted
     * `?key=` is never kept) → prepend `https://` when there is no scheme → strip trailing `/` →
     * strip a pasted endpoint tail (`/models/…`, `/publishers/…`, `/projects/…`) → strip ONE trailing
     * version segment (`/v1`, `/v1beta`, `/v1beta1`, `/v1alpha`, case-insensitive, whole segment
     * only) → strip trailing `/`. The version is re-added by [generateContentUrl] (see [pastedVersion]).
     */
    fun normalizeBaseUrl(raw: String): String {
        val s = cleanBase(raw)
        if (s.isEmpty()) return s
        return stripVersion(s)
    }

    private fun stripVersion(s: String): String {
        val authorityEnd = s.indexOf('/', s.indexOf("://") + 3)
        if (authorityEnd < 0) return s // version can only live in the path
        return VERSION_SEGMENT.replace(s, "").trimEnd('/')
    }

    /** API version the user pasted at the end of [raw] (`v1`, `v1beta`, …, lower-cased) or null. The pasted version wins over the setting. */
    fun pastedVersion(raw: String): String? {
        val s = cleanBase(raw)
        if (s.isEmpty() || s.indexOf('/', s.indexOf("://") + 3) < 0) return null
        return VERSION_SEGMENT.find(s)?.groupValues?.get(1)?.lowercase()
    }

    /** Base URL of the Gemini API (official host or the normalised custom base). */
    fun geminiBase(s: AiProviderSettings): String =
        normalizeBaseUrl(s.customBaseUrl).ifEmpty { DEFAULT_GEMINI_BASE }

    /** Default Vertex host for the location (`global` → no regional prefix) or the normalised custom base. */
    fun vertexBase(s: AiProviderSettings): String {
        val custom = normalizeBaseUrl(s.customBaseUrl)
        if (custom.isNotEmpty()) return custom
        val loc = sanitizeValue(s.vertexLocation).filterNot { it.isWhitespace() }.ifEmpty { "global" }
        return if (loc == "global") "https://aiplatform.googleapis.com" else "https://$loc-aiplatform.googleapis.com"
    }

    /** Effective Gemini API version: version pasted in the custom URL, else the setting (default v1beta). */
    fun geminiVersion(s: AiProviderSettings): String =
        pastedVersion(s.customBaseUrl) ?: sanitizeValue(s.geminiApiVersion).trim('/').ifEmpty { "v1beta" }

    /** Effective Vertex API version: version pasted in the custom URL, else `v1`. */
    fun vertexVersion(s: AiProviderSettings): String = pastedVersion(s.customBaseUrl) ?: "v1"

    private fun enc(v: String): String = URLEncoder.encode(v.trim(), "UTF-8").replace("+", "%20")

    /** Full `generateContent` URL (query-string secrets included; header secrets come from [headers]). */
    fun generateContentUrl(s: AiProviderSettings, model: String = s.model): String {
        val m = enc(sanitizeModel(model))
        if (!s.isVertex) {
            val base = "${geminiBase(s)}/${geminiVersion(s)}/models/$m:generateContent"
            val key = sanitizeValue(s.geminiApiKey)
            return if (s.proxyAuthStyle == AiProviderSettings.AUTH_STYLE_QUERY && key.isNotEmpty()) {
                "$base?key=${enc(key)}"
            } else {
                base
            }
        }
        val base = "${vertexBase(s)}/${vertexVersion(s)}"
        if (s.vertexAuthMode == AiProviderSettings.VERTEX_AUTH_EXPRESS) {
            return "$base/publishers/google/models/$m:generateContent?key=${enc(sanitizeValue(s.vertexApiKey))}"
        }
        val loc = sanitizeValue(s.vertexLocation).filterNot { it.isWhitespace() }.ifEmpty { "global" }
        return "$base/projects/${enc(sanitizeValue(s.vertexProject))}/locations/${enc(loc)}/publishers/google/models/$m:generateContent"
    }

    /** [url] with any `key=` query value masked (safe for logs and error messages). */
    fun redactUrl(url: String): String = url.replace(Regex("([?&]key=)[^&#]*"), "$1***")

    /**
     * Request headers: content type (`application/json`, matching kotori), provider auth
     * ([bearerToken] for Vertex SA / access-token) and the user's [AiProviderSettings.extraHeaders]
     * (which override earlier entries case-insensitively). Header values never contain CR/LF or
     * invisible format characters, names are RFC 7230 tokens, so the transport cannot throw.
     */
    fun headers(s: AiProviderSettings, bearerToken: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = "application/json"
        if (!s.isVertex) {
            val key = sanitizeValue(s.geminiApiKey)
            if (key.isNotEmpty()) {
                when (s.proxyAuthStyle) {
                    AiProviderSettings.AUTH_STYLE_BEARER -> h["Authorization"] = "Bearer $key"
                    AiProviderSettings.AUTH_STYLE_QUERY -> {}
                    else -> h["x-goog-api-key"] = key
                }
            }
        } else if (s.vertexAuthMode != AiProviderSettings.VERTEX_AUTH_EXPRESS) {
            val token = bearerToken?.let(::sanitizeValue).orEmpty()
            if (token.isNotEmpty()) h["Authorization"] = "Bearer $token"
        }
        for ((name, value) in parseExtraHeaders(s.extraHeaders, sanitizeValue(s.placeholderKey))) {
            h.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { h.remove(it) }
            h[name] = value
        }
        return h
    }

    private fun isUnicodeInvisibleOrOddSpace(c: Char): Boolean =
        c == '\u00A0' ||
            c == '\u180E' ||
            c in '\u2000'..'\u200F' ||
            c in '\u2028'..'\u202F' ||
            c in '\u205F'..'\u206F' ||
            c == '\u3000' ||
            c == '\uFEFF' ||
            Character.getType(c) == Character.FORMAT.toInt()

    /**
     * Trims and removes CR/LF, control characters, and Unicode zero-width / format / non-breaking
     * space characters that often get pasted with API keys, model names or headers on Android.
     */
    fun sanitizeValue(v: String): String =
        v.filter { it >= ' ' && it !in '\u007F'..'\u009F' && !isUnicodeInvisibleOrOddSpace(it) }.trim()

    /** Sanitizes a model identifier (strips invisible characters, whitespace and optional `models/` prefix). */
    fun sanitizeModel(v: String): String =
        sanitizeValue(v).filterNot { it.isWhitespace() }.replace(MODEL_PREFIX, "")

    /** `Name: value` lines → pairs; malformed lines (no colon, non-token name) are skipped; `{apiKey}` substituted. */
    fun parseExtraHeaders(text: String, apiKey: String): List<Pair<String, String>> =
        text.split('\n', '\r').mapNotNull { raw ->
            val line = sanitizeValue(raw)
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val name = line.substring(0, colon).trim()
            if (!HEADER_TOKEN.matches(name)) return@mapNotNull null
            name to sanitizeValue(line.substring(colon + 1).replace("{apiKey}", apiKey))
        }

    /** Host of [url] for error messages (never the full URL: it may carry `?key=`). */
    fun hostOf(url: String): String {
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    }
}
