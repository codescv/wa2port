package com.wa2port.android.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.wa2port.android.data.Settings
import com.wa2port.core.ai.CancelToken
import com.wa2port.core.update.AppUpdater
import com.wa2port.core.update.ReleaseInfo
import com.wa2port.core.update.SemanticVersion
import com.wa2port.core.update.UpdateEndpointConfig
import com.wa2port.core.update.UpdateErrorCode
import com.wa2port.core.update.UpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the in-app updater is (Compose state; written on the main thread only). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: ReleaseInfo) : UpdateState
    data class Available(val latest: ReleaseInfo) : UpdateState
    data class Downloading(val latest: ReleaseInfo, val downloadedBytes: Long, val totalBytes: Long) : UpdateState {
        /** 0..1, or null when the total size is unknown (indeterminate progress). */
        val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }
    data class Downloaded(val latest: ReleaseInfo, val apkFile: File) : UpdateState
    data class Error(val message: String, val lastRelease: ReleaseInfo? = null) : UpdateState
}

/**
 * Manual update check → APK download (to `cacheDir/updates`) → hand-off to the system installer
 * through the app's FileProvider. Blocking network work runs on [Dispatchers.IO]; every state write
 * happens on [scope]'s (main) dispatcher and is guarded by a generation id so a late progress post
 * or result from a cancelled / superseded job can never overwrite a newer state.
 */
class UpdateController(
    private val app: Application,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val updater: AppUpdater = AppUpdater(),
) {
    private val installed: PackageInfo? = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()

    /** Installed `versionName` (e.g. `1.2.0`). */
    val currentVersion: String = installed?.versionName ?: "0.0.0"
    private val currentVersionCode: Long = installed?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    /** Title-screen update overlay visibility. */
    var dialogOpen by mutableStateOf(false)

    /** The release the current state refers to, if any. */
    val latest: ReleaseInfo?
        get() = when (val s = state) {
            is UpdateState.UpToDate -> s.latest
            is UpdateState.Available -> s.latest
            is UpdateState.Downloading -> s.latest
            is UpdateState.Downloaded -> s.latest
            is UpdateState.Error -> s.lastRelease
            UpdateState.Idle, UpdateState.Checking -> null
        }

    val busy: Boolean get() = state is UpdateState.Checking || state is UpdateState.Downloading

    private val updatesDir = File(app.cacheDir, "updates")

    /** Last started job; a new job joins it first so two downloads never share the `.tmp` file. */
    private var job: Job? = null
    private var cancel: CancelToken? = null
    private var generation = 0

    init {
        job = scope.launch { restoreDownloaded() }
    }

    /** Queries the configured endpoint. No-op while checking / downloading. [silent] = failures → Idle. */
    fun checkForUpdates(silent: Boolean = false) {
        if (busy) return
        // A ready-to-install APK must survive a failed / cancelled re-check.
        val prevReady = state as? UpdateState.Downloaded
        lastReady = prevReady
        val gen = ++generation
        val token = CancelToken().also { cancel = it }
        val endpoint = UpdateEndpointConfig(settings().updateUrl)
        state = UpdateState.Checking
        val prev = job
        job = scope.launch {
            try {
                prev?.join()
                val result = withContext(Dispatchers.IO) { updater.checkLatestRelease(currentVersion, endpoint, token) }
                val latest = result.latest
                val next: UpdateState = if (!result.updateAvailable) {
                    UpdateState.UpToDate(latest)
                } else {
                    // Already downloaded (and still valid) → straight to "install".
                    val ready = withContext(Dispatchers.IO) { existingDownload(latest) }
                    if (ready != null) UpdateState.Downloaded(latest, ready) else UpdateState.Available(latest)
                }
                if (gen == generation) state = next
            } catch (e: UpdateException) {
                if (gen != generation) return@launch
                val cancelled = e.code == UpdateErrorCode.CANCELLED
                state = when {
                    prevReady != null && prevReady.apkFile.isFile -> {
                        if (!silent && !cancelled) toast("检查更新失败 Check failed: ${e.message ?: e.code.name}")
                        prevReady
                    }
                    silent || cancelled -> UpdateState.Idle
                    else -> UpdateState.Error(e.message ?: e.code.name)
                }
            }
        }
    }

    /** Downloads the APK of [latest] (from Available / Error(lastRelease) / Downloaded for a re-download). */
    fun downloadUpdate() {
        if (busy) return
        val release = latest ?: return
        val asset = release.apkAsset ?: return
        val gen = ++generation
        val token = CancelToken().also { cancel = it }
        val dest = apkFileFor(release)
        state = UpdateState.Downloading(release, 0, asset.sizeBytes)
        val prev = job
        job = scope.launch {
            try {
                prev?.join()
                val file = withContext(Dispatchers.IO) {
                    // Always the asset's browser_download_url: the signed CDN URL it redirects to expires.
                    updater.downloadApk(asset.downloadUrl, dest, token, expectedSize = asset.sizeBytes) { done, total ->
                        scope.launch {
                            if (gen == generation && state is UpdateState.Downloading) {
                                state = UpdateState.Downloading(release, done, total)
                            }
                        }
                    }
                }
                val problem = withContext(Dispatchers.IO) { validateApk(file).also { if (it != null) file.delete() } }
                if (gen != generation || state !is UpdateState.Downloading) return@launch
                state = if (problem != null) UpdateState.Error(problem, release) else UpdateState.Downloaded(release, file)
            } catch (e: UpdateException) {
                if (gen != generation || state !is UpdateState.Downloading) return@launch
                state = if (e.code == UpdateErrorCode.CANCELLED) UpdateState.Available(release) else UpdateState.Error(e.message ?: e.code.name, release)
            }
        }
    }

    /** Cancels a running check (→ Idle, or back to a ready download) or download (→ Available). */
    fun cancelDownload() {
        val s = state
        generation++
        cancel?.cancel()
        job?.cancel()
        state = when (s) {
            is UpdateState.Downloading -> UpdateState.Available(s.latest)
            UpdateState.Checking -> lastReady?.takeIf { it.apkFile.isFile } ?: UpdateState.Idle
            else -> s
        }
    }

    /** The update source changed: a stale "up to date" / error no longer applies. */
    fun onEndpointChanged() {
        if (state is UpdateState.UpToDate || state is UpdateState.Error) state = UpdateState.Idle
    }

    /**
     * Re-validates the downloaded APK, asks for "install unknown apps" if needed, then opens the
     * system installer on a FileProvider URI. [context] should be the Activity.
     */
    fun installApk(context: Context) {
        val s = state as? UpdateState.Downloaded ?: return
        val gen = generation
        scope.launch {
            val problem = withContext(Dispatchers.IO) { validateApk(s.apkFile).also { if (it != null) s.apkFile.delete() } }
            if (gen != generation || state != s) return@launch
            if (problem != null) {
                state = UpdateState.Error(problem, s.latest.takeIf { it.apkAsset != null })
                return@launch
            }
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                Toast.makeText(context, "请允许「安装未知应用」后再回来点击安装\nAllow \"Install unknown apps\", then tap Install again", Toast.LENGTH_LONG).show()
                val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", s.apkFile)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                state = UpdateState.Error("无法启动安装程序 Cannot start installer: ${e.message ?: e.javaClass.simpleName}", s.latest)
            }
        }
    }

    /** Opens the release page (or [url]) in the browser. */
    fun openInBrowser(context: Context, url: String = latest?.htmlUrl ?: UpdateEndpointConfig.DEFAULT_RELEASE_HTML_URL) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "没有可用的浏览器 No browser: $url", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开 Cannot open: $url", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ internals

    /** The Downloaded state a running re-check started from (restored if that check is cancelled). */
    private var lastReady: UpdateState.Downloaded? = null

    private fun toast(msg: String) {
        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
    }

    private fun apkFileFor(release: ReleaseInfo): File =
        File(updatesDir, "wa2port-${release.version.replace(UNSAFE_CHARS, "_")}.apk")

    /** A previously downloaded, still-valid APK for [release], or null. */
    private fun existingDownload(release: ReleaseInfo): File? {
        val f = apkFileFor(release)
        if (!f.isFile) return null
        val size = release.apkAsset?.sizeBytes ?: -1
        if (size > 0 && f.length() != size) return null
        return f.takeIf { validateApk(it) == null }
    }

    /**
     * Null when [apk] is an archive of this app with a higher versionCode than the installed one;
     * otherwise a user-facing reason (the installer would only say "App not installed").
     */
    private fun validateApk(apk: File): String? {
        if (!apk.isFile) return "安装包不存在 APK file missing"
        val info = runCatching { app.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull()
            ?: return "安装包无效或已损坏 Invalid or corrupt APK"
        if (info.packageName != app.packageName) {
            return "安装包无效：包名不符 Wrong package (${info.packageName})"
        }
        val code = PackageInfoCompat.getLongVersionCode(info)
        if (code <= currentVersionCode) {
            return "安装包版本不高于当前版本 APK is not newer (v${info.versionName ?: "?"} / $code ≤ v$currentVersion / $currentVersionCode)"
        }
        return null
    }

    /**
     * Housekeeping + process-death recovery (Android kills the app when "install unknown apps" is
     * granted): deletes orphan `.tmp` files and stale / invalid APKs, and restores Downloaded for the
     * newest valid APK so the user can install without another network round-trip.
     */
    private suspend fun restoreDownloaded() {
        val restored = withContext(Dispatchers.IO) {
            val files = updatesDir.listFiles().orEmpty()
            files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
            var best: Pair<File, PackageInfo>? = null
            for (f in files.filter { it.isFile && it.name.endsWith(".apk") }) {
                if (validateApk(f) != null) {
                    f.delete()
                    continue
                }
                val info = app.packageManager.getPackageArchiveInfo(f.path, 0) ?: continue
                if (best == null || PackageInfoCompat.getLongVersionCode(info) > PackageInfoCompat.getLongVersionCode(best.second)) {
                    best?.first?.delete()
                    best = f to info
                } else {
                    f.delete()
                }
            }
            best
        } ?: return
        val (file, info) = restored
        val version = info.versionName?.let { SemanticVersion.parse(it)?.toString() ?: it }
            ?: file.name.removePrefix("wa2port-").removeSuffix(".apk")
        if (state != UpdateState.Idle) return
        state = UpdateState.Downloaded(
            ReleaseInfo(
                tagName = "v$version",
                version = version,
                title = "v$version",
                body = "",
                publishedAt = null,
                htmlUrl = UpdateEndpointConfig.DEFAULT_RELEASE_HTML_URL,
                apkAsset = null,
            ),
            file,
        )
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        val UNSAFE_CHARS = Regex("[^0-9A-Za-z._-]")
    }
}
