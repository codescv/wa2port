package com.wa2port.android.sync

import com.wa2port.core.sync.CloudSyncSchema
import com.wa2port.core.sync.ConflictReason
import com.wa2port.core.sync.LocksCodec
import com.wa2port.core.sync.LocksDocument
import com.wa2port.core.sync.SaveConflictDecision
import com.wa2port.core.sync.SaveConflictEvaluator
import com.wa2port.core.sync.SavePreviewParser
import com.wa2port.core.sync.SystemDataMerger
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale

data class ResolveConflictOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val systemUpdated: Boolean = false,
    val savesUpdated: Boolean = false,
)

/**
 * Pure-JVM 3-way Google Drive sync engine for WA2Port (matching iOS schema 100%).
 *
 * Remote layout under My Drive:
 * ```
 * WA2Port Sync/
 *   system.json
 *   saves/
 *     auto.json
 *     quick.json
 *     slot_01.json .. slot_36.json
 *     locks.json
 * ```
 *
 * Never syncs `saves/undo.json`, `saves/quick.prev.json`, `*.lock`, `*.tmp`, or `*.bak`.
 */
class CloudSyncEngine(
    private val driveApi: DriveApi,
    private val filesDir: File,
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val shouldPersistBaseline: () -> Boolean = { true },
) {
    private val savesDir: File get() = File(filesDir, CloudSyncSchema.SAVES_FOLDER_NAME)
    private val systemFile: File get() = File(filesDir, CloudSyncSchema.SYSTEM_FILE_NAME)
    private val locksFile: File get() = File(savesDir, CloudSyncSchema.LOCKS_FILE_NAME)
    private val baselineFile: File get() = File(filesDir, CloudSyncSchema.SYNC_STATE_FILE_NAME)
    private val conflictsBackupDir: File get() = File(filesDir, CloudSyncSchema.CONFLICTS_BACKUP_DIR_NAME)

    suspend fun syncAll(onProgress: (String) -> Unit = {}): SyncResult {
        savesDir.mkdirs()
        val initialState = loadBaselineState()
        val entries = initialState.entries.toMutableMap()
        var activeRootFolderId: String? = initialState.rootFolderId
        var activeSavesFolderId: String? = initialState.savesFolderId

        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var skipped = 0
        var autoResolvedConflicts = 0
        var systemUpdated = false
        var savesUpdated = false
        val pendingConflicts = mutableListOf<SyncConflict>()
        val errors = mutableListOf<String>()

        try {
            onProgress("正在连接 Google Drive…")
            val rootFolder = driveApi.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME, parentId = null)
            // Account switch / recreated root folder guard: if the Drive root folder ID changed,
            // old baseline entries belong to a different/deleted folder and must be cleared so
            // local saves are never silently overwritten or mass-deleted.
            if (activeRootFolderId != null && activeRootFolderId != rootFolder.id) {
                entries.clear()
                activeSavesFolderId = null
            }
            activeRootFolderId = rootFolder.id

            val savesFolder = driveApi.findOrCreateFolder(
                CloudSyncSchema.SAVES_FOLDER_NAME,
                parentId = rootFolder.id,
            )
            // Recreated saves/ subfolder guard: drop stale saves/* baseline entries so local saves
            // are re-uploaded instead of treated as remotely deleted.
            if (activeSavesFolderId != null && activeSavesFolderId != savesFolder.id) {
                entries.keys.removeAll { it.startsWith("${CloudSyncSchema.SAVES_FOLDER_NAME}/") }
            }
            activeSavesFolderId = savesFolder.id

            val rootChildren = indexByNameLatest(driveApi.listChildren(rootFolder.id))
            val savesChildren = indexByNameLatest(driveApi.listChildren(savesFolder.id))

            // Capture local locks before syncing slots so locally locked slots are never silently
            // overwritten or deleted by remote changes.
            val localLockedSlotsBeforeSync = readLocalLockedSlots().toSet()

            // 1. Sync system.json (with lossless union merge on conflict).
            onProgress("正在同步系统数据…")
            try {
                val sysOutcome = syncSystemFile(
                    remoteFile = rootChildren[CloudSyncSchema.SYSTEM_FILE_NAME]?.takeIf { !it.isFolder },
                    rootFolderId = rootFolder.id,
                    entries = entries,
                )
                uploaded += sysOutcome.uploaded
                downloaded += sysOutcome.downloaded
                skipped += sysOutcome.skipped
                autoResolvedConflicts += sysOutcome.autoResolved
                if (sysOutcome.localChanged) systemUpdated = true
                sysOutcome.conflict?.let { pendingConflicts += it }
            } catch (authErr: GDriveAuthException) {
                throw authErr
            } catch (e: Exception) {
                errors += "system.json: ${redactSecrets(e.message ?: e.javaClass.simpleName)}"
            }

            // 2. Sync save slots (auto, quick, slot_01..slot_36 — NEVER undo.json or quick.prev.json).
            val slots = CloudSyncSchema.SYNCED_SLOTS
            slots.forEachIndexed { idx, slot ->
                val fileName = CloudSyncSchema.saveFileNameForSlot(slot) ?: return@forEachIndexed
                val relPath = CloudSyncSchema.relativePathForSlot(slot) ?: return@forEachIndexed
                val localFile = File(savesDir, fileName)
                val remoteFile = savesChildren[fileName]?.takeIf { !it.isFolder }
                val baselineEntry = entries[relPath]
                if (!localFile.isFile && remoteFile == null && baselineEntry == null) {
                    return@forEachIndexed
                }

                onProgress("正在同步存档 (${idx + 1}/${slots.size})…")
                try {
                    val outcome = syncSaveSlot(
                        slot = slot,
                        fileName = fileName,
                        relPath = relPath,
                        localFile = localFile,
                        remoteFile = remoteFile,
                        savesFolderId = savesFolder.id,
                        isLocallyLocked = slot >= CloudSyncSchema.QUICK_SLOT && slot in localLockedSlotsBeforeSync,
                        entries = entries,
                    )
                    uploaded += outcome.uploaded
                    downloaded += outcome.downloaded
                    deletedLocal += outcome.deletedLocal
                    deletedRemote += outcome.deletedRemote
                    skipped += outcome.skipped
                    autoResolvedConflicts += outcome.autoResolved
                    if (outcome.localChanged) savesUpdated = true
                    outcome.conflict?.let { pendingConflicts += it }
                } catch (authErr: GDriveAuthException) {
                    throw authErr
                } catch (e: Exception) {
                    errors += "$fileName: ${redactSecrets(e.message ?: e.javaClass.simpleName)}"
                }
            }

            // 3. Sync saves/locks.json (and materialize .lock sidecars).
            onProgress("正在同步存档锁定状态…")
            try {
                val locksOutcome = syncLocksFile(
                    remoteFile = savesChildren[CloudSyncSchema.LOCKS_FILE_NAME]?.takeIf { !it.isFolder },
                    savesFolderId = savesFolder.id,
                    entries = entries,
                )
                uploaded += locksOutcome.uploaded
                downloaded += locksOutcome.downloaded
                skipped += locksOutcome.skipped
                autoResolvedConflicts += locksOutcome.autoResolved
                if (locksOutcome.localChanged) savesUpdated = true
            } catch (authErr: GDriveAuthException) {
                throw authErr
            } catch (e: Exception) {
                errors += "locks.json: ${redactSecrets(e.message ?: e.javaClass.simpleName)}"
            }
        } finally {
            if (shouldPersistBaseline()) {
                saveBaselineState(
                    SyncBaselineState(
                        version = 1,
                        rootFolderId = activeRootFolderId,
                        savesFolderId = activeSavesFolderId,
                        entries = entries,
                    ),
                )
            }
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            skipped = skipped,
            autoResolvedConflicts = autoResolvedConflicts,
            pendingConflicts = pendingConflicts,
            systemUpdated = systemUpdated,
            savesUpdated = savesUpdated,
            errors = errors,
            syncedAtMs = clock.nowMs(),
        )
    }

    /**
     * Executes the user's explicit choice (`KEEP_LOCAL` or `USE_CLOUD`) for an unresolved
     * [SyncConflict] and updates `sync_state.json` immediately.
     */
    suspend fun resolveConflict(
        conflict: SyncConflict,
        choice: ConflictChoice,
    ): ResolveConflictOutcome {
        val state = loadBaselineState()
        val entries = state.entries.toMutableMap()
        val localFile = localFileForRelativePath(conflict.relativePath)
        val remoteFileName = conflict.relativePath.substringAfterLast('/')
        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var systemUpdated = false
        var savesUpdated = false

        when (choice) {
            ConflictChoice.KEEP_LOCAL -> {
                if (conflict.local.exists) {
                    val bytes = if (localFile.isFile) {
                        localFile.readBytes()
                    } else {
                        conflict.local.bytes ?: error("Local file missing for ${conflict.relativePath}")
                    }
                    val md5 = md5Hex(bytes)
                    val saveTs = if (conflict.slot != null) {
                        SavePreviewParser.parse(bytes, localFile.lastModified()).timestampMs
                    } else {
                        localFile.lastModified().takeIf { it > 0L } ?: clock.nowMs()
                    }
                    val uploadedFile = driveApi.uploadFile(
                        parentId = conflict.parentFolderId,
                        name = remoteFileName,
                        mimeType = JSON_MIME,
                        content = bytes,
                        existingFileId = conflict.remoteFileId,
                        modifiedTimeMs = saveTs.takeIf { it > 0L },
                    )
                    entries[conflict.relativePath] = SyncBaselineEntry(
                        baseMd5 = uploadedFile.md5Checksum ?: md5,
                        remoteFileId = uploadedFile.id,
                        remoteModifiedMs = uploadedFile.modifiedTimeMs,
                        localSaveTsMs = saveTs,
                    )
                    uploaded++
                } else {
                    // Local was deleted; propagate deletion to Drive.
                    conflict.remoteFileId?.let { driveApi.deleteFile(it) }
                    entries.remove(conflict.relativePath)
                    deletedRemote++
                }
            }

            ConflictChoice.USE_CLOUD -> {
                if (conflict.remote.exists) {
                    val remoteBytes = conflict.remote.bytes
                        ?: conflict.remoteFileId?.let { driveApi.downloadFile(it) }
                        ?: error("Cloud file content missing for ${conflict.relativePath}")
                    val remoteMd5 = conflict.remote.md5 ?: md5Hex(remoteBytes)
                    if (localFile.isFile) {
                        writeConflictBackup(conflict.relativePath, localFile.readBytes())
                    }
                    writeFileAtomic(localFile, remoteBytes)
                    val saveTs = if (conflict.slot != null) {
                        SavePreviewParser.parse(remoteBytes, conflict.remoteModifiedMs).timestampMs
                    } else {
                        conflict.remoteModifiedMs
                    }
                    entries[conflict.relativePath] = SyncBaselineEntry(
                        baseMd5 = remoteMd5,
                        remoteFileId = conflict.remoteFileId ?: "",
                        remoteModifiedMs = conflict.remoteModifiedMs,
                        localSaveTsMs = saveTs,
                    )
                    downloaded++
                    if (conflict.isSystemFile) systemUpdated = true else savesUpdated = true
                } else {
                    // Remote was deleted; remove local file (backing up first and clearing lock if needed).
                    if (localFile.isFile) {
                        writeConflictBackup(conflict.relativePath, localFile.readBytes())
                        localFile.delete()
                        deletedLocal++
                    }
                    val slot = conflict.slot
                    if (slot != null && slot >= CloudSyncSchema.QUICK_SLOT && lockSidecarFile(slot).isFile) {
                        lockSidecarFile(slot).delete()
                        val updatedLocks = LocksDocument(
                            version = 1,
                            updatedAtMs = clock.nowMs(),
                            lockedSlots = readLocalLockedSlots(),
                        )
                        writeFileAtomic(locksFile, LocksCodec.encode(updatedLocks).toByteArray(Charsets.UTF_8))
                    }
                    entries.remove(conflict.relativePath)
                    if (conflict.isSystemFile) systemUpdated = true else savesUpdated = true
                }
            }
        }

        if (shouldPersistBaseline()) {
            saveBaselineState(state.copy(entries = entries))
        }

        return ResolveConflictOutcome(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            systemUpdated = systemUpdated,
            savesUpdated = savesUpdated,
        )
    }

    // ------------------------------------------------------------------ per-file sync

    private data class ItemSyncOutcome(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val deletedLocal: Int = 0,
        val deletedRemote: Int = 0,
        val skipped: Int = 0,
        val autoResolved: Int = 0,
        val localChanged: Boolean = false,
        val conflict: SyncConflict? = null,
    )

    private suspend fun syncSystemFile(
        remoteFile: DriveFile?,
        rootFolderId: String,
        entries: MutableMap<String, SyncBaselineEntry>,
    ): ItemSyncOutcome {
        val relPath = CloudSyncSchema.SYSTEM_REMOTE_PATH
        val localExists = systemFile.isFile
        val remoteExists = remoteFile != null
        val baseEntry = entries[relPath]
        val baseMd5 = baseEntry?.baseMd5

        if (!localExists && !remoteExists) {
            entries.remove(relPath)
            return ItemSyncOutcome()
        }

        if (localExists && !remoteExists) {
            // Never auto-delete local system.json; upload local if new or modified, or re-upload if remote missing.
            val bytes = systemFile.readBytes()
            val localMd5 = md5Hex(bytes)
            val uploadedFile = driveApi.uploadFile(
                parentId = rootFolderId,
                name = CloudSyncSchema.SYSTEM_FILE_NAME,
                mimeType = JSON_MIME,
                content = bytes,
                existingFileId = null,
                modifiedTimeMs = systemFile.lastModified().takeIf { it > 0L },
            )
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = uploadedFile.md5Checksum ?: localMd5,
                remoteFileId = uploadedFile.id,
                remoteModifiedMs = uploadedFile.modifiedTimeMs,
                localSaveTsMs = systemFile.lastModified(),
            )
            return ItemSyncOutcome(uploaded = 1)
        }

        if (!localExists && remoteExists) {
            val bytes = driveApi.downloadFile(remoteFile!!.id)
            if (!isLocalUnchanged(systemFile, expectedMd5 = null)) {
                return ItemSyncOutcome(skipped = 1)
            }
            val md5 = remoteFile.md5Checksum ?: md5Hex(bytes)
            writeFileAtomic(systemFile, bytes)
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = md5,
                remoteFileId = remoteFile.id,
                remoteModifiedMs = remoteFile.modifiedTimeMs,
                localSaveTsMs = remoteFile.modifiedTimeMs,
            )
            return ItemSyncOutcome(downloaded = 1, localChanged = true)
        }

        // Both local and remote system.json exist.
        val remote = remoteFile!!
        val localBytes = systemFile.readBytes()
        val localMd5 = md5Hex(localBytes)
        var remoteBytesCache: ByteArray? = null
        val remoteMd5 = remote.md5Checksum ?: run {
            val dl = driveApi.downloadFile(remote.id)
            remoteBytesCache = dl
            md5Hex(dl)
        }

        if (localMd5.equals(remoteMd5, ignoreCase = true)) {
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = localMd5,
                remoteFileId = remote.id,
                remoteModifiedMs = remote.modifiedTimeMs,
                localSaveTsMs = systemFile.lastModified(),
            )
            return ItemSyncOutcome(skipped = 1)
        }

        // Fast-forward: only remote changed since baseline.
        if (baseMd5 != null && localMd5.equals(baseMd5, ignoreCase = true)) {
            val bytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
            if (!isLocalUnchanged(systemFile, expectedMd5 = localMd5)) {
                return ItemSyncOutcome(skipped = 1)
            }
            val actualMd5 = remote.md5Checksum ?: md5Hex(bytes)
            writeFileAtomic(systemFile, bytes)
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = actualMd5,
                remoteFileId = remote.id,
                remoteModifiedMs = remote.modifiedTimeMs,
                localSaveTsMs = remote.modifiedTimeMs,
            )
            return ItemSyncOutcome(downloaded = 1, localChanged = true)
        }

        // Fast-forward: only local changed since baseline.
        if (baseMd5 != null && remoteMd5.equals(baseMd5, ignoreCase = true)) {
            val uploadedFile = driveApi.uploadFile(
                parentId = rootFolderId,
                name = CloudSyncSchema.SYSTEM_FILE_NAME,
                mimeType = JSON_MIME,
                content = localBytes,
                existingFileId = remote.id,
                modifiedTimeMs = systemFile.lastModified().takeIf { it > 0L },
            )
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = uploadedFile.md5Checksum ?: localMd5,
                remoteFileId = uploadedFile.id,
                remoteModifiedMs = uploadedFile.modifiedTimeMs,
                localSaveTsMs = systemFile.lastModified(),
            )
            return ItemSyncOutcome(uploaded = 1)
        }

        // Both changed (or first sync with differing files): perform lossless union merge.
        val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
        val mergedText = SystemDataMerger.mergeJson(
            localText = localBytes.toString(Charsets.UTF_8),
            remoteText = remoteBytes.toString(Charsets.UTF_8),
        )
        if (mergedText == null) {
            return ItemSyncOutcome(
                conflict = SyncConflict(
                    relativePath = relPath,
                    slot = null,
                    reason = ConflictReason.UNPARSEABLE,
                    local = ConflictSideInfo.fromRaw(
                        bytes = localBytes,
                        md5 = localMd5,
                        timestampMs = systemFile.lastModified(),
                        summary = "本地系统数据 (${localBytes.size} B)",
                    ),
                    remote = ConflictSideInfo.fromRaw(
                        bytes = remoteBytes,
                        md5 = remoteMd5,
                        timestampMs = remote.modifiedTimeMs,
                        summary = "云端系统数据 (${remoteBytes.size} B)",
                    ),
                    remoteFileId = remote.id,
                    remoteModifiedMs = remote.modifiedTimeMs,
                    parentFolderId = rootFolderId,
                ),
            )
        }

        val mergedBytes = mergedText.toByteArray(Charsets.UTF_8)
        val mergedMd5 = md5Hex(mergedBytes)
        val localNeedsWrite = !mergedMd5.equals(localMd5, ignoreCase = true)
        if (localNeedsWrite) {
            if (!isLocalUnchanged(systemFile, expectedMd5 = localMd5)) {
                return ItemSyncOutcome(skipped = 1)
            }
            writeFileAtomic(systemFile, mergedBytes)
        }
        val remoteNeedsUpload = !mergedMd5.equals(remoteMd5, ignoreCase = true)
        val finalRemote = if (remoteNeedsUpload) {
            driveApi.uploadFile(
                parentId = rootFolderId,
                name = CloudSyncSchema.SYSTEM_FILE_NAME,
                mimeType = JSON_MIME,
                content = mergedBytes,
                existingFileId = remote.id,
                modifiedTimeMs = clock.nowMs(),
            )
        } else {
            remote
        }
        entries[relPath] = SyncBaselineEntry(
            baseMd5 = finalRemote.md5Checksum ?: mergedMd5,
            remoteFileId = finalRemote.id,
            remoteModifiedMs = finalRemote.modifiedTimeMs,
            localSaveTsMs = clock.nowMs(),
        )
        return ItemSyncOutcome(
            uploaded = if (remoteNeedsUpload) 1 else 0,
            downloaded = if (localNeedsWrite && !remoteNeedsUpload) 1 else 0,
            autoResolved = 1,
            localChanged = localNeedsWrite,
        )
    }

    private suspend fun syncSaveSlot(
        slot: Int,
        fileName: String,
        relPath: String,
        localFile: File,
        remoteFile: DriveFile?,
        savesFolderId: String,
        isLocallyLocked: Boolean,
        entries: MutableMap<String, SyncBaselineEntry>,
    ): ItemSyncOutcome {
        val localExists = localFile.isFile
        val remoteExists = remoteFile != null
        val baseMd5 = entries[relPath]?.baseMd5

        // Case 4: Both absent.
        if (!localExists && !remoteExists) {
            entries.remove(relPath)
            return ItemSyncOutcome()
        }

        // Case 2: Local exists, remote absent.
        if (localExists && !remoteExists) {
            val localBytes = localFile.readBytes()
            val localMd5 = md5Hex(localBytes)
            val localMeta = SavePreviewParser.parse(localBytes, localFile.lastModified())

            if (baseMd5 == null) {
                // Brand-new local save -> fast-forward upload.
                val uploaded = driveApi.uploadFile(
                    parentId = savesFolderId,
                    name = fileName,
                    mimeType = JSON_MIME,
                    content = localBytes,
                    existingFileId = null,
                    modifiedTimeMs = localMeta.timestampMs.takeIf { it > 0L },
                )
                entries[relPath] = SyncBaselineEntry(
                    baseMd5 = uploaded.md5Checksum ?: localMd5,
                    remoteFileId = uploaded.id,
                    remoteModifiedMs = uploaded.modifiedTimeMs,
                    localSaveTsMs = localMeta.timestampMs,
                )
                return ItemSyncOutcome(uploaded = 1)
            }

            if (localMd5.equals(baseMd5, ignoreCase = true)) {
                // Remote deleted while local unchanged.
                if (isLocallyLocked) {
                    return ItemSyncOutcome(
                        conflict = SyncConflict(
                            relativePath = relPath,
                            slot = slot,
                            reason = ConflictReason.LOCAL_LOCKED,
                            local = ConflictSideInfo.fromSave(localBytes, localMd5, localMeta, isLocked = true),
                            remote = ConflictSideInfo.deleted(),
                            remoteFileId = null,
                            remoteModifiedMs = 0L,
                            parentFolderId = savesFolderId,
                        ),
                    )
                }
                if (!isLocalUnchanged(localFile, expectedMd5 = localMd5)) {
                    return ItemSyncOutcome(skipped = 1)
                }
                writeConflictBackup(relPath, localBytes)
                localFile.delete()
                entries.remove(relPath)
                return ItemSyncOutcome(deletedLocal = 1, localChanged = true)
            }

            // Local modified while remote deleted -> modify-vs-delete conflict.
            return ItemSyncOutcome(
                conflict = SyncConflict(
                    relativePath = relPath,
                    slot = slot,
                    reason = if (isLocallyLocked) ConflictReason.LOCAL_LOCKED else ConflictReason.MODIFY_VS_DELETE,
                    local = ConflictSideInfo.fromSave(localBytes, localMd5, localMeta, isLocked = isLocallyLocked),
                    remote = ConflictSideInfo.deleted(),
                    remoteFileId = null,
                    remoteModifiedMs = 0L,
                    parentFolderId = savesFolderId,
                ),
            )
        }

        // Case 3: Local absent, remote exists.
        if (!localExists && remoteExists) {
            val remote = remoteFile!!
            var remoteBytesCache: ByteArray? = null
            val remoteMd5 = remote.md5Checksum ?: run {
                val dl = driveApi.downloadFile(remote.id)
                remoteBytesCache = dl
                md5Hex(dl)
            }

            if (baseMd5 == null) {
                // Brand-new remote save -> fast-forward download.
                val bytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
                if (!isLocalUnchanged(localFile, expectedMd5 = null)) {
                    return ItemSyncOutcome(skipped = 1)
                }
                val actualMd5 = remote.md5Checksum ?: md5Hex(bytes)
                val remoteMeta = SavePreviewParser.parse(bytes, remote.modifiedTimeMs)
                writeFileAtomic(localFile, bytes)
                entries[relPath] = SyncBaselineEntry(
                    baseMd5 = actualMd5,
                    remoteFileId = remote.id,
                    remoteModifiedMs = remote.modifiedTimeMs,
                    localSaveTsMs = remoteMeta.timestampMs,
                )
                return ItemSyncOutcome(downloaded = 1, localChanged = true)
            }

            if (remoteMd5.equals(baseMd5, ignoreCase = true)) {
                // Local deleted while remote unchanged -> propagate delete to Drive.
                driveApi.deleteFile(remote.id)
                entries.remove(relPath)
                return ItemSyncOutcome(deletedRemote = 1)
            }

            // Remote modified while local deleted -> modify-vs-delete conflict.
            val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
            val remoteMeta = SavePreviewParser.parse(remoteBytes, remote.modifiedTimeMs)
            return ItemSyncOutcome(
                conflict = SyncConflict(
                    relativePath = relPath,
                    slot = slot,
                    reason = ConflictReason.MODIFY_VS_DELETE,
                    local = ConflictSideInfo.deleted(),
                    remote = ConflictSideInfo.fromSave(remoteBytes, remoteMd5, remoteMeta),
                    remoteFileId = remote.id,
                    remoteModifiedMs = remote.modifiedTimeMs,
                    parentFolderId = savesFolderId,
                ),
            )
        }

        // Case 1: Both local and remote exist.
        val remote = remoteFile!!
        val localBytes = localFile.readBytes()
        val localMd5 = md5Hex(localBytes)
        val localMeta = SavePreviewParser.parse(localBytes, localFile.lastModified())
        var remoteBytesCache: ByteArray? = null
        val remoteMd5 = remote.md5Checksum ?: run {
            val dl = driveApi.downloadFile(remote.id)
            remoteBytesCache = dl
            md5Hex(dl)
        }

        // 1a. Identical MD5 -> no-op, update baseline.
        if (localMd5.equals(remoteMd5, ignoreCase = true)) {
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = localMd5,
                remoteFileId = remote.id,
                remoteModifiedMs = remote.modifiedTimeMs,
                localSaveTsMs = localMeta.timestampMs,
            )
            return ItemSyncOutcome(skipped = 1)
        }

        // 1b. Fast-forward remote -> local (only remote changed since baseline).
        if (baseMd5 != null && localMd5.equals(baseMd5, ignoreCase = true)) {
            val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
            val actualMd5 = remote.md5Checksum ?: md5Hex(remoteBytes)
            val remoteMeta = SavePreviewParser.parse(remoteBytes, remote.modifiedTimeMs)
            if (isLocallyLocked) {
                return ItemSyncOutcome(
                    conflict = SyncConflict(
                        relativePath = relPath,
                        slot = slot,
                        reason = ConflictReason.LOCAL_LOCKED,
                        local = ConflictSideInfo.fromSave(localBytes, localMd5, localMeta, isLocked = true),
                        remote = ConflictSideInfo.fromSave(remoteBytes, actualMd5, remoteMeta),
                        remoteFileId = remote.id,
                        remoteModifiedMs = remote.modifiedTimeMs,
                        parentFolderId = savesFolderId,
                    ),
                )
            }
            if (!isLocalUnchanged(localFile, expectedMd5 = localMd5)) {
                return ItemSyncOutcome(skipped = 1)
            }
            writeFileAtomic(localFile, remoteBytes)
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = actualMd5,
                remoteFileId = remote.id,
                remoteModifiedMs = remote.modifiedTimeMs,
                localSaveTsMs = remoteMeta.timestampMs,
            )
            return ItemSyncOutcome(downloaded = 1, localChanged = true)
        }

        // 1c. Fast-forward local -> remote (only local changed since baseline).
        if (baseMd5 != null && remoteMd5.equals(baseMd5, ignoreCase = true)) {
            val uploaded = driveApi.uploadFile(
                parentId = savesFolderId,
                name = fileName,
                mimeType = JSON_MIME,
                content = localBytes,
                existingFileId = remote.id,
                modifiedTimeMs = localMeta.timestampMs.takeIf { it > 0L },
            )
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = uploaded.md5Checksum ?: localMd5,
                remoteFileId = uploaded.id,
                remoteModifiedMs = uploaded.modifiedTimeMs,
                localSaveTsMs = localMeta.timestampMs,
            )
            return ItemSyncOutcome(uploaded = 1)
        }

        // 1d. Both changed (or first sync with differing files): evaluate conflict policy.
        val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remote.id)
        val actualRemoteMd5 = remote.md5Checksum ?: md5Hex(remoteBytes)
        val remoteMeta = SavePreviewParser.parse(remoteBytes, remote.modifiedTimeMs)

        return when (val decision = SaveConflictEvaluator.evaluate(localMeta, remoteMeta, isLocallyLocked)) {
            SaveConflictDecision.AutoLocalWins -> {
                val uploaded = driveApi.uploadFile(
                    parentId = savesFolderId,
                    name = fileName,
                    mimeType = JSON_MIME,
                    content = localBytes,
                    existingFileId = remote.id,
                    modifiedTimeMs = localMeta.timestampMs.takeIf { it > 0L },
                )
                entries[relPath] = SyncBaselineEntry(
                    baseMd5 = uploaded.md5Checksum ?: localMd5,
                    remoteFileId = uploaded.id,
                    remoteModifiedMs = uploaded.modifiedTimeMs,
                    localSaveTsMs = localMeta.timestampMs,
                )
                ItemSyncOutcome(uploaded = 1, autoResolved = 1)
            }

            SaveConflictDecision.AutoRemoteWins -> {
                if (!isLocalUnchanged(localFile, expectedMd5 = localMd5)) {
                    return ItemSyncOutcome(skipped = 1)
                }
                writeConflictBackup(relPath, localBytes)
                writeFileAtomic(localFile, remoteBytes)
                entries[relPath] = SyncBaselineEntry(
                    baseMd5 = actualRemoteMd5,
                    remoteFileId = remote.id,
                    remoteModifiedMs = remote.modifiedTimeMs,
                    localSaveTsMs = remoteMeta.timestampMs,
                )
                ItemSyncOutcome(downloaded = 1, autoResolved = 1, localChanged = true)
            }

            is SaveConflictDecision.NeedsUserChoice -> {
                ItemSyncOutcome(
                    conflict = SyncConflict(
                        relativePath = relPath,
                        slot = slot,
                        reason = decision.reason,
                        local = ConflictSideInfo.fromSave(localBytes, localMd5, localMeta, isLocked = isLocallyLocked),
                        remote = ConflictSideInfo.fromSave(remoteBytes, actualRemoteMd5, remoteMeta),
                        remoteFileId = remote.id,
                        remoteModifiedMs = remote.modifiedTimeMs,
                        parentFolderId = savesFolderId,
                    ),
                )
            }
        }
    }

    private suspend fun syncLocksFile(
        remoteFile: DriveFile?,
        savesFolderId: String,
        entries: MutableMap<String, SyncBaselineEntry>,
    ): ItemSyncOutcome {
        val relPath = CloudSyncSchema.LOCKS_REMOTE_PATH
        val baseEntry = entries[relPath]
        val baseMd5 = baseEntry?.baseMd5
        val localDoc = prepareLocalLocksDocument(hadBaseline = baseEntry != null)

        if (localDoc == null && remoteFile == null) {
            entries.remove(relPath)
            return ItemSyncOutcome()
        }

        if (localDoc == null && remoteFile != null) {
            val remoteBytes = driveApi.downloadFile(remoteFile.id)
            val remoteDoc = LocksCodec.decodeOrNull(remoteBytes) ?: return ItemSyncOutcome(skipped = 1)
            val remoteMd5 = remoteFile.md5Checksum ?: md5Hex(remoteBytes)
            writeFileAtomic(locksFile, remoteBytes)
            applyLocalLockedSlots(remoteDoc.lockedSlots)
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = remoteMd5,
                remoteFileId = remoteFile.id,
                remoteModifiedMs = remoteFile.modifiedTimeMs,
                localSaveTsMs = remoteDoc.updatedAtMs,
            )
            return ItemSyncOutcome(downloaded = 1, localChanged = true)
        }

        val localBytes = locksFile.readBytes()
        val localMd5 = md5Hex(localBytes)

        if (remoteFile == null) {
            // Upload local locks.json (or if local unchanged & remote deleted, still keep empty/clear).
            if (baseMd5 != null && localMd5.equals(baseMd5, ignoreCase = true)) {
                applyLocalLockedSlots(emptyList())
                locksFile.delete()
                entries.remove(relPath)
                return ItemSyncOutcome(deletedLocal = 1, localChanged = true)
            }
            val uploaded = driveApi.uploadFile(
                parentId = savesFolderId,
                name = CloudSyncSchema.LOCKS_FILE_NAME,
                mimeType = JSON_MIME,
                content = localBytes,
                existingFileId = null,
                modifiedTimeMs = localDoc!!.updatedAtMs.takeIf { it > 0L },
            )
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = uploaded.md5Checksum ?: localMd5,
                remoteFileId = uploaded.id,
                remoteModifiedMs = uploaded.modifiedTimeMs,
                localSaveTsMs = localDoc.updatedAtMs,
            )
            return ItemSyncOutcome(uploaded = 1)
        }

        // Both local and remote locks.json exist.
        var remoteBytesCache: ByteArray? = null
        val remoteMd5 = remoteFile.md5Checksum ?: run {
            val dl = driveApi.downloadFile(remoteFile.id)
            remoteBytesCache = dl
            md5Hex(dl)
        }

        if (localMd5.equals(remoteMd5, ignoreCase = true)) {
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = localMd5,
                remoteFileId = remoteFile.id,
                remoteModifiedMs = remoteFile.modifiedTimeMs,
                localSaveTsMs = localDoc!!.updatedAtMs,
            )
            return ItemSyncOutcome(skipped = 1)
        }

        if (baseMd5 != null && localMd5.equals(baseMd5, ignoreCase = true)) {
            val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remoteFile.id)
            val remoteDoc = LocksCodec.decodeOrNull(remoteBytes) ?: return ItemSyncOutcome(skipped = 1)
            val actualMd5 = remoteFile.md5Checksum ?: md5Hex(remoteBytes)
            writeFileAtomic(locksFile, remoteBytes)
            applyLocalLockedSlots(remoteDoc.lockedSlots)
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = actualMd5,
                remoteFileId = remoteFile.id,
                remoteModifiedMs = remoteFile.modifiedTimeMs,
                localSaveTsMs = remoteDoc.updatedAtMs,
            )
            return ItemSyncOutcome(downloaded = 1, localChanged = true)
        }

        if (baseMd5 != null && remoteMd5.equals(baseMd5, ignoreCase = true)) {
            val uploaded = driveApi.uploadFile(
                parentId = savesFolderId,
                name = CloudSyncSchema.LOCKS_FILE_NAME,
                mimeType = JSON_MIME,
                content = localBytes,
                existingFileId = remoteFile.id,
                modifiedTimeMs = localDoc!!.updatedAtMs.takeIf { it > 0L },
            )
            entries[relPath] = SyncBaselineEntry(
                baseMd5 = uploaded.md5Checksum ?: localMd5,
                remoteFileId = uploaded.id,
                remoteModifiedMs = uploaded.modifiedTimeMs,
                localSaveTsMs = localDoc.updatedAtMs,
            )
            return ItemSyncOutcome(uploaded = 1)
        }

        // Conflict on saves/locks.json: resolve by updatedAtMs if |diff| >= 5000ms, else union.
        val remoteBytes = remoteBytesCache ?: driveApi.downloadFile(remoteFile.id)
        val remoteDoc = LocksCodec.decodeOrNull(remoteBytes)
        val resolved = if (remoteDoc == null) {
            localDoc!!
        } else {
            LocksCodec.resolveConflict(localDoc!!, remoteDoc, clock.nowMs())
        }
        val resolvedBytes = LocksCodec.encode(resolved).toByteArray(Charsets.UTF_8)
        val resolvedMd5 = md5Hex(resolvedBytes)

        val localSlotsBefore = localDoc.lockedSlots
        val localNeedsWrite = !resolvedMd5.equals(localMd5, ignoreCase = true) || localSlotsBefore != resolved.lockedSlots
        if (localNeedsWrite) {
            writeFileAtomic(locksFile, resolvedBytes)
            applyLocalLockedSlots(resolved.lockedSlots)
        }

        val remoteNeedsUpload = !resolvedMd5.equals(remoteMd5, ignoreCase = true)
        val finalRemote = if (remoteNeedsUpload) {
            driveApi.uploadFile(
                parentId = savesFolderId,
                name = CloudSyncSchema.LOCKS_FILE_NAME,
                mimeType = JSON_MIME,
                content = resolvedBytes,
                existingFileId = remoteFile.id,
                modifiedTimeMs = resolved.updatedAtMs.takeIf { it > 0L },
            )
        } else {
            remoteFile
        }
        entries[relPath] = SyncBaselineEntry(
            baseMd5 = finalRemote.md5Checksum ?: resolvedMd5,
            remoteFileId = finalRemote.id,
            remoteModifiedMs = finalRemote.modifiedTimeMs,
            localSaveTsMs = resolved.updatedAtMs,
        )
        return ItemSyncOutcome(
            uploaded = if (remoteNeedsUpload) 1 else 0,
            downloaded = if (localNeedsWrite && !remoteNeedsUpload) 1 else 0,
            autoResolved = 1,
            localChanged = localSlotsBefore != resolved.lockedSlots,
        )
    }

    // ------------------------------------------------------------------ local helpers

    private fun prepareLocalLocksDocument(hadBaseline: Boolean): LocksDocument? {
        val sidecarLocks = readLocalLockedSlots()
        val existingDoc = if (locksFile.isFile) {
            runCatching { LocksCodec.decodeOrNull(locksFile.readText()) }.getOrNull()
        } else {
            null
        }
        if (existingDoc != null && existingDoc.lockedSlots == sidecarLocks) {
            return existingDoc
        }
        if (sidecarLocks.isNotEmpty()) {
            val latestSidecarMtime = sidecarLocks
                .map { lockSidecarFile(it).lastModified() }
                .filter { it > 0L }
                .maxOrNull()
                ?: clock.nowMs()
            val doc = LocksDocument(
                version = 1,
                updatedAtMs = maxOf(latestSidecarMtime, existingDoc?.updatedAtMs ?: 0L),
                lockedSlots = sidecarLocks,
            ).canonical()
            writeFileAtomic(locksFile, LocksCodec.encode(doc).toByteArray(Charsets.UTF_8))
            return doc
        }
        if (existingDoc != null || hadBaseline) {
            val doc = LocksDocument(
                version = 1,
                updatedAtMs = clock.nowMs(),
                lockedSlots = emptyList(),
            )
            writeFileAtomic(locksFile, LocksCodec.encode(doc).toByteArray(Charsets.UTF_8))
            return doc
        }
        return null
    }

    internal fun readLocalLockedSlots(): List<Int> =
        (CloudSyncSchema.QUICK_SLOT..CloudSyncSchema.MAX_USER_SLOT).filter { lockSidecarFile(it).isFile }

    private fun applyLocalLockedSlots(lockedSlots: List<Int>) {
        val wanted = lockedSlots.toSet()
        for (slot in CloudSyncSchema.QUICK_SLOT..CloudSyncSchema.MAX_USER_SLOT) {
            val f = lockSidecarFile(slot)
            if (slot in wanted) {
                if (!f.isFile) {
                    f.parentFile?.mkdirs()
                    runCatching { f.writeText("1") }
                }
            } else if (f.isFile) {
                f.delete()
            }
        }
    }

    private fun lockSidecarFile(slot: Int): File {
        val name = if (slot == CloudSyncSchema.QUICK_SLOT) {
            "quick.lock"
        } else {
            "slot_%02d.lock".format(Locale.ROOT, slot)
        }
        return File(savesDir, name)
    }

    private fun localFileForRelativePath(relativePath: String): File = when {
        relativePath == CloudSyncSchema.SYSTEM_REMOTE_PATH -> systemFile
        relativePath.startsWith("${CloudSyncSchema.SAVES_FOLDER_NAME}/") ->
            File(savesDir, relativePath.removePrefix("${CloudSyncSchema.SAVES_FOLDER_NAME}/"))
        else -> File(filesDir, relativePath)
    }

    /** TOCTOU guard: checks that [file] still has [expectedMd5] (or is still absent if null) right before writing/deleting. */
    private fun isLocalUnchanged(file: File, expectedMd5: String?): Boolean {
        val currentMd5 = if (file.isFile) runCatching { md5Hex(file) }.getOrNull() else null
        return if (expectedMd5 == null) {
            currentMd5 == null
        } else {
            currentMd5 != null && currentMd5.equals(expectedMd5, ignoreCase = true)
        }
    }

    private fun writeConflictBackup(relativePath: String, bytes: ByteArray) {
        runCatching {
            conflictsBackupDir.mkdirs()
            val baseName = relativePath.substringAfterLast('/').ifBlank { "file" }
            val ts = clock.nowMs()
            var target = File(conflictsBackupDir, "$baseName.conflict-$ts.bak")
            var suffix = 1
            while (target.exists()) {
                target = File(conflictsBackupDir, "$baseName.conflict-$ts-$suffix.bak")
                suffix++
            }
            writeFileAtomic(target, bytes)
        }
    }

    internal fun loadBaselineState(): SyncBaselineState {
        if (!baselineFile.isFile) return SyncBaselineState()
        return runCatching {
            syncJson.decodeFromString<SyncBaselineState>(baselineFile.readText())
        }.getOrElse { SyncBaselineState() }
    }

    private fun saveBaselineState(state: SyncBaselineState) {
        runCatching {
            val text = syncPrettyJson.encodeToString(state)
            writeFileAtomic(baselineFile, text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun indexByNameLatest(files: List<DriveFile>): Map<String, DriveFile> {
        val map = LinkedHashMap<String, DriveFile>()
        for (f in files) {
            val prev = map[f.name]
            if (prev == null || f.modifiedTimeMs >= prev.modifiedTimeMs) {
                map[f.name] = f
            }
        }
        return map
    }
}
