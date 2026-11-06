package com.wa2port.android.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wa2port.core.ai.AiJson
import com.wa2port.core.ai.AiPackDownloader
import com.wa2port.core.ai.AiPackEndpointConfig
import com.wa2port.core.ai.AiPackRemoteInfo
import com.wa2port.core.ai.AiPackStats
import com.wa2port.core.ai.CancelToken
import com.wa2port.core.ai.OfflineAiPack
import com.wa2port.core.update.UpdateErrorCode
import com.wa2port.core.update.UpdateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** State of the full-game offline AI explanation pack (Compose state; written on [AiPackController]'s scope). */
sealed interface AiPackState {
    data object NotInstalled : AiPackState
    data object Checking : AiPackState
    data class Available(val info: AiPackRemoteInfo) : AiPackState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val info: AiPackRemoteInfo?) : AiPackState {
        /** 0..1, or null when the total is unknown (indeterminate progress). */
        val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** [updateInfo] = a newer pack is available (from [AiPackController.checkUpdate]); [checked] = a check ran. */
    data class Installed(val stats: AiPackStats, val path: File, val updateInfo: AiPackRemoteInfo? = null, val checked: Boolean = false) : AiPackState

    /** [resumableBytes] > 0 → a `.part` is kept and 重试 resumes it. */
    data class Error(val message: String, val previousInstalled: Installed? = null, val resumableBytes: Long = 0) : AiPackState
}

/** What we know about the installed pack's origin (for update checks). */
@Serializable
internal data class InstalledPackInfo(val sha256: String? = null, val sizeBytes: Long = -1, val updatedAt: String? = null, val tag: String? = null)

/**
 * Owns `filesDir/ai_pack/wa2-ai-pack-v1.wa2ai`: 1-click download (resumable `.part`), cancel,
 * update check, delete, and hot-plugging the opened [OfflineAiPack] into the AI controller via
 * [onPackChanged] (zero restart). Blocking work runs on [io]; every state write happens on
 * [scope] and is guarded by a generation id so a late result of a cancelled / superseded job can
 * never overwrite a newer state (same pattern as `UpdateController`).
 */
class AiPackController(
    val packDir: File,
    private val scope: CoroutineScope,
    private val downloader: AiPackDownloader = AiPackDownloader(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val endpoint: () -> AiPackEndpointConfig = { AiPackEndpointConfig() },
    private val onPackChanged: (OfflineAiPack?) -> Unit = {},
) {
    var state by mutableStateOf<AiPackState>(AiPackState.NotInstalled)
        private set

    val packFile: File get() = File(packDir, OfflineAiPack.FILE_NAME)
    private val infoFile: File get() = File(packDir, "pack-info.json")
    val partFile: File get() = downloader.partFile(packFile)

    /** The opened pack (null when not installed); read from any thread. */
    @Volatile
    var pack: OfflineAiPack? = null
        private set

    private var job: Job? = null
    private var cancel: CancelToken? = null
    private var generation = 0

    val isInstalled: Boolean get() = pack != null
    val busy: Boolean get() = state is AiPackState.Checking || state is AiPackState.Downloading

    /** Bytes of a resumable partial download (0 when none). */
    val partialBytes: Long get() = partFile.takeIf { it.isFile }?.length() ?: 0L

    private val installedState: AiPackState.Installed?
        get() = when (val s = state) {
            is AiPackState.Installed -> s
            is AiPackState.Error -> s.previousInstalled
            else -> pack?.let { AiPackState.Installed(it.stats, it.file) }
        }

    init {
        job = scope.launch { loadInstalled() }
    }

    /** Opens an already-downloaded pack (startup); a corrupt file is removed. */
    suspend fun loadInstalled() {
        val gen = generation
        val opened = withContext(io) {
            val f = packFile
            if (!f.isFile) return@withContext null
            runCatching { OfflineAiPack.open(f) }.getOrElse {
                f.delete()
                null
            }
        }
        if (gen != generation) {
            opened?.close()
            return
        }
        swap(opened)
        state = opened?.let { AiPackState.Installed(it.stats, it.file) } ?: AiPackState.NotInstalled
    }

    private fun swap(next: OfflineAiPack?) {
        val old = pack
        if (old === next) return
        pack = next
        onPackChanged(next)
        old?.close()
    }

    /** 1-click download (or resume / update). No-op while busy. */
    fun download() {
        if (busy) return
        val prevInstalled = installedState
        val knownInfo = (state as? AiPackState.Available)?.info ?: prevInstalled?.updateInfo
        val gen = ++generation
        val token = CancelToken().also { cancel = it }
        state = AiPackState.Downloading(partialBytes, knownInfo?.sizeBytes ?: -1, knownInfo)
        val prev = job
        job = scope.launch {
            try {
                prev?.join()
                val info = knownInfo ?: withContext(io) { downloader.fetchInfo(endpoint(), token) }
                if (gen != generation) return@launch
                state = AiPackState.Downloading(partialBytes, info.sizeBytes, info)
                val opened = withContext(io) {
                    downloader.download(info, packFile, token) { done, total ->
                        scope.launch {
                            if (gen == generation && state is AiPackState.Downloading) state = AiPackState.Downloading(done, total, info)
                        }
                    }
                    runCatching {
                        infoFile.writeText(AiJson.encodeToString(InstalledPackInfo.serializer(), InstalledPackInfo(info.sha256, packFile.length(), info.updatedAt, info.tag)))
                    }
                    OfflineAiPack.open(packFile)
                }
                if (gen != generation) {
                    // Superseded (cancel raced the very end): keep the file, just reopen later.
                    opened.close()
                    return@launch
                }
                swap(opened)
                state = AiPackState.Installed(opened.stats, opened.file, checked = true)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val ue = e as? UpdateException
                state = if (ue?.code == UpdateErrorCode.CANCELLED) {
                    prevInstalled ?: AiPackState.NotInstalled
                } else {
                    AiPackState.Error(ue?.message ?: "${e.javaClass.simpleName}: ${e.message}", prevInstalled, partialBytes)
                }
            }
        }
    }

    /** Cancels a running check / download; the `.part` is kept so the next download resumes. */
    fun cancel() {
        val s = state
        generation++
        cancel?.cancel()
        job?.cancel()
        state = when (s) {
            is AiPackState.Downloading, AiPackState.Checking -> pack?.let { AiPackState.Installed(it.stats, it.file) } ?: AiPackState.NotInstalled
            else -> s
        }
    }

    /** Queries the release: Installed(updateInfo = newer pack) / Available / Error. */
    fun checkUpdate() {
        if (busy) return
        val prevInstalled = installedState
        val gen = ++generation
        val token = CancelToken().also { cancel = it }
        state = AiPackState.Checking
        val prev = job
        job = scope.launch {
            try {
                prev?.join()
                val (info, local) = withContext(io) {
                    downloader.fetchInfo(endpoint(), token) to runCatching { AiJson.decodeFromString(InstalledPackInfo.serializer(), infoFile.readText()) }.getOrNull()
                }
                if (gen != generation) return@launch
                val installedNow = pack
                state = if (installedNow != null) {
                    val newer = isNewer(info, local, installedNow.file.length())
                    AiPackState.Installed(installedNow.stats, installedNow.file, updateInfo = info.takeIf { newer }, checked = true)
                } else {
                    // Also when the API was unavailable (rate limit…): the direct asset may still download fine.
                    AiPackState.Available(info)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val ue = e as? UpdateException
                state = if (ue?.code == UpdateErrorCode.CANCELLED) {
                    prevInstalled ?: AiPackState.NotInstalled
                } else {
                    AiPackState.Error(ue?.message ?: "${e.javaClass.simpleName}: ${e.message}", prevInstalled, partialBytes)
                }
            }
        }
    }

    /** Closes and deletes the installed pack (and any partial download); the AI sheet falls back immediately. */
    fun delete() {
        generation++
        cancel?.cancel()
        job?.cancel()
        swap(null)
        state = AiPackState.NotInstalled
        val dir = packDir
        scope.launch {
            withContext(io) {
                File(dir, OfflineAiPack.FILE_NAME).delete()
                downloader.partFile(File(dir, OfflineAiPack.FILE_NAME)).delete()
                File(dir, "pack-info.json").delete()
            }
        }
    }

    /** Releases the open pack (ViewModel cleared). */
    fun close() {
        generation++
        cancel?.cancel()
        job?.cancel()
        swap(null)
    }

    companion object {
        /** Remote is newer when its digest / size / timestamp differs from what we installed. */
        internal fun isNewer(remote: AiPackRemoteInfo, local: InstalledPackInfo?, localSize: Long): Boolean {
            if (remote.fromFallback) return false
            if (remote.sha256 != null && local?.sha256 != null) return !remote.sha256.equals(local.sha256, ignoreCase = true)
            if (remote.sizeBytes > 0 && localSize > 0 && remote.sizeBytes != localSize) return true
            if (remote.updatedAt != null && local?.updatedAt != null) return remote.updatedAt != local.updatedAt
            return false
        }

        /** `X.X MB`. */
        fun mb(bytes: Long): String = "%.1f".format(java.util.Locale.ROOT, bytes / 1048576.0)
    }
}
