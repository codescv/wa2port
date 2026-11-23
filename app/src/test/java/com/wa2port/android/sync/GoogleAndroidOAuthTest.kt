package com.wa2port.android.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GoogleAndroidOAuthTest {

    companion object {
        private const val WA2_ANDROID_CLIENT_ID =
            "304560912778-cnl01d27bu8jrunpdf5rjfuj3blh9v9a.apps.googleusercontent.com"
        private const val WA2_ANDROID_SCHEME =
            "com.googleusercontent.apps.304560912778-cnl01d27bu8jrunpdf5rjfuj3blh9v9a"
        private const val WA2_ANDROID_REDIRECT_URI =
            "com.googleusercontent.apps.304560912778-cnl01d27bu8jrunpdf5rjfuj3blh9v9a:/oauth2redirect"
    }

    @TempDir
    lateinit var tempDir: File

    private class LocalTestHttpServer(
        private val handler: (path: String, method: String, body: String) -> Pair<Int, String>,
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val running = AtomicBoolean(true)
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

        private val worker = thread(isDaemon = true, name = "oauth-test-http") {
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        socket.use { client ->
                            val input = BufferedInputStream(client.getInputStream())
                            val headerBytes = ByteArrayOutputStream()
                            var crlfCount = 0
                            while (true) {
                                val b = input.read()
                                if (b < 0) return@use
                                headerBytes.write(b)
                                crlfCount = when {
                                    b == '\r'.code && (crlfCount == 0 || crlfCount == 2) -> crlfCount + 1
                                    b == '\n'.code && (crlfCount == 1 || crlfCount == 3) -> crlfCount + 1
                                    else -> if (b == '\r'.code) 1 else 0
                                }
                                if (crlfCount == 4) break
                            }
                            val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
                            val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                            val requestParts = lines.firstOrNull()?.split(' ').orEmpty()
                            val method = requestParts.getOrNull(0) ?: "GET"
                            val rawTarget = requestParts.getOrNull(1) ?: "/"
                            val path = rawTarget.substringBefore('?')
                            val contentLength = lines.drop(1)
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                            val bodyBytes = ByteArray(contentLength)
                            var offset = 0
                            while (offset < contentLength) {
                                val n = input.read(bodyBytes, offset, contentLength - offset)
                                if (n <= 0) break
                                offset += n
                            }
                            val reqBody = String(bodyBytes, 0, offset, Charsets.UTF_8)
                            val (status, respBody) = handler(path, method, reqBody)
                            val respBytes = respBody.toByteArray(Charsets.UTF_8)
                            val reason = if (status in 200..299) "OK" else "Error"
                            val respHeader = (
                                "HTTP/1.1 $status $reason\r\n" +
                                    "Content-Type: application/json; charset=utf-8\r\n" +
                                    "Content-Length: ${respBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(Charsets.US_ASCII)
                            val out = client.getOutputStream()
                            out.write(respHeader)
                            out.write(respBytes)
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            runCatching { serverSocket.close() }
            worker.join(1_000L)
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        GoogleAndroidOAuth.parseQueryParams(body)

    private fun startServer(
        handler: (path: String, method: String, body: String) -> Pair<Int, String>,
    ): Pair<LocalTestHttpServer, String> {
        val server = LocalTestHttpServer(handler)
        return server to server.baseUrl
    }

    @Test
    fun derivesRedirectSchemeAndUriFromAndroidClientId() {
        assertEquals(WA2_ANDROID_SCHEME, GoogleAndroidOAuth.deriveRedirectScheme(WA2_ANDROID_CLIENT_ID))
        assertEquals(WA2_ANDROID_REDIRECT_URI, GoogleAndroidOAuth.deriveRedirectUri(WA2_ANDROID_CLIENT_ID))
        assertEquals(
            WA2_ANDROID_REDIRECT_URI,
            GoogleAndroidOAuth.deriveRedirectUri("  $WA2_ANDROID_CLIENT_ID  "),
        )

        assertThrows(IllegalArgumentException::class.java) {
            GoogleAndroidOAuth.deriveRedirectUri("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAndroidOAuth.deriveRedirectUri("not-a-google-client-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAndroidOAuth.deriveRedirectUri(".apps.googleusercontent.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleAndroidOAuth.deriveRedirectUri("bad/prefix.apps.googleusercontent.com")
        }
    }

    @Test
    fun parsesRedirectCallbackSuccessErrorsAndStateValidation() {
        val ok = GoogleAndroidOAuth.parseRedirectCallback(
            redirectUri = "$WA2_ANDROID_REDIRECT_URI?state=st-123&code=4%2F0AbCdEf&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file",
            expectedState = "st-123",
        )
        assertEquals(OAuthCallbackResult.Success("4/0AbCdEf"), ok)

        // Triple-slash empty authority and fragment stripping.
        val tripleSlash = GoogleAndroidOAuth.parseRedirectCallback(
            redirectUri = "$WA2_ANDROID_SCHEME:///oauth2redirect?state=st-123&code=abc_999#ignored",
            expectedState = "st-123",
        )
        assertEquals(OAuthCallbackResult.Success("abc_999"), tripleSlash)

        // State mismatch / missing state.
        assertEquals(
            OAuthCallbackResult.InvalidState("wrong"),
            GoogleAndroidOAuth.parseRedirectCallback(
                "$WA2_ANDROID_REDIRECT_URI?state=wrong&code=abc",
                expectedState = "st-123",
            ),
        )
        assertEquals(
            OAuthCallbackResult.InvalidState(null),
            GoogleAndroidOAuth.parseRedirectCallback(
                "$WA2_ANDROID_REDIRECT_URI?code=abc",
                expectedState = "st-123",
            ),
        )

        // OAuth errors (only accepted when state matches).
        assertEquals(
            OAuthCallbackResult.Error("access_denied", ""),
            GoogleAndroidOAuth.parseRedirectCallback(
                "$WA2_ANDROID_REDIRECT_URI?state=st-123&error=access_denied",
                expectedState = "st-123",
            ),
        )
        assertEquals(
            OAuthCallbackResult.Error("temporarily_unavailable", "Try again later"),
            GoogleAndroidOAuth.parseRedirectCallback(
                "$WA2_ANDROID_REDIRECT_URI?state=st-123&error=temporarily_unavailable&error_description=Try+again+later",
                expectedState = "st-123",
            ),
        )

        // Missing authorization code.
        assertEquals(
            OAuthCallbackResult.MissingCode,
            GoogleAndroidOAuth.parseRedirectCallback(
                "$WA2_ANDROID_REDIRECT_URI?state=st-123",
                expectedState = "st-123",
            ),
        )
    }

    @Test
    fun coordinatorIgnoresWrongSchemeAndConsumesMatchingRedirectOnce() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val deferred = coordinator.beginAuth(
            expectedState = "expected-state",
            expectedScheme = WA2_ANDROID_SCHEME,
        )
        assertTrue(coordinator.hasPendingAuth())

        assertFalse(coordinator.deliverRedirectUri(null))
        assertFalse(coordinator.deliverRedirectUri(""))
        assertFalse(coordinator.deliverRedirectUri("https://example.com/oauth2redirect?state=expected-state&code=1"))
        assertTrue(coordinator.hasPendingAuth())

        assertTrue(
            coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=expected-state&code=ok-code"),
        )
        assertFalse(coordinator.hasPendingAuth())
        assertFalse(
            coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=expected-state&code=duplicate"),
        )
        assertEquals(OAuthCallbackResult.Success("ok-code"), deferred.await())
    }

    @Test
    fun fullPkceSignInExchangesCodeWithoutClientSecretAndStoresCredentials() = runBlocking {
        val capturedTokenForm = AtomicReference<Map<String, String>>(emptyMap())
        val (server, baseUrl) = startServer { path, method, body ->
            when {
                path == "/token" && method == "POST" -> {
                    capturedTokenForm.set(parseForm(body))
                    200 to """{"access_token":"fake-access-test-access","refresh_token":"1//test-refresh","expires_in":3600,"scope":"$DRIVE_SCOPE"}"""
                }
                path == "/about" && method == "GET" -> {
                    200 to """{"user":{"displayName":"Kazusa","emailAddress":"kazusa@example.com"}}"""
                }
                else -> 404 to """{"error":"not_found"}"""
            }
        }
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val secrets = InMemorySyncSecretStore()
            val manager = CloudSyncManager(
                secretStore = secrets,
                isAutoSyncEnabled = { true },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
                clock = SyncClock { 1_000_000L },
                tokenEndpoint = "$baseUrl/token",
                authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                apiBase = baseUrl,
                redirectCoordinator = coordinator,
            )

            val capturedAuthUrl = AtomicReference<String>("")
            val account = manager.signIn { url ->
                capturedAuthUrl.set(url)
                val authParams = GoogleAndroidOAuth.parseQueryParams(url.substringAfter('?'))
                val state = authParams["state"]!!
                assertTrue(coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=$state&code=auth-code-42"))
            }

            // Verify authorization URL parameters.
            val authParams = GoogleAndroidOAuth.parseQueryParams(capturedAuthUrl.get().substringAfter('?'))
            assertEquals(WA2_ANDROID_CLIENT_ID, authParams["client_id"])
            assertEquals(WA2_ANDROID_REDIRECT_URI, authParams["redirect_uri"])
            assertEquals("code", authParams["response_type"])
            assertEquals(DRIVE_SCOPE, authParams["scope"])
            assertEquals("S256", authParams["code_challenge_method"])
            assertEquals("offline", authParams["access_type"])
            assertEquals("consent", authParams["prompt"])
            assertFalse(authParams["state"].isNullOrBlank())
            assertFalse(authParams["code_challenge"].isNullOrBlank())
            assertFalse("client_secret" in authParams)

            // Verify token exchange body: PKCE verifier matches challenge, NO client_secret sent.
            val tokenForm = capturedTokenForm.get()
            assertEquals("auth-code-42", tokenForm["code"])
            assertEquals(WA2_ANDROID_CLIENT_ID, tokenForm["client_id"])
            assertEquals(WA2_ANDROID_REDIRECT_URI, tokenForm["redirect_uri"])
            assertEquals("authorization_code", tokenForm["grant_type"])
            assertFalse("client_secret" in tokenForm)
            val verifier = tokenForm["code_verifier"]
            assertNotNull(verifier)
            assertEquals(authParams["code_challenge"], GoogleAndroidOAuth.computeCodeChallengeS256(verifier!!))

            // Verify persisted credentials & UI state.
            assertEquals("kazusa@example.com", account.email)
            assertEquals("Kazusa", account.displayName)
            val saved = secrets.load()
            assertEquals(WA2_ANDROID_CLIENT_ID, saved.clientId)
            assertEquals("", saved.clientSecret)
            assertEquals("fake-access-test-access", saved.accessToken)
            assertEquals("1//test-refresh", saved.refreshToken)
            assertEquals(1_000_000L + 3_600_000L, saved.expiresAtMs)
            assertTrue(manager.uiState.value.isSignedIn)
            assertFalse(manager.uiState.value.isSigningIn)
            assertNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun tokenRefreshOmitsClientSecretWhenBlankAndHandlesInvalidGrant() {
        val capturedRefreshForms = mutableListOf<Map<String, String>>()
        val nextResponse = AtomicReference(200 to """{"access_token":"fake-access-fresh","expires_in":1800}""")
        val (server, baseUrl) = startServer { _, _, body ->
            capturedRefreshForms.add(parseForm(body))
            nextResponse.get()
        }
        try {
            var now = 5_000_000L
            var refreshedAccess = ""
            val provider = OAuthTokenProvider(
                clientId = WA2_ANDROID_CLIENT_ID,
                clientSecret = "",
                initialAccessToken = "fake-access-stale",
                initialRefreshToken = "1//refresh-token",
                initialExpiresAtMs = now - 1_000L,
                tokenEndpoint = "$baseUrl/token",
                clock = SyncClock { now },
                onTokensRefreshed = { access, _, _ -> refreshedAccess = access },
            )

            val fresh = provider.accessToken()
            assertEquals("fake-access-fresh", fresh)
            assertEquals("fake-access-fresh", refreshedAccess)
            assertEquals(1, capturedRefreshForms.size)
            val form = capturedRefreshForms.single()
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("1//refresh-token", form["refresh_token"])
            assertEquals(WA2_ANDROID_CLIENT_ID, form["client_id"])
            assertFalse("client_secret" in form)

            // Advance past expiry and simulate invalid_grant -> GDriveAuthException(needsInteraction = true).
            now += 2_000_000L
            nextResponse.set(400 to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
            val authErr = assertThrows(GDriveAuthException::class.java) {
                provider.accessToken()
            }
            assertTrue(authErr.needsInteraction)
        } finally {
            server.close()
        }
    }

    @Test
    fun stateMismatchAndOAuthErrorsFailWithoutCallingTokenEndpoint() = runBlocking {
        val tokenCalls = AtomicInteger(0)
        val (server, baseUrl) = startServer { _, _, _ ->
            tokenCalls.incrementAndGet()
            200 to """{"access_token":"should-not-happen","expires_in":3600}"""
        }
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val manager = CloudSyncManager(
                secretStore = InMemorySyncSecretStore(),
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
                tokenEndpoint = "$baseUrl/token",
                apiBase = baseUrl,
                redirectCoordinator = coordinator,
            )

            // 1. State mismatch.
            val mismatchErr = assertThrows(GDriveAuthException::class.java) {
                runBlocking {
                    manager.signIn {
                        coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=forged&code=stolen")
                    }
                }
            }
            assertTrue(mismatchErr.needsInteraction)
            assertTrue(mismatchErr.message!!.contains("state", ignoreCase = true))
            assertFalse(manager.uiState.value.isSigningIn)
            assertNotNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())

            // 2. OAuth access_denied error.
            val deniedErr = assertThrows(GDriveAuthException::class.java) {
                runBlocking {
                    manager.signIn { url ->
                        val state = GoogleAndroidOAuth.parseQueryParams(url.substringAfter('?'))["state"]!!
                        coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=$state&error=access_denied")
                    }
                }
            }
            assertEquals("Google sign-in was denied", deniedErr.message)
            assertFalse(manager.uiState.value.isSigningIn)
            assertFalse(coordinator.hasPendingAuth())

            // 3. OAuth custom error + description.
            val serverErr = assertThrows(GDriveAuthException::class.java) {
                runBlocking {
                    manager.signIn { url ->
                        val state = GoogleAndroidOAuth.parseQueryParams(url.substringAfter('?'))["state"]!!
                        coordinator.deliverRedirectUri(
                            "$WA2_ANDROID_REDIRECT_URI?state=$state&error=server_error&error_description=Backend+down",
                        )
                    }
                }
            }
            assertTrue(serverErr.message!!.contains("server_error"))
            assertTrue(serverErr.message!!.contains("Backend down"))
            assertEquals(0, tokenCalls.get())
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun hostResumeCancelsAbandonedSignInButDoesNotCancelAfterRedirectDelivered() = runBlocking {
        val tokenEntered = CompletableDeferred<Unit>()
        val releaseToken = CompletableDeferred<Unit>()
        val (server, baseUrl) = startServer { path, _, _ ->
            when (path) {
                "/token" -> {
                    tokenEntered.complete(Unit)
                    runBlocking { releaseToken.await() }
                    200 to """{"access_token":"fake-access-ok","refresh_token":"1//ok","expires_in":3600}"""
                }
                "/about" -> 200 to """{"user":{"displayName":"Setsuna","emailAddress":"setsuna@example.com"}}"""
                else -> 404 to "{}"
            }
        }
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val manager = CloudSyncManager(
                secretStore = InMemorySyncSecretStore(),
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
                tokenEndpoint = "$baseUrl/token",
                apiBase = baseUrl,
                redirectCoordinator = coordinator,
            )

            // Part A: User returns to app without completing browser sign-in -> cancelled cleanly without error.
            val browserOpened = CompletableDeferred<Unit>()
            val abandonedJob = scope.async {
                manager.signIn {
                    browserOpened.complete(Unit)
                }
            }
            browserOpened.await()
            manager.onHostResume()
            assertThrows(CancellationIOException::class.java) {
                runBlocking { abandonedJob.await() }
            }
            assertFalse(manager.uiState.value.isSigningIn)
            assertNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())

            // Part A2: Explicit cancelSignIn() button while awaiting redirect -> cancelled cleanly without error.
            val cancelOpened = CompletableDeferred<Unit>()
            val cancelledJob = scope.async {
                manager.signIn {
                    cancelOpened.complete(Unit)
                }
            }
            cancelOpened.await()
            manager.cancelSignIn()
            assertThrows(CancellationIOException::class.java) {
                runBlocking { cancelledJob.await() }
            }
            assertFalse(manager.uiState.value.isSigningIn)
            assertNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())

            // Part B: Redirect arrives, token exchange begins, then MainActivity.onResume() fires -> MUST NOT cancel!
            val signInJob = scope.async {
                manager.signIn { url ->
                    val state = GoogleAndroidOAuth.parseQueryParams(url.substringAfter('?'))["state"]!!
                    coordinator.deliverRedirectUri("$WA2_ANDROID_REDIRECT_URI?state=$state&code=valid-code")
                }
            }
            tokenEntered.await()
            manager.onHostResume()
            releaseToken.complete(Unit)

            val account = signInJob.await()
            assertEquals("setsuna@example.com", account.email)
            assertTrue(manager.uiState.value.isSignedIn)
        } finally {
            scope.cancel()
            server.close()
        }
    }

    @Test
    fun timeoutThrowsSocketTimeoutAndCleansUpCoordinator() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val manager = CloudSyncManager(
                secretStore = InMemorySyncSecretStore(),
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
                redirectCoordinator = coordinator,
            )

            assertThrows(SocketTimeoutException::class.java) {
                runBlocking {
                    manager.signIn(timeoutMs = 80L) {
                        // Browser opened, user never completes sign-in.
                    }
                }
            }
            assertFalse(manager.uiState.value.isSigningIn)
            assertNotNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun migratesLegacyDesktopClientIdWhenSignedOutAndOnSignOut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            // Case 1: Unsigned-in user upgrading from v1.4.16 Desktop OAuth client.
            val signedOutStore = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                    clientSecret = "GOCSPX-legacy-secret",
                ),
            )
            val manager1 = CloudSyncManager(
                secretStore = signedOutStore,
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
            )
            assertEquals(WA2_ANDROID_CLIENT_ID, manager1.uiState.value.clientId)
            assertEquals(WA2_ANDROID_CLIENT_ID, signedOutStore.load().clientId)
            assertEquals("", signedOutStore.load().clientSecret)

            // Case 2: Signed-in legacy user keeps existing tokens until signOut(), then migrates.
            val signedInStore = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                    clientSecret = "GOCSPX-legacy-secret",
                    accessToken = "fake-access-legacy",
                    refreshToken = "1//legacy",
                    expiresAtMs = 9_999_999_999_999L,
                    accountEmail = "legacy@example.com",
                ),
            )
            val manager2 = CloudSyncManager(
                secretStore = signedInStore,
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
                revokeEndpoint = "http://127.0.0.1:1/unused",
            )
            assertTrue(manager2.uiState.value.isSignedIn)
            assertEquals(CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID, signedInStore.load().clientId)

            manager2.signOut()
            assertFalse(manager2.uiState.value.isSignedIn)
            assertEquals(WA2_ANDROID_CLIENT_ID, manager2.uiState.value.clientId)
            assertEquals(WA2_ANDROID_CLIENT_ID, signedInStore.load().clientId)
            assertEquals("", signedInStore.load().clientSecret)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun saveOAuthConfigPreservesSameSignedInClientAndSignsOutWhenClientChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val baseline = File(tempDir, "sync_state.json").apply { writeText("{}") }
            val store = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                    clientSecret = "GOCSPX-legacy-secret",
                    accessToken = "fake-access-legacy",
                    refreshToken = "1//legacy",
                    expiresAtMs = 9_999_999_999_999L,
                    accountEmail = "legacy@example.com",
                ),
            )
            val manager = CloudSyncManager(
                secretStore = store,
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = WA2_ANDROID_CLIENT_ID,
            )

            // Saving unchanged client ID keeps tokens and Desktop clientSecret intact.
            manager.saveOAuthConfig(CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID)
            assertTrue(manager.uiState.value.isSignedIn)
            assertEquals("GOCSPX-legacy-secret", store.load().clientSecret)
            assertEquals("1//legacy", store.load().refreshToken)
            assertTrue(baseline.isFile)

            // Resetting to default Android client ID while signed in signs out locally and clears baseline.
            manager.saveOAuthConfig("")
            assertFalse(manager.uiState.value.isSignedIn)
            assertEquals(WA2_ANDROID_CLIENT_ID, manager.uiState.value.clientId)
            assertEquals("", manager.uiState.value.accountEmail)
            assertEquals(WA2_ANDROID_CLIENT_ID, store.load().clientId)
            assertEquals("", store.load().clientSecret)
            assertEquals("", store.load().refreshToken)
            assertEquals("", store.load().accessToken)
            assertFalse(baseline.exists())
        } finally {
            scope.cancel()
        }
    }
}
