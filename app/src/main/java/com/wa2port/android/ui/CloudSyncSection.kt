package com.wa2port.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.BuildConfig
import com.wa2port.android.data.Settings
import com.wa2port.android.sync.CloudSyncManager
import com.wa2port.android.sync.GoogleAndroidOAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SYNC_OK = Color(0xFF8FD3A8)
private val SYNC_ERR = Color(0xFFFFB0A0)
private val SYNC_WARN = Color(0xFFFFD9A0)

/**
 * 设置 → ☁ 云端同步 Cloud Sync (Google Drive):
 * - Google Drive sign-in / sign-out + account email (Android OAuth 2.0 PKCE + custom URI scheme)
 * - 立即同步 Sync Now button + status (last synced time, uploaded/downloaded counts, error)
 * - 自动同步 Auto-Sync toggle (default ON when signed in)
 * - Optional collapsible Advanced section for custom Android OAuth Client ID override.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudSyncSettingsSection(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    cloudSync: CloudSyncManager,
) {
    val strings = LocalAppStrings.current
    val state by cloudSync.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var draftClientId by remember(state.clientId) { mutableStateOf(state.clientId) }
    var savedNotice by remember { mutableStateOf<String?>(null) }

    SectionTitle(strings.syncSectionTitle)

    // Status card.
    val cardShape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(cardShape)
            .background(Color(0x55141E36))
            .border(BorderStroke(1.dp, Wa2Colors.AccentDim), cardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.isSignedIn) {
            val accountLine = buildString {
                append(strings.syncConnected)
                if (state.accountEmail.isNotBlank()) {
                    append(" · ").append(state.accountEmail)
                } else if (state.accountName.isNotBlank()) {
                    append(" · ").append(state.accountName)
                }
            }
            Text(accountLine, color = SYNC_OK, fontSize = 14.sp, fontWeight = FontWeight.Medium)

            if (state.isSyncing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Wa2Colors.Accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.progressMessage ?: strings.syncSyncing,
                        color = Wa2Colors.Text,
                        fontSize = 13.sp,
                    )
                }
            } else {
                state.lastResult?.let { res ->
                    val timeStr = if (state.lastSyncedAtMs > 0L) {
                        strings.syncLastSynced(formatSyncTimestamp(state.lastSyncedAtMs))
                    } else {
                        ""
                    }
                    Text(
                        "${res.summaryText(strings)}$timeStr",
                        color = if (res.hasPendingConflicts) SYNC_WARN else Wa2Colors.TextDim,
                        fontSize = 12.sp,
                    )
                } ?: Text(
                    strings.syncDirInfo,
                    color = Wa2Colors.TextDim,
                    fontSize = 12.sp,
                )
            }
        } else {
            Text(
                if (state.isSigningIn) strings.syncSigningIn else strings.syncNotSignedIn,
                color = if (state.isSigningIn) Wa2Colors.Accent else Wa2Colors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                strings.syncSignInDesc,
                color = Wa2Colors.TextDim,
                fontSize = 12.sp,
            )
        }

        state.lastError?.let { err ->
            Text("✗ $err", color = SYNC_ERR, fontSize = 12.sp)
        }

        // Action buttons.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.isSignedIn) {
                if (state.isSigningIn) {
                    MenuButton(strings.syncCancelSignInBtn, compact = true) {
                        cloudSync.cancelSignIn()
                    }
                } else {
                    MenuButton(strings.syncSignInBtn, compact = true) {
                        cloudSync.startSignInAndSync { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                throw IllegalStateException(strings.syncNoBrowserErr, e)
                            }
                        }
                    }
                }
            } else {
                MenuButton(
                    title = if (state.isSyncing) strings.syncSyncingBtn else strings.syncSyncNowBtn,
                    enabled = !state.isSyncing && !state.isResolvingConflict,
                    compact = true,
                ) {
                    scope.launch { cloudSync.syncNow() }
                }
                if (!confirmSignOut) {
                    MenuButton(
                        title = strings.syncSignOutBtn,
                        enabled = !state.isSyncing,
                        compact = true,
                    ) {
                        confirmSignOut = true
                    }
                } else {
                    MenuButton(
                        title = strings.syncConfirmSignOutBtn,
                        compact = true,
                    ) {
                        confirmSignOut = false
                        scope.launch { cloudSync.signOut() }
                    }
                    MenuButton(
                        title = strings.commonCancel,
                        compact = true,
                    ) {
                        confirmSignOut = false
                    }
                }
            }
        }
    }

    OptionRow(strings.syncAutoLabel) {
        Segmented(
            listOf(true to strings.commonOn, false to strings.commonOff),
            settings.cloudSyncAutoEnabled,
            { enabled -> onChange { it.copy(cloudSyncAutoEnabled = enabled) } },
        )
    }
    Text(
        strings.syncAutoDesc,
        color = Wa2Colors.TextDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )

    // Collapsible Advanced OAuth section.
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(
            (if (advancedOpen) "▼ " else "▶ ") + strings.syncAdvancedToggle,
            color = Wa2Colors.Accent,
            fontSize = 13.sp,
        )
    }
    if (advancedOpen) {
        val defaultRedirectScheme = remember {
            runCatching { GoogleAndroidOAuth.deriveRedirectScheme(BuildConfig.DEFAULT_GDRIVE_CLIENT_ID) }.getOrNull()
        }
        val derivedRedirectScheme = remember(draftClientId) {
            runCatching { GoogleAndroidOAuth.deriveRedirectScheme(draftClientId) }.getOrNull()
        }
        val derivedRedirectUri = remember(draftClientId) {
            runCatching { GoogleAndroidOAuth.deriveRedirectUri(draftClientId) }.getOrNull()
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                strings.syncAdvancedDesc,
                color = Wa2Colors.TextDim,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            OutlinedTextField(
                value = draftClientId,
                onValueChange = { draftClientId = it; savedNotice = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Android OAuth Client ID", fontSize = 12.sp) },
                placeholder = { Text("xxxx.apps.googleusercontent.com", fontSize = 12.sp, color = Wa2Colors.TextDim) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = syncFieldColors(),
            )
            derivedRedirectUri?.let { uri ->
                Text(
                    "Redirect URI: $uri",
                    color = Wa2Colors.TextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (draftClientId.isNotBlank() && derivedRedirectScheme == null) {
                Text(
                    strings.syncClientIdInvalidWarn,
                    color = SYNC_WARN,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else if (
                derivedRedirectScheme != null &&
                defaultRedirectScheme != null &&
                !derivedRedirectScheme.equals(defaultRedirectScheme, ignoreCase = true)
            ) {
                Text(
                    strings.syncManifestSchemeWarn(defaultRedirectScheme),
                    color = SYNC_WARN,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuButton(strings.syncSaveConfigBtn, compact = true) {
                    cloudSync.saveOAuthConfig(draftClientId)
                    savedNotice = strings.syncSavedNotice
                }
                MenuButton(strings.commonReset, compact = true) {
                    cloudSync.saveOAuthConfig("")
                    draftClientId = cloudSync.uiState.value.clientId
                    savedNotice = strings.syncResetNotice
                }
            }
            savedNotice?.let { Text(it, color = SYNC_OK, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)) }
        }
    }
}

private val syncTimeFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)

private fun formatSyncTimestamp(epochMs: Long): String = syncTimeFormat.format(Date(epochMs))

@Composable
private fun syncFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Wa2Colors.Text,
    unfocusedTextColor = Wa2Colors.Text,
    focusedBorderColor = Wa2Colors.Accent,
    unfocusedBorderColor = Wa2Colors.AccentDim,
    focusedLabelColor = Wa2Colors.Accent,
    unfocusedLabelColor = Wa2Colors.TextDim,
    cursorColor = Wa2Colors.Accent,
)
