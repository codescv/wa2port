package com.wa2port.core.ai

import com.wa2port.core.ai.AiProviderSettings.Companion.AUTH_STYLE_BEARER
import com.wa2port.core.ai.AiProviderSettings.Companion.AUTH_STYLE_QUERY
import com.wa2port.core.ai.AiProviderSettings.Companion.PROVIDER_VERTEX_AI
import com.wa2port.core.ai.AiProviderSettings.Companion.VERTEX_AUTH_EXPRESS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiEndpointsTest {
    private val m = AiProviderSettings.DEFAULT_MODEL
    private val gem = AiProviderSettings(geminiApiKey = "K")
    private val vtx = AiProviderSettings(providerType = PROVIDER_VERTEX_AI, vertexServiceAccountJson = "{}")

    private fun url(s: AiProviderSettings) = AiEndpoints.generateContentUrl(s)

    @Test
    fun geminiDefault() {
        assertEquals("gemini-3.5-flash-lite", AiProviderSettings.DEFAULT_MODEL)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"), AiProviderSettings.MODEL_PRESETS)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", url(gem))
        val h = AiEndpoints.headers(gem)
        assertEquals("K", h["x-goog-api-key"])
        assertEquals("application/json", h["Content-Type"])
        assertFalse(h.containsKey("Authorization"))
    }

    @Test
    fun customBaseUrlVariants() {
        val expected = "https://p.example.com/v1beta/models/$m:generateContent"
        assertEquals(expected, url(gem.copy(customBaseUrl = "https://p.example.com")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "https://p.example.com/")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "https://p.example.com///")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "https://p.example.com/v1beta")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "https://p.example.com/v1beta/")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "  https://p.example.com/V1BETA/ \n")))
        assertEquals(expected, url(gem.copy(customBaseUrl = "HTTPS://p.example.com")))
        assertEquals("https://p.example.com/gemini/v1beta/models/$m:generateContent", url(gem.copy(customBaseUrl = "p.example.com/gemini")))
        // Custom URL keeps the default x-goog-api-key header.
        assertEquals("K", AiEndpoints.headers(gem.copy(customBaseUrl = "https://p.example.com"))["x-goog-api-key"])
    }

    @Test
    fun apiVersionSettingAndPastedVersion() {
        assertEquals("https://p.example.com/v1/models/$m:generateContent", url(gem.copy(customBaseUrl = "https://p.example.com", geminiApiVersion = "v1")))
        // A version pasted in the URL wins over the setting (never silently rewritten to v1beta).
        assertEquals("https://p.example.com/v1/models/$m:generateContent", url(gem.copy(customBaseUrl = "https://p.example.com/v1")))
        assertEquals("https://p.example.com/v1alpha/models/$m:generateContent", url(gem.copy(customBaseUrl = "https://p.example.com/v1alpha/")))
        assertEquals("v1", AiEndpoints.pastedVersion("https://p.example.com/v1/"))
        assertNull(AiEndpoints.pastedVersion("https://p.example.com"))
    }

    @Test
    fun normalizeEdgeCases() {
        assertEquals("https://p.example.com", AiEndpoints.normalizeBaseUrl("https://p.example.com/v1beta/models/gemini-x:generateContent?key=SECRET"))
        assertEquals("https://p.example.com/proxy", AiEndpoints.normalizeBaseUrl("https://p.example.com/proxy/v1/projects/p/locations/global/publishers/google/models/x:generateContent"))
        assertEquals("https://p.example.com", AiEndpoints.normalizeBaseUrl("https://p.example.com#frag"))
        assertEquals("https://v1.example.com", AiEndpoints.normalizeBaseUrl("https://v1.example.com"))
        assertEquals("https://h/foo-v1", AiEndpoints.normalizeBaseUrl("https://h/foo-v1"))
        assertEquals("https://p.example.com/vertex", AiEndpoints.normalizeBaseUrl("https://p.example.com/vertex/v1beta1"))
        assertEquals("https://[::1]:8443/x", AiEndpoints.normalizeBaseUrl("https://[::1]:8443/x"))
        assertEquals("http://10.0.2.2:8080", AiEndpoints.normalizeBaseUrl("http://10.0.2.2:8080/"))
        assertEquals("https://h:8443/a/b", AiEndpoints.normalizeBaseUrl("h:8443/a/b/"))
        assertEquals("", AiEndpoints.normalizeBaseUrl("   "))
    }

    @Test
    fun queryKeyAndBearerStyles() {
        val q = gem.copy(customBaseUrl = "https://p.example.com", proxyAuthStyle = AUTH_STYLE_QUERY)
        assertEquals("https://p.example.com/v1beta/models/$m:generateContent?key=K", url(q))
        assertFalse(AiEndpoints.headers(q).containsKey("x-goog-api-key"))
        assertFalse(AiEndpoints.headers(q).containsKey("Authorization"))

        val b = gem.copy(customBaseUrl = "https://p.example.com", proxyAuthStyle = AUTH_STYLE_BEARER)
        assertEquals("https://p.example.com/v1beta/models/$m:generateContent", url(b))
        assertEquals("Bearer K", AiEndpoints.headers(b)["Authorization"])
        assertFalse(AiEndpoints.headers(b).containsKey("x-goog-api-key"))
    }

    @Test
    fun vertexUrls() {
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/gps-babel-tower/locations/global/publishers/google/models/$m:generateContent",
            url(vtx),
        )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/gps-babel-tower/locations/us-central1/publishers/google/models/$m:generateContent",
            url(vtx.copy(vertexLocation = "us-central1")),
        )
        val express = vtx.copy(vertexAuthMode = VERTEX_AUTH_EXPRESS, vertexApiKey = "K")
        assertEquals("https://aiplatform.googleapis.com/v1/publishers/google/models/$m:generateContent?key=K", url(express))
        assertFalse(AiEndpoints.headers(express, "ignored").containsKey("Authorization"))
        assertEquals(
            "https://vp.example.com/v1/projects/gps-babel-tower/locations/global/publishers/google/models/$m:generateContent",
            url(vtx.copy(customBaseUrl = "https://vp.example.com/v1/")),
        )
        assertEquals(
            "https://vp.example.com/v1/projects/gps-babel-tower/locations/asia-northeast1/publishers/google/models/$m:generateContent",
            url(vtx.copy(customBaseUrl = "vp.example.com", vertexLocation = "asia-northeast1")),
        )
        assertEquals("Bearer TOKEN", AiEndpoints.headers(vtx, "TOKEN")["Authorization"])
    }

    @Test
    fun extraHeaders() {
        val s = gem.copy(extraHeaders = "X-Proxy-Token: {apiKey}\nbad line\nBad Name: x\n: novalue\nX-Evil: a\rInjected: b\nx-goog-api-key: OVERRIDE")
        val h = AiEndpoints.headers(s)
        assertEquals("K", h["X-Proxy-Token"])
        assertFalse(h.keys.any { it.contains(' ') })
        assertEquals("a", h["X-Evil"])
        assertEquals("b", h["Injected"])
        assertEquals("OVERRIDE", h["x-goog-api-key"])
        assertTrue(h.values.none { v -> v.any { it == '\r' || it == '\n' } })
        // Key with stray CR/LF never reaches a header value.
        assertEquals("K2", AiEndpoints.headers(AiProviderSettings(geminiApiKey = " K2\r\n"))["x-goog-api-key"])
    }

    @Test
    fun configurationAndRedaction() {
        assertFalse(AiProviderSettings().isConfigured)
        assertTrue(gem.isConfigured)
        assertTrue(AiProviderSettings(customBaseUrl = "https://p").isConfigured)
        assertFalse(vtx.copy(vertexServiceAccountJson = "").isConfigured)
        assertFalse(vtx.copy(vertexProject = "").isConfigured)
        assertTrue(vtx.copy(vertexProject = "", vertexAuthMode = VERTEX_AUTH_EXPRESS, vertexApiKey = "k").isConfigured)
        val str = AiProviderSettings(geminiApiKey = "SECRET-KEY", vertexAccessToken = "TOK", vertexApiKey = "VK").toString()
        assertFalse(str.contains("SECRET-KEY") || str.contains("TOK") || str.contains("VK,"))
        assertNull(AiProviderSettings(customBaseUrl = "http://10.0.2.2:8080").connectionWarning())
        assertTrue(AiProviderSettings(customBaseUrl = "http://192.168.1.2:8080").connectionWarning() != null)
        assertEquals("p.example.com", AiEndpoints.hostOf("https://p.example.com:443/v1?key=x"))
        assertEquals("https://h/x?key=***&alt=sse", AiEndpoints.redactUrl("https://h/x?key=abc&alt=sse"))
    }

    @Test
    fun unicodeInvisibleCharAndProxySanitization() {
        val dirtyKey = "\uFEFF\u200BAIzaSy_TEST\u200E\u00A0\u3000\u2060\r\n"
        assertEquals("AIzaSy_TEST", AiEndpoints.sanitizeValue(dirtyKey))

        val cfProxy = AiProviderSettings(
            geminiApiKey = dirtyKey,
            customBaseUrl = " \u200Bhttps://gemini.elecpaoao.workers.dev/\uFEFF ",
            model = "\u200Bmodels/gemini-3.5-flash-lite\u00A0",
        )
        assertEquals(
            "https://gemini.elecpaoao.workers.dev/v1beta/models/gemini-3.5-flash-lite:generateContent",
            url(cfProxy),
        )
        val h = AiEndpoints.headers(cfProxy)
        assertEquals("application/json", h["Content-Type"])
        assertEquals("AIzaSy_TEST", h["x-goog-api-key"])

        val queryProxy = cfProxy.copy(proxyAuthStyle = AUTH_STYLE_QUERY)
        assertEquals(
            "https://gemini.elecpaoao.workers.dev/v1beta/models/gemini-3.5-flash-lite:generateContent?key=AIzaSy_TEST",
            url(queryProxy),
        )

        val expressDirty = vtx.copy(vertexAuthMode = VERTEX_AUTH_EXPRESS, vertexApiKey = dirtyKey, model = "gemini-3.8-flash\u200B")
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.8-flash:generateContent?key=AIzaSy_TEST",
            url(expressDirty),
        )

        assertEquals(AiProviderSettings.DEFAULT_MODEL, AiProviderSettings(model = "gemini-2.5-flash").withNormalizedModel().model)
        assertEquals(AiProviderSettings.DEFAULT_MODEL, AiProviderSettings(model = "gemini-2.0-flash-exp").withNormalizedModel().model)
        assertEquals("gemini-3.8-flash", AiProviderSettings(model = "gemini-3.8-flash").withNormalizedModel().model)
        assertFalse(AiProviderSettings(geminiApiKey = "\u200B\uFEFF").isConfigured)
    }
}
