package com.wa2port.core.update

import com.wa2port.core.i18n.Wa2I18n

/**
 * Where to look for releases, from one user-facing string:
 * - empty → the official GitHub Releases API;
 * - ends with `/` → a GitHub proxy prefix (e.g. `https://ghproxy.net/`) prepended to both the API
 *   URL and the asset download URLs;
 * - anything else → the full URL of a GitHub-compatible "latest release" JSON endpoint; asset URLs
 *   from that JSON are used verbatim (so a mock / self-hosted server controls both).
 */
data class UpdateEndpointConfig(val customUpdateUrl: String = "") {
    val trimmed: String get() = customUpdateUrl.trim()

    val isProxyPrefix: Boolean get() = trimmed.endsWith("/")

    /** URL of the "latest release" JSON. */
    fun apiUrl(): String = when {
        trimmed.isEmpty() -> DEFAULT_RELEASE_API_URL
        isProxyPrefix -> trimmed + DEFAULT_RELEASE_API_URL
        else -> trimmed
    }

    /** [assetUrl] routed through the proxy prefix (if any, and not already prefixed). */
    fun downloadUrl(assetUrl: String): String =
        if (isProxyPrefix && !assetUrl.startsWith(trimmed)) trimmed + assetUrl else assetUrl

    /** User-facing problem with [customUpdateUrl], or null when usable (empty = default is fine). */
    fun validationError(): String? {
        val t = trimmed
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Wa2I18n.s.errUrlMustStartHttp
        }
        if (t.any { it.isWhitespace() }) return Wa2I18n.s.errUrlNoSpaces
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return Wa2I18n.s.errUrlMissingHost
        return null
    }

    companion object {
        const val DEFAULT_RELEASE_API_URL = "https://api.github.com/repos/codescv/wa2port/releases/latest"
        const val DEFAULT_RELEASE_HTML_URL = "https://github.com/codescv/wa2port/releases/latest"
    }
}
