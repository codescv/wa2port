package com.wa2port.android.sync

import com.wa2port.android.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Adversarial edge cases for the Android OAuth (PKCE + custom URI scheme) migration. */
class GoogleAndroidOAuthAdversarialTest {

    @TempDir
    lateinit var tempDir: File

    private val androidClientId =
        "304560912778-cnl01d27bu8jrunpdf5rjfuj3blh9v9a.apps.googleusercontent.com"

    private fun legacySignedInCreds() = StoredOAuthCredentials(
        clientId = CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID,
        clientSecret = "GOCSPX-legacy-secret",
        accessToken = "fake-access-legacy",
        refreshToken = "1//legacy",
        expiresAtMs = 9_999_999_999_999L,
        accountEmail = "legacy@example.com",
    )

    /** The manifest intent-filter scheme must be derived from the exact default Client ID baked into BuildConfig. */
    @Test
    fun manifestRedirectSchemeMatchesBuildConfigDefaultClientId() {
        assertEquals(androidClientId, BuildConfig.DEFAULT_GDRIVE_CLIENT_ID)
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "Run from app module dir: ${manifest.absolutePath}")
        val xml = manifest.readText()
        val activityBlock = Regex(
            """<activity[^>]*android:name="\.sync\.OAuthRedirectActivity"[\s\S]*?</activity>""",
        ).find(xml)?.value
        assertNotNull(activityBlock, "OAuthRedirectActivity must be declared")
        val block = activityBlock!!
        val expectedScheme = GoogleAndroidOAuth.deriveRedirectScheme(BuildConfig.DEFAULT_GDRIVE_CLIENT_ID)
        assertTrue("android:scheme=\"$expectedScheme\"" in block, "scheme mismatch:\n$block")
        assertTrue("android:exported=\"true\"" in block)
        assertTrue("android.intent.action.VIEW" in block)
        assertTrue("android.intent.category.DEFAULT" in block)
        assertTrue("android.intent.category.BROWSABLE" in block)
        assertFalse("android:host" in block, "android:host would break empty-authority scheme:/oauth2redirect")
        assertFalse("android:path" in block)
    }

    /** Signed-in v1.4.16 users must keep refreshing with their Desktop client + stored secret until they sign out. */
    @Test
    fun legacySignedInUserKeepsDesktopSecretForRefreshAfterUpgrade() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = InMemorySyncSecretStore(legacySignedInCreds())
            val manager = CloudSyncManager(
                secretStore = store,
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = androidClientId,
            )
            assertTrue(manager.uiState.value.isSignedIn)
            val saved = store.load()
            assertEquals(CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID, saved.clientId)
            assertEquals("GOCSPX-legacy-secret", saved.clientSecret)
            assertEquals("1//legacy", saved.refreshToken)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Signed-in legacy user opens Advanced and taps "Save" (draft == legacy ID shown in field):
     * the Desktop secret must not be stripped while the Desktop refresh token is still in use,
     * otherwise every refresh fails with `invalid_request: client_secret is missing` (not needsInteraction).
     */
    @Test
    fun savingOAuthConfigWhileLegacySignedInDoesNotOrphanDesktopRefreshToken() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = InMemorySyncSecretStore(legacySignedInCreds())
            val manager = CloudSyncManager(
                secretStore = store,
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = androidClientId,
            )
            manager.saveOAuthConfig(manager.uiState.value.clientId)
            val after = store.load()
            val consistent =
                (after.clientId == CloudSyncManager.LEGACY_DESKTOP_CLIENT_ID && after.clientSecret.isNotBlank()) ||
                    !after.isSignedIn
            assertTrue(
                consistent,
                "Desktop refresh token kept but secret stripped: clientId=${after.clientId} " +
                    "secretBlank=${after.clientSecret.isBlank()} signedIn=${after.isSignedIn}",
            )
        } finally {
            scope.cancel()
        }
    }

    /** A stale callback from an earlier, cancelled attempt must never complete a newer attempt. */
    @Test
    fun staleCallbackFromSupersededAttemptCannotCompleteNewAttempt() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scheme = GoogleAndroidOAuth.deriveRedirectScheme(androidClientId)
        val redirect = GoogleAndroidOAuth.deriveRedirectUri(androidClientId)
        val first = coordinator.beginAuth("state-one", scheme)
        val second = coordinator.beginAuth("state-two", scheme)
        assertTrue(first.isCancelled)
        coordinator.deliverRedirectUri("$redirect?state=state-one&code=stale")
        val result = second.await()
        assertFalse(result is OAuthCallbackResult.Success, "stale code accepted: $result")
    }

    /** onHostResume() with no browser launched yet / no active sign-in must be a no-op. */
    @Test
    fun hostResumeBeforeBrowserLaunchOrWithoutSignInIsNoOp() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val manager = CloudSyncManager(
                secretStore = InMemorySyncSecretStore(),
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = androidClientId,
                redirectCoordinator = coordinator,
            )
            manager.onHostResume()
            assertFalse(manager.uiState.value.isSigningIn)

            // Resume fires *inside* openBrowser (before startActivity pauses MainActivity): treat as
            // abandoned only after launch; ensure we never hang either way.
            val opened = CompletableDeferred<Unit>()
            val job = scope.async {
                manager.signIn(timeoutMs = 2_000L) {
                    opened.complete(Unit)
                }
            }
            opened.await()
            manager.onHostResume()
            assertThrows(CancellationIOException::class.java) { runBlocking { job.await() } }
            assertFalse(manager.uiState.value.isSigningIn)
            assertFalse(coordinator.hasPendingAuth())
        } finally {
            scope.cancel()
        }
    }

    /** ActivityNotFoundException-style failures from openBrowser must reset UI and clear the coordinator. */
    @Test
    fun openBrowserFailureCleansUp() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val manager = CloudSyncManager(
                secretStore = InMemorySyncSecretStore(),
                isAutoSyncEnabled = { false },
                filesDir = tempDir,
                scope = scope,
                defaultClientId = androidClientId,
                redirectCoordinator = coordinator,
            )
            assertThrows(IllegalStateException::class.java) {
                runBlocking { manager.signIn { throw IllegalStateException("no browser") } }
            }
            assertFalse(manager.uiState.value.isSigningIn)
            assertNotNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasPendingAuth())
        } finally {
            scope.cancel()
        }
    }
}
