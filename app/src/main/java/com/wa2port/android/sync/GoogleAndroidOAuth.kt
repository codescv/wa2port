package com.wa2port.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val scope: String,
)

class CancellationIOException(
    message: String = "Google sign-in was cancelled",
    cause: Throwable? = null,
) : IOException(message, cause)

sealed interface OAuthCallbackResult {
    data class Success(val code: String) : OAuthCallbackResult
    data class Error(val error: String, val errorDescription: String = "") : OAuthCallbackResult
    data class InvalidState(val receivedState: String?) : OAuthCallbackResult
    data object MissingCode : OAuthCallbackResult
}

/**
 * In-process handoff coordinator bridging [OAuthRedirectActivity] (custom URI scheme callback)
 * and the active [GoogleAndroidOAuth.authorize] coroutine.
 */
class OAuthRedirectCoordinator {
    private data class PendingAuth(
        val expectedState: String,
        val expectedScheme: String?,
        val deferred: CompletableDeferred<OAuthCallbackResult>,
    )

    private val pending = AtomicReference<PendingAuth?>(null)

    /**
     * Registers a pending authorization session awaiting a redirect callback.
     * Any previous unfinished session is cancelled so stale waiters cannot leak or hang.
     */
    fun beginAuth(
        expectedState: String,
        expectedScheme: String? = null,
    ): CompletableDeferred<OAuthCallbackResult> {
        require(expectedState.isNotBlank()) { "OAuth state must not be blank" }
        val deferred = CompletableDeferred<OAuthCallbackResult>()
        val next = PendingAuth(
            expectedState = expectedState,
            expectedScheme = expectedScheme?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.US),
            deferred = deferred,
        )
        val prev = pending.getAndSet(next)
        prev?.deferred?.cancel(CancellationException("Superseded by a new Google sign-in"))
        return deferred
    }

    /** True while an authorization flow is actively awaiting a redirect callback. */
    fun hasPendingAuth(): Boolean = pending.get()?.deferred?.isActive == true

    /**
     * Delivers a custom-scheme redirect URI string (from [OAuthRedirectActivity]) to the active
     * pending authorization session, if any.
     *
     * @return `true` if a pending flow matched and consumed the redirect, `false` otherwise.
     */
    fun deliverRedirectUri(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        while (true) {
            val current = pending.get() ?: return false
            if (!current.deferred.isActive) {
                pending.compareAndSet(current, null)
                continue
            }
            if (current.expectedScheme != null) {
                val incomingScheme = extractScheme(uriString)?.lowercase(Locale.US)
                if (incomingScheme != current.expectedScheme) {
                    return false
                }
            }
            if (pending.compareAndSet(current, null)) {
                val parsed = GoogleAndroidOAuth.parseRedirectCallback(
                    redirectUri = uriString,
                    expectedState = current.expectedState,
                )
                return current.deferred.complete(parsed)
            }
        }
    }

    /** Cancels any pending authorization flow waiting for a redirect callback. */
    fun cancelPending(reason: String = "Google sign-in was cancelled"): Boolean {
        val current = pending.getAndSet(null) ?: return false
        current.deferred.cancel(CancellationException(reason))
        return true
    }

    /** Clears [deferred] if it is still the registered active entry. */
    fun clearIfCurrent(deferred: CompletableDeferred<OAuthCallbackResult>) {
        while (true) {
            val current = pending.get() ?: return
            if (current.deferred !== deferred) return
            if (pending.compareAndSet(current, null)) return
        }
    }

    private fun extractScheme(uriString: String): String? {
        val colonIdx = uriString.indexOf(':')
        if (colonIdx <= 0) return null
        return uriString.substring(0, colonIdx).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        val SHARED = OAuthRedirectCoordinator()
    }
}

/**
 * Pure-JVM Android OAuth 2.0 public-client flow with PKCE (`S256`) and custom URI scheme redirect
 * (`com.googleusercontent.apps.<client-prefix>:/oauth2redirect`).
 *
 * - Uses single scope `https://www.googleapis.com/auth/drive.file`.
 * - Sends `access_type=offline` and `prompt=consent` so Google returns a `refresh_token`.
 * - Strictly verifies `state` on callback in constant time.
 * - Public Android OAuth client: never sends `client_secret`.
 */
class GoogleAndroidOAuth(
    private val clientId: String,
    private val openBrowser: (String) -> Unit,
    private val redirectCoordinator: OAuthRedirectCoordinator = OAuthRedirectCoordinator.SHARED,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val authEndpoint: String = DEFAULT_OAUTH_AUTH_ENDPOINT,
    private val random: SecureRandom = SecureRandom(),
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : Closeable {

    companion object {
        const val GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"
        const val REVERSE_DNS_SCHEME_PREFIX = "com.googleusercontent.apps."
        const val REDIRECT_PATH = "/oauth2redirect"
        private val VALID_SCHEME_PREFIX_REGEX = Regex("^[A-Za-z0-9._-]+$")

        fun deriveRedirectScheme(clientId: String): String {
            val trimmed = clientId.trim()
            require(trimmed.isNotEmpty()) { "OAuth Client ID is required" }
            val lower = trimmed.lowercase(Locale.US)
            if (!lower.endsWith(GOOGLE_CLIENT_ID_SUFFIX) || trimmed.length <= GOOGLE_CLIENT_ID_SUFFIX.length) {
                throw IllegalArgumentException(
                    "Android OAuth Client ID must end with $GOOGLE_CLIENT_ID_SUFFIX",
                )
            }
            val prefix = trimmed.substring(0, trimmed.length - GOOGLE_CLIENT_ID_SUFFIX.length)
            require(prefix.isNotEmpty() && VALID_SCHEME_PREFIX_REGEX.matches(prefix)) {
                "Invalid Google OAuth Client ID format"
            }
            return "$REVERSE_DNS_SCHEME_PREFIX$prefix"
        }

        fun deriveRedirectUri(clientId: String): String =
            "${deriveRedirectScheme(clientId)}:$REDIRECT_PATH"

        fun generateCodeVerifier(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun computeCodeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun generateState(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun parseRedirectCallback(
            redirectUri: String,
            expectedState: String,
        ): OAuthCallbackResult {
            val withoutFragment = redirectUri.substringBefore('#')
            val qIdx = withoutFragment.indexOf('?')
            val query = if (qIdx >= 0) withoutFragment.substring(qIdx + 1) else ""
            val params = parseQueryParams(query)

            val gotState = params["state"]
            if (gotState.isNullOrEmpty() || !constantTimeEquals(gotState, expectedState)) {
                return OAuthCallbackResult.InvalidState(gotState)
            }

            val error = params["error"]?.trim()
            if (!error.isNullOrEmpty()) {
                val desc = params["error_description"]?.trim().orEmpty()
                return OAuthCallbackResult.Error(error = error, errorDescription = desc)
            }

            val code = params["code"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return OAuthCallbackResult.MissingCode
            return OAuthCallbackResult.Success(code)
        }

        internal fun parseQueryParams(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
                val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                if (key.isNotEmpty() && key !in result) {
                    result[key] = value
                }
            }
            return result
        }

        internal fun parseTokenResponse(rawJson: String, nowMs: Long): OAuthTokens {
            val obj = runCatching { syncJson.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                ?: throw IOException("OAuth token response is not valid JSON")
            val accessToken = obj.str("access_token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("OAuth token response missing access_token")
            val refreshToken = obj.str("refresh_token") ?: ""
            val expiresIn = obj.long("expires_in", 3600L).coerceAtLeast(60L)
            val expiresAtMs = nowMs + expiresIn * 1000L
            val scope = obj.str("scope") ?: DRIVE_SCOPE
            return OAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = expiresAtMs,
                scope = scope,
            )
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private val cancelled = AtomicBoolean(false)
    private val browserLaunched = AtomicBoolean(false)

    @Volatile
    private var pendingDeferred: CompletableDeferred<OAuthCallbackResult>? = null

    @Volatile
    var authUrl: String? = null
        private set

    @Volatile
    var redirectUri: String? = null
        private set

    /** True after the browser was launched and while still waiting for the redirect callback. */
    fun isAwaitingRedirect(): Boolean =
        browserLaunched.get() && (pendingDeferred?.isActive == true)

    suspend fun authorize(timeoutMs: Long = 5 * 60_000L): OAuthTokens {
        val trimmedClientId = clientId.trim()
        require(trimmedClientId.isNotEmpty()) { "OAuth Client ID is required" }
        if (cancelled.get()) throw CancellationIOException()

        val scheme = deriveRedirectScheme(trimmedClientId)
        val builtRedirectUri = "$scheme:$REDIRECT_PATH"
        redirectUri = builtRedirectUri

        val verifier = generateCodeVerifier(random)
        val challenge = computeCodeChallengeS256(verifier)
        val expectedState = generateState(random)
        val builtAuthUrl = buildAuthUrl(builtRedirectUri, challenge, expectedState)
        authUrl = builtAuthUrl

        val deferred = redirectCoordinator.beginAuth(
            expectedState = expectedState,
            expectedScheme = scheme,
        )
        pendingDeferred = deferred

        val callbackResult: OAuthCallbackResult = try {
            if (cancelled.get()) throw CancellationIOException()
            browserLaunched.set(true)
            openBrowser(builtAuthUrl)
            withTimeout(timeoutMs.coerceAtLeast(1L)) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out waiting for Google sign-in in browser")
        } catch (e: CancellationException) {
            if (cancelled.get()) throw CancellationIOException(cause = e)
            if (!currentCoroutineContext().isActive) throw e
            throw CancellationIOException(cause = e)
        } finally {
            browserLaunched.set(false)
            redirectCoordinator.clearIfCurrent(deferred)
            if (pendingDeferred === deferred) pendingDeferred = null
        }

        val authCode = when (callbackResult) {
            is OAuthCallbackResult.Success -> callbackResult.code
            is OAuthCallbackResult.InvalidState -> throw GDriveAuthException(
                message = "Google sign-in state mismatch — please try again",
                needsInteraction = true,
            )
            is OAuthCallbackResult.MissingCode -> throw GDriveAuthException(
                message = "Google sign-in did not return an authorization code",
                needsInteraction = true,
            )
            is OAuthCallbackResult.Error -> {
                val msg = if (callbackResult.error == "access_denied") {
                    "Google sign-in was denied"
                } else {
                    val detail = listOf(callbackResult.error, callbackResult.errorDescription)
                        .filter { it.isNotBlank() }
                        .joinToString(": ")
                    "Google sign-in failed (${redactSecrets(detail)})"
                }
                throw GDriveAuthException(message = msg, needsInteraction = true)
            }
        }

        if (cancelled.get()) throw CancellationIOException()
        val tokens = withContext(Dispatchers.IO) {
            exchangeAuthorizationCode(
                code = authCode,
                redirectUri = builtRedirectUri,
                codeVerifier = verifier,
            )
        }
        if (cancelled.get()) throw CancellationIOException()
        return tokens
    }

    fun cancel() {
        cancelled.set(true)
        browserLaunched.set(false)
        val deferred = pendingDeferred
        if (deferred != null) {
            redirectCoordinator.clearIfCurrent(deferred)
            deferred.cancel(CancellationException("Google sign-in was cancelled"))
        }
    }

    override fun close() {
        cancel()
    }

    internal fun buildAuthUrl(redirectUri: String, challenge: String, state: String): String {
        val base = authEndpoint.trimEnd('?')
        val sep = if ('?' in base) '&' else '?'
        return buildString {
            append(base).append(sep)
            append("client_id=").append(urlEncode(clientId.trim()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&response_type=code")
            append("&scope=").append(urlEncode(DRIVE_SCOPE))
            append("&code_challenge=").append(urlEncode(challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(urlEncode(state))
            append("&access_type=offline")
            append("&prompt=consent")
        }
    }

    internal fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OAuthTokens {
        val form = buildString {
            append("code=").append(urlEncode(code))
            append("&client_id=").append(urlEncode(clientId.trim()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(urlEncode(codeVerifier))
        }
        val bodyBytes = form.toByteArray(Charsets.UTF_8)
        val conn = (URL(tokenEndpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        try {
            conn.outputStream.use { it.write(bodyBytes) }
            val status = conn.responseCode
            val raw = readHttpBody(conn, status)
            if (status !in 200..299) {
                val parsed = runCatching { syncJson.parseToJsonElement(raw).jsonObject }.getOrNull()
                val errCode = parsed?.str("error") ?: ""
                val errDesc = parsed?.str("error_description") ?: ""
                val detail = listOf(errCode, errDesc).filter { it.isNotBlank() }.joinToString(": ")
                    .ifBlank { "HTTP $status" }
                throw GDriveAuthException(
                    message = "OAuth token exchange failed (${redactSecrets(detail)})",
                    needsInteraction = true,
                )
            }
            return parseTokenResponse(raw, clock.nowMs())
        } finally {
            conn.disconnect()
        }
    }
}

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.long(key: String, default: Long = 0L): Long =
    this[key]?.jsonPrimitive?.longOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: default
