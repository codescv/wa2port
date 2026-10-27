package com.wa2port.core.update

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
            return "地址必须以 http:// 或 https:// 开头 URL must start with http(s)://"
        }
        if (t.any { it.isWhitespace() }) return "地址不能包含空格 URL must not contain spaces"
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty()) return "地址缺少主机名 URL has no host"
        return null
    }

    companion object {
        const val DEFAULT_RELEASE_API_URL = "https://api.github.com/repos/codescv/wa2port/releases/latest"
        const val DEFAULT_RELEASE_HTML_URL = "https://github.com/codescv/wa2port/releases/latest"
    }
}
