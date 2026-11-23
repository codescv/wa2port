package com.wa2port.android.sync

import com.wa2port.core.sync.CloudSyncSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Coordinates Google Drive OAuth sign-in, token persistence, single-flight coalesced syncing,
 * debounced auto-sync triggers, and interactive two-choice conflict resolution.
 */
class CloudSyncManager(
    private val secretStore: SyncSecretStore,
    private val isAutoSyncEnabled: () -> Boolean,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val defaultClientId: String = "",
    private val onLocalDataUpdated: (systemChanged: Boolean, savesChanged: Boolean) -> Unit = { _, _ -> },
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val authEndpoint: String = DEFAULT_OAUTH_AUTH_ENDPOINT,
    private val revokeEndpoint: String = DEFAULT_OAUTH_REVOKE_ENDPOINT,
    private val apiBase: String = DEFAULT_DRIVE_API_BASE,
    private val uploadBase: String = DEFAULT_DRIVE_UPLOAD_BASE,
    private val redirectCoordinator: OAuthRedirectCoordinator = OAuthRedirectCoordinator.SHARED,
    private val driveApiFactory: (DriveTokenProvider) -> DriveApi = { provider ->
        HttpGoogleDriveClient(
            tokenProvider = provider,
            apiBase = apiBase,
            uploadBase = uploadBase,
        )
    },
) {
    companion object {
        const val AUTO_SYNC_DEBOUNCE_MS = 15_000L
        private const val MAX_FOLLOWUP_PASSES = 3
        internal const val LEGACY_DESKTOP_CLIENT_ID =
            "304560912778-r98sabc8lhet5jlrt0tpgrpqkdigc0vl.apps.googleusercontent.com"
    }

    private val baselineFile: File get() = File(filesDir, CloudSyncSchema.SYNC_STATE_FILE_NAME)

    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    /** Protects [activeSync] creation/coalescing. */
    private val flightMutex = Mutex()
    /** Serializes sync execution and conflict resolution so baseline writes never interleave. */
    private val syncExecutionMutex = Mutex()
    @Volatile
    private var activeSync: Deferred<SyncResult>? = null

    /** Set when a local save/edit arrives while a sync is already in flight so a follow-up pass runs. */
    private val dirtyWhileSyncing = AtomicBoolean(false)

    @Volatile
    private var cachedTokenProvider: OAuthTokenProvider? = null

    @Volatile
    private var lastAutoSyncAtMs: Long = 0L

    /** Monotonic generation counter incremented on sign-in and sign-out to fence stale callbacks/writes. */
    private val authGeneration = AtomicLong(0L)

    @Volatile
    private var activeOAuth: GoogleAndroidOAuth? = null

    private fun initialUiState(): SyncUiState {
        var creds = secretStore.load()
        val seededId = defaultClientId.trim()
        val shouldMigrateLegacy = !creds.isSignedIn &&
            creds.clientId.trim() == LEGACY_DESKTOP_CLIENT_ID &&
            seededId.isNotBlank() &&
            seededId != LEGACY_DESKTOP_CLIENT_ID
        val needId = creds.clientId.isBlank() && seededId.isNotBlank()
        if (shouldMigrateLegacy || needId) {
            val updated = creds.copy(
                clientId = seededId,
                clientSecret = if (shouldMigrateLegacy) "" else creds.clientSecret,
            )
            runCatching { secretStore.save(updated) }
            creds = updated
        }
        return SyncUiState(
            isSignedIn = creds.isSignedIn,
            accountEmail = creds.accountEmail,
            accountName = creds.accountName,
            clientId = creds.clientId.ifBlank { seededId },
        )
    }

    fun saveOAuthConfig(clientId: String) {
        val trimmedId = clientId.trim().ifBlank { defaultClientId.trim() }
        runCatching {
            val current = secretStore.load()
            if (current.clientId == trimmedId) {
                if (current.isSignedIn || current.clientSecret.isEmpty()) return
                secretStore.save(current.copy(clientSecret = ""))
                cachedTokenProvider = null
                return
            }

            if (current.isSignedIn) {
                // Changing Client ID while signed in invalidates the existing token pair:
                // reset local auth/sync state so tokens minted for the old client are never
                // paired with the new client ID.
                authGeneration.incrementAndGet()
                dirtyWhileSyncing.set(false)
                cancelSignIn()
                activeSync?.cancel()
                activeSync = null
                cachedTokenProvider = null
                secretStore.save(StoredOAuthCredentials(clientId = trimmedId, clientSecret = ""))
                runCatching { baselineFile.delete() }
                _uiState.update {
                    it.copy(
                        isSignedIn = false,
                        accountEmail = "",
                        accountName = "",
                        clientId = trimmedId,
                        isSyncing = false,
                        isResolvingConflict = false,
                        progressMessage = null,
                        lastResult = null,
                        lastSyncedAtMs = 0L,
                        lastError = null,
                        pendingConflicts = emptyList(),
                    )
                }
                return
            }

            secretStore.save(
                current.copy(
                    clientId = trimmedId,
                    clientSecret = "",
                ),
            )
            cachedTokenProvider = null
            _uiState.update {
                it.copy(
                    clientId = trimmedId,
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(lastError = "保存 OAuth 配置失败: ${redactSecrets(err.message ?: "Keystore error")}")
            }
        }
    }

    /**
     * Launches interactive Google sign-in followed by an initial sync inside [scope] (`viewModelScope`),
     * so transient UI disposal / Activity recreation while the browser is open does not cancel sign-in.
     */
    fun startSignInAndSync(
        timeoutMs: Long = 5 * 60_000L,
        openBrowser: (String) -> Unit,
    ): Job = scope.launch {
        val ok = runCatching {
            signIn(timeoutMs = timeoutMs, openBrowser = openBrowser)
        }.isSuccess
        if (ok) {
            syncNow()
        }
    }

    suspend fun signIn(
        timeoutMs: Long = 5 * 60_000L,
        openBrowser: (String) -> Unit,
    ): SyncAccountInfo {
        val creds = secretStore.load()
        val clientId = creds.clientId.trim().ifBlank { defaultClientId.trim() }
        if (clientId.isEmpty()) {
            val msg = "请先配置 Google Android OAuth Client ID"
            _uiState.update { it.copy(lastError = msg) }
            throw IllegalStateException(msg)
        }

        activeOAuth?.cancel()
        _uiState.update { it.copy(isSigningIn = true, lastError = null) }
        val oauth = GoogleAndroidOAuth(
            clientId = clientId,
            openBrowser = openBrowser,
            redirectCoordinator = redirectCoordinator,
            tokenEndpoint = tokenEndpoint,
            authEndpoint = authEndpoint,
            clock = clock,
        )
        activeOAuth = oauth
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                oauth.cancel()
            }
        }
        return try {
            val tokens = oauth.authorize(timeoutMs = timeoutMs)
            val userInfo = withContext(Dispatchers.IO) {
                fetchDriveUserInfo(tokens.accessToken, apiBase = apiBase)
            }
            val updated = StoredOAuthCredentials(
                clientId = clientId,
                clientSecret = "",
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.ifBlank {
                    if (creds.clientId == clientId) creds.refreshToken else ""
                },
                expiresAtMs = tokens.expiresAtMs,
                accountEmail = userInfo.email,
                accountName = userInfo.displayName,
            )
            authGeneration.incrementAndGet()
            secretStore.save(updated)
            cachedTokenProvider = null
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    accountEmail = updated.accountEmail,
                    accountName = updated.accountName,
                    clientId = updated.clientId,
                    isSigningIn = false,
                    lastError = null,
                )
            }
            userInfo
        } catch (e: CancellationIOException) {
            _uiState.update { it.copy(isSigningIn = false) }
            throw e
        } catch (e: CancellationException) {
            _uiState.update { it.copy(isSigningIn = false) }
            throw e
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: "Google 登录失败")
            _uiState.update { it.copy(isSigningIn = false, lastError = msg) }
            throw e
        } finally {
            cancelHandle.dispose()
            if (activeOAuth === oauth) activeOAuth = null
            runCatching { oauth.close() }
        }
    }

    /**
     * Called when the host Activity resumes. If an interactive sign-in is still awaiting a browser
     * redirect (i.e. [OAuthRedirectActivity] did not deliver one), the user returned to the app
     * without completing sign-in, so cancel cleanly instead of hanging.
     */
    fun onHostResume() {
        val current = activeOAuth ?: return
        if (current.isAwaitingRedirect()) {
            cancelSignIn()
        }
    }

    fun cancelSignIn() {
        activeOAuth?.cancel()
        activeOAuth = null
        _uiState.update { it.copy(isSigningIn = false) }
    }

    suspend fun signOut() {
        authGeneration.incrementAndGet()
        dirtyWhileSyncing.set(false)
        cancelSignIn()
        val runningSync = flightMutex.withLock {
            val s = activeSync
            activeSync = null
            s
        }
        runningSync?.cancel()
        cachedTokenProvider = null
        val creds = secretStore.load()
        var nextClientId = creds.clientId.trim().ifBlank { defaultClientId.trim() }
        withContext(Dispatchers.IO) {
            val tokenToRevoke = creds.refreshToken.ifBlank { creds.accessToken }
            if (tokenToRevoke.isNotBlank()) {
                revokeOAuthTokenBestEffort(tokenToRevoke, revokeEndpoint = revokeEndpoint)
            }
            val seededId = defaultClientId.trim()
            val migrateLegacyOnSignOut =
                creds.clientId.trim() == LEGACY_DESKTOP_CLIENT_ID &&
                    seededId.isNotBlank() &&
                    seededId != LEGACY_DESKTOP_CLIENT_ID
            if (migrateLegacyOnSignOut) {
                nextClientId = seededId
                runCatching {
                    secretStore.save(StoredOAuthCredentials(clientId = seededId, clientSecret = ""))
                }
            } else {
                runCatching { secretStore.clearTokens() }
            }
            runCatching { baselineFile.delete() }
        }
        _uiState.update {
            it.copy(
                isSignedIn = false,
                accountEmail = "",
                accountName = "",
                clientId = nextClientId,
                isSyncing = false,
                isResolvingConflict = false,
                progressMessage = null,
                lastResult = null,
                lastSyncedAtMs = 0L,
                lastError = null,
                pendingConflicts = emptyList(),
            )
        }
    }

    /**
     * Triggers a non-blocking background auto-sync if auto-sync is enabled, signed in, and no
     * unresolved conflicts are currently waiting for user input. If a sync is already in flight,
     * marks [dirtyWhileSyncing] so one follow-up pass uploads changes written mid-sync.
     */
    fun triggerAutoSync(reason: String, debounce: Boolean = true) {
        if (!isAutoSyncEnabled()) return
        if (!secretStore.load().isSignedIn) return
        if (_uiState.value.pendingConflicts.isNotEmpty()) return
        if (activeSync?.isActive == true) {
            dirtyWhileSyncing.set(true)
            return
        }
        val now = clock.nowMs()
        if (debounce && now - lastAutoSyncAtMs < AUTO_SYNC_DEBOUNCE_MS) return
        lastAutoSyncAtMs = now
        scope.launch(Dispatchers.IO) {
            runCatching { syncNow() }
        }
    }

    /** Runs a full sync. Overlapping calls coalesce onto the single in-flight sync. */
    suspend fun syncNow(): SyncResult {
        val deferred = flightMutex.withLock {
            val existing = activeSync
            if (existing != null && existing.isActive) {
                dirtyWhileSyncing.set(true)
                existing
            } else {
                lateinit var created: Deferred<SyncResult>
                created = scope.async(Dispatchers.IO) {
                    try {
                        var passes = 0
                        var last: SyncResult
                        do {
                            dirtyWhileSyncing.set(false)
                            last = syncExecutionMutex.withLock { executeSync() }
                            passes++
                        } while (
                            passes < MAX_FOLLOWUP_PASSES &&
                            dirtyWhileSyncing.compareAndSet(true, false) &&
                            last.pendingConflicts.isEmpty() &&
                            !last.hasErrors &&
                            secretStore.load().isSignedIn
                        )
                        last
                    } finally {
                        withContext(NonCancellable) {
                            flightMutex.withLock {
                                if (activeSync === created) {
                                    activeSync = null
                                }
                            }
                        }
                    }
                }
                activeSync = created
                created
            }
        }
        return deferred.await()
    }

    /** Resolves one pending conflict with the user's explicit choice (`KEEP_LOCAL` or `USE_CLOUD`). */
    suspend fun resolveConflict(conflict: SyncConflict, choice: ConflictChoice): Boolean =
        syncExecutionMutex.withLock {
            val gen = authGeneration.get()
            val creds = secretStore.load()
            if (!creds.isSignedIn) return@withLock false

            _uiState.update { it.copy(isResolvingConflict = true, lastError = null) }
            return@withLock try {
                val tokenProvider = getOrCreateTokenProvider(creds, gen)
                val engine = CloudSyncEngine(
                    driveApi = driveApiFactory(tokenProvider),
                    filesDir = filesDir,
                    clock = clock,
                    shouldPersistBaseline = { authGeneration.get() == gen },
                )
                val outcome = withContext(Dispatchers.IO) {
                    engine.resolveConflict(conflict, choice)
                }
                if (authGeneration.get() != gen) return@withLock false

                if (outcome.systemUpdated || outcome.savesUpdated) {
                    runCatching { onLocalDataUpdated(outcome.systemUpdated, outcome.savesUpdated) }
                }
                val now = clock.nowMs()
                _uiState.update { state ->
                    val remaining = state.pendingConflicts.filterNot { it.relativePath == conflict.relativePath }
                    val updatedResult = state.lastResult?.let { prev ->
                        prev.copy(
                            uploaded = prev.uploaded + outcome.uploaded,
                            downloaded = prev.downloaded + outcome.downloaded,
                            deletedLocal = prev.deletedLocal + outcome.deletedLocal,
                            deletedRemote = prev.deletedRemote + outcome.deletedRemote,
                            pendingConflicts = remaining,
                            systemUpdated = prev.systemUpdated || outcome.systemUpdated,
                            savesUpdated = prev.savesUpdated || outcome.savesUpdated,
                            syncedAtMs = now,
                        )
                    }
                    state.copy(
                        isResolvingConflict = false,
                        pendingConflicts = remaining,
                        lastResult = updatedResult,
                        lastSyncedAtMs = now,
                    )
                }
                true
            } catch (e: CancellationException) {
                if (authGeneration.get() == gen) {
                    _uiState.update { it.copy(isResolvingConflict = false) }
                }
                throw e
            } catch (e: Exception) {
                val msg = redactSecrets(e.message ?: e.javaClass.simpleName)
                if (authGeneration.get() == gen) {
                    _uiState.update { it.copy(isResolvingConflict = false, lastError = msg) }
                }
                false
            }
        }

    /** Resolves all currently pending conflicts sequentially using the same choice. */
    suspend fun resolveAllConflicts(choice: ConflictChoice) {
        while (true) {
            val next = _uiState.value.pendingConflicts.firstOrNull() ?: break
            val ok = resolveConflict(next, choice)
            if (!ok) break
        }
    }

    /** Dismisses the conflict dialog (`稍后处理`); unresolved entries will be re-checked on next sync. */
    fun dismissConflicts() {
        _uiState.update { it.copy(pendingConflicts = emptyList()) }
    }

    private suspend fun executeSync(): SyncResult {
        val gen = authGeneration.get()
        val creds = secretStore.load()
        if (!creds.isSignedIn) {
            val err = "尚未登录 Google Drive"
            _uiState.update { it.copy(isSignedIn = false, isSyncing = false, lastError = err) }
            return SyncResult(errors = listOf(err), syncedAtMs = clock.nowMs())
        }

        _uiState.update {
            it.copy(
                isSyncing = true,
                progressMessage = "开始同步…",
                lastError = null,
            )
        }

        return try {
            val tokenProvider = getOrCreateTokenProvider(creds, gen)
            val engine = CloudSyncEngine(
                driveApi = driveApiFactory(tokenProvider),
                filesDir = filesDir,
                clock = clock,
                shouldPersistBaseline = { authGeneration.get() == gen },
            )
            val result = withContext(Dispatchers.IO) {
                engine.syncAll(
                    onProgress = { msg ->
                        if (authGeneration.get() == gen) {
                            _uiState.update { it.copy(progressMessage = msg) }
                        }
                    },
                )
            }
            if (authGeneration.get() != gen) {
                return SyncResult(syncedAtMs = clock.nowMs())
            }
            if (result.systemUpdated || result.savesUpdated) {
                runCatching { onLocalDataUpdated(result.systemUpdated, result.savesUpdated) }
            }
            lastAutoSyncAtMs = clock.nowMs()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    progressMessage = null,
                    lastResult = result,
                    lastSyncedAtMs = result.syncedAtMs,
                    lastError = result.errors.firstOrNull(),
                    pendingConflicts = result.pendingConflicts,
                )
            }
            result
        } catch (ce: CancellationException) {
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(isSyncing = false, progressMessage = null) }
            }
            throw ce
        } catch (authErr: GDriveAuthException) {
            val msg = redactSecrets(authErr.message ?: "Google Drive 登录已过期，请重新登录")
            if (authGeneration.get() == gen) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        progressMessage = null,
                        lastError = msg,
                    )
                }
            }
            SyncResult(errors = listOf(msg), syncedAtMs = clock.nowMs())
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: e.javaClass.simpleName)
            if (authGeneration.get() == gen) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        progressMessage = null,
                        lastError = msg,
                    )
                }
            }
            SyncResult(errors = listOf(msg), syncedAtMs = clock.nowMs())
        }
    }

    private fun getOrCreateTokenProvider(creds: StoredOAuthCredentials, generation: Long): OAuthTokenProvider {
        cachedTokenProvider?.let { return it }
        val provider = OAuthTokenProvider(
            clientId = creds.clientId,
            clientSecret = creds.clientSecret,
            initialAccessToken = creds.accessToken,
            initialRefreshToken = creds.refreshToken,
            initialExpiresAtMs = creds.expiresAtMs,
            tokenEndpoint = tokenEndpoint,
            clock = clock,
            onTokensRefreshed = { newAccess, rotatedRefresh, newExpiresAt ->
                if (authGeneration.get() != generation) return@OAuthTokenProvider
                val latest = secretStore.load()
                if (!latest.isSignedIn) return@OAuthTokenProvider
                secretStore.save(
                    latest.copy(
                        accessToken = newAccess,
                        refreshToken = rotatedRefresh ?: latest.refreshToken,
                        expiresAtMs = newExpiresAt,
                    ),
                )
            },
        )
        cachedTokenProvider = provider
        return provider
    }
}
