package com.wa2port.android.sync

import com.wa2port.core.engine.SystemData
import com.wa2port.core.sync.CloudSyncSchema
import com.wa2port.core.sync.ConflictReason
import com.wa2port.core.sync.LocksCodec
import com.wa2port.core.sync.LocksDocument
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CloudSyncEngineTest {

    @TempDir
    lateinit var tempDir: File

    private class FakeDriveApi : DriveApi {
        private var nextId = 1
        val folders = LinkedHashMap<String, DriveFile>()
        val files = LinkedHashMap<String, Pair<DriveFile, ByteArray>>()
        var uploadCalls = 0
        var downloadCalls = 0
        var deleteCalls = 0
        var onBeforeDownload: (suspend (fileId: String) -> Unit)? = null
        var onBeforeListChildren: (suspend (folderId: String) -> Unit)? = null

        override suspend fun findOrCreateFolder(name: String, parentId: String?): DriveFile {
            val existing = folders.values.firstOrNull { f ->
                f.name == name && (if (parentId == null) f.parents.isEmpty() else parentId in f.parents)
            }
            if (existing != null) return existing
            val id = "folder_${nextId++}"
            val created = DriveFile(
                id = id,
                name = name,
                mimeType = DRIVE_FOLDER_MIME,
                parents = parentId?.let { listOf(it) } ?: emptyList(),
            )
            folders[id] = created
            return created
        }

        override suspend fun listChildren(folderId: String): List<DriveFile> {
            onBeforeListChildren?.invoke(folderId)
            val childFolders = folders.values.filter { folderId in it.parents }
            val childFiles = files.values.map { it.first }.filter { folderId in it.parents }
            return childFolders + childFiles
        }

        override suspend fun downloadFile(fileId: String): ByteArray {
            onBeforeDownload?.invoke(fileId)
            downloadCalls++
            return files[fileId]?.second?.copyOf() ?: throw DriveFileNotFoundException("Missing $fileId")
        }

        override suspend fun uploadFile(
            parentId: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            existingFileId: String?,
            modifiedTimeMs: Long?,
        ): DriveFile {
            uploadCalls++
            val id = existingFileId ?: "file_${nextId++}"
            val existing = existingFileId?.let { files[it]?.first }
            val parents = existing?.parents?.ifEmpty { listOf(parentId) } ?: listOf(parentId)
            val meta = DriveFile(
                id = id,
                name = name,
                mimeType = mimeType,
                md5Checksum = md5Hex(content),
                modifiedTimeMs = modifiedTimeMs ?: System.currentTimeMillis(),
                size = content.size.toLong(),
                parents = parents,
            )
            files[id] = meta to content.copyOf()
            return meta
        }

        override suspend fun deleteFile(fileId: String) {
            deleteCalls++
            files.remove(fileId)
        }

        fun putRemoteFile(parentId: String, name: String, content: String, modifiedTimeMs: Long): DriveFile {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val id = "file_${nextId++}"
            val meta = DriveFile(
                id = id,
                name = name,
                mimeType = JSON_MIME,
                md5Checksum = md5Hex(bytes),
                modifiedTimeMs = modifiedTimeMs,
                size = bytes.size.toLong(),
                parents = listOf(parentId),
            )
            files[id] = meta to bytes
            return meta
        }

        fun updateRemoteFile(fileId: String, content: String, modifiedTimeMs: Long): DriveFile {
            val prev = files[fileId]?.first ?: error("No remote file $fileId")
            val bytes = content.toByteArray(Charsets.UTF_8)
            val updated = prev.copy(
                md5Checksum = md5Hex(bytes),
                modifiedTimeMs = modifiedTimeMs,
                size = bytes.size.toLong(),
            )
            files[fileId] = updated to bytes
            return updated
        }

        fun findRemoteByName(name: String): Pair<DriveFile, ByteArray>? =
            files.values.firstOrNull { it.first.name == name }
    }

    private fun saveJson(
        timestamp: Long,
        scene: String = "1001",
        previewCn: String = "中文台词",
        previewJp: String = "日本語",
    ): String = """{"version":1,"timestamp":$timestamp,"scene":"$scene","previewJp":"$previewJp","previewCn":"$previewCn"}"""

    private fun savesDir(root: File = tempDir): File = File(root, "saves").apply { mkdirs() }

    @Test
    fun fastForwardUploadDedupAndNeverSyncsUndoOrQuickPrev() = runBlocking {
        val drive = FakeDriveApi()
        var now = 1_000_000L
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { now })

        val saves = savesDir()
        File(saves, "auto.json").writeText(saveJson(10_000L, "1001"))
        File(saves, "quick.json").writeText(saveJson(20_000L, "1002"))
        File(saves, "slot_01.json").writeText(saveJson(30_000L, "1003"))
        File(saves, "slot_36.json").writeText(saveJson(40_000L, "2001"))
        // Excluded files MUST NEVER be uploaded.
        File(saves, "undo.json").writeText(saveJson(99_000L, "9999"))
        File(saves, "quick.prev.json").writeText(saveJson(98_000L, "9998"))
        File(tempDir, "system.json").writeText(SystemData().toJson())

        val r1 = engine.syncAll()
        assertEquals(5, r1.uploaded) // auto, quick, slot_01, slot_36, system
        assertEquals(0, r1.downloaded)
        assertTrue(r1.pendingConflicts.isEmpty())
        assertNotNull(drive.findRemoteByName("auto.json"))
        assertNotNull(drive.findRemoteByName("quick.json"))
        assertNotNull(drive.findRemoteByName("slot_01.json"))
        assertNotNull(drive.findRemoteByName("slot_36.json"))
        assertNotNull(drive.findRemoteByName("system.json"))
        assertNull(drive.findRemoteByName("undo.json"))
        assertNull(drive.findRemoteByName("quick.prev.json"))

        // Second sync with identical MD5 is a pure no-op.
        val uploadsBefore = drive.uploadCalls
        val downloadsBefore = drive.downloadCalls
        now += 10_000L
        val r2 = engine.syncAll()
        assertEquals(0, r2.uploaded)
        assertEquals(0, r2.downloaded)
        assertEquals(uploadsBefore, drive.uploadCalls)
        assertEquals(downloadsBefore, drive.downloadCalls)
    }

    @Test
    fun fastForwardDownloadAndLocksMaterializationOnFreshDevice() = runBlocking {
        val drive = FakeDriveApi()
        val root = drive.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME)
        val remoteSaves = drive.findOrCreateFolder(CloudSyncSchema.SAVES_FOLDER_NAME, root.id)
        drive.putRemoteFile(remoteSaves.id, "slot_03.json", saveJson(50_000L, "2005"), 50_000L)
        drive.putRemoteFile(
            remoteSaves.id,
            "locks.json",
            LocksCodec.encode(LocksDocument(1, 55_000L, listOf(0, 3))),
            55_000L,
        )
        val sys = SystemData().apply { gflags[80] = 1; read.markRead("1001", 7) }
        drive.putRemoteFile(root.id, "system.json", sys.toJson(), 60_000L)

        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 100_000L })
        val res = engine.syncAll()

        assertEquals(3, res.downloaded)
        assertEquals(0, res.uploaded)
        assertTrue(res.systemUpdated)
        assertTrue(res.savesUpdated)
        assertTrue(File(savesDir(), "slot_03.json").isFile)
        assertTrue(File(savesDir(), "quick.lock").isFile)
        assertTrue(File(savesDir(), "slot_03.lock").isFile)
        assertFalse(File(savesDir(), "slot_01.lock").isFile)
        val loadedSys = SystemData().apply { loadJson(File(tempDir, "system.json").readText()) }
        assertEquals(1, loadedSys.gflags[80])
        assertTrue(loadedSys.read.isRead("1001", 7))
    }

    @Test
    fun autoResolvesTimestampConflictByNewerWinsAndWritesBackupWhenRemoteWins() = runBlocking {
        val drive = FakeDriveApi()
        val root = drive.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME)
        val remoteSaves = drive.findOrCreateFolder(CloudSyncSchema.SAVES_FOLDER_NAME, root.id)

        val saves = savesDir()
        // Slot 01: remote is 10s newer -> remote wins automatically, backup written.
        val localOld = saveJson(100_000L, "1001", previewCn = "本地旧存档")
        val remoteNew = saveJson(110_000L, "1002", previewCn = "云端新存档")
        File(saves, "slot_01.json").writeText(localOld)
        drive.putRemoteFile(remoteSaves.id, "slot_01.json", remoteNew, 110_000L)

        // Slot 02: local is 10s newer -> local wins automatically and uploads.
        val localNew = saveJson(210_000L, "2002", previewCn = "本地新存档")
        val remoteOld = saveJson(200_000L, "2001", previewCn = "云端旧存档")
        File(saves, "slot_02.json").writeText(localNew)
        drive.putRemoteFile(remoteSaves.id, "slot_02.json", remoteOld, 200_000L)

        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 500_000L })
        val res = engine.syncAll()

        assertTrue(res.pendingConflicts.isEmpty())
        assertEquals(2, res.autoResolvedConflicts)
        assertEquals(1, res.downloaded)
        assertEquals(1, res.uploaded)
        assertEquals(remoteNew, File(saves, "slot_01.json").readText())
        assertEquals(localNew, drive.findRemoteByName("slot_02.json")!!.second.toString(Charsets.UTF_8))

        // Backup written for overwritten local slot_01.json.
        val backupDir = File(tempDir, CloudSyncSchema.CONFLICTS_BACKUP_DIR_NAME)
        val backups = backupDir.listFiles().orEmpty()
        assertEquals(1, backups.size)
        assertTrue(backups[0].name.startsWith("slot_01.json.conflict-"))
        assertTrue(backups[0].name.endsWith(".bak"))
        assertEquals(localOld, backups[0].readText())
    }

    @Test
    fun emitsAndResolvesTieAndMissingTimestampConflicts() = runBlocking {
        val drive = FakeDriveApi()
        val root = drive.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME)
        val remoteSaves = drive.findOrCreateFolder(CloudSyncSchema.SAVES_FOLDER_NAME, root.id)

        val saves = savesDir()
        // Slot 04: 2s difference (< 5s tie guard) -> TIMESTAMP_TIE
        val localTie = saveJson(100_000L, "1004", previewCn = "本地")
        val remoteTie = saveJson(102_000L, "1005", previewCn = "云端")
        File(saves, "slot_04.json").writeText(localTie)
        drive.putRemoteFile(remoteSaves.id, "slot_04.json", remoteTie, 102_000L)

        // Slot 05: missing timestamp on remote -> MISSING_TIMESTAMP
        val localValid = saveJson(200_000L, "2001", previewCn = "本地有效")
        val remoteNoTs = """{"version":1,"scene":"2002","previewCn":"云端无时间戳"}"""
        File(saves, "slot_05.json").writeText(localValid)
        drive.putRemoteFile(remoteSaves.id, "slot_05.json", remoteNoTs, 300_000L)

        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 900_000L })
        val res = engine.syncAll()

        assertEquals(2, res.pendingConflicts.size)
        val c4 = res.pendingConflicts.first { it.slot == 4 }
        val c5 = res.pendingConflicts.first { it.slot == 5 }
        assertEquals(ConflictReason.TIMESTAMP_TIE, c4.reason)
        assertEquals(ConflictReason.MISSING_TIMESTAMP, c5.reason)

        // Resolve slot 4 with USE_CLOUD -> local overwritten + backup created.
        val out4 = engine.resolveConflict(c4, ConflictChoice.USE_CLOUD)
        assertEquals(1, out4.downloaded)
        assertEquals(remoteTie, File(saves, "slot_04.json").readText())
        assertTrue(File(tempDir, CloudSyncSchema.CONFLICTS_BACKUP_DIR_NAME).listFiles().orEmpty().isNotEmpty())

        // Resolve slot 5 with KEEP_LOCAL -> remote overwritten with local.
        val out5 = engine.resolveConflict(c5, ConflictChoice.KEEP_LOCAL)
        assertEquals(1, out5.uploaded)
        assertEquals(localValid, drive.findRemoteByName("slot_05.json")!!.second.toString(Charsets.UTF_8))

        // Next sync is completely clean with zero conflicts!
        val followUp = engine.syncAll()
        assertTrue(followUp.pendingConflicts.isEmpty())
        assertEquals(0, followUp.uploaded)
        assertEquals(0, followUp.downloaded)
    }

    @Test
    fun locallyLockedSlotNeverAutoOverwrittenOrDeletedByRemote() = runBlocking {
        val drive = FakeDriveApi()
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 1_000_000L })
        val saves = savesDir()

        // Establish initial synced baseline for slot 07 and slot 08.
        File(saves, "slot_07.json").writeText(saveJson(100_000L, "1007"))
        File(saves, "slot_08.json").writeText(saveJson(100_000L, "1008"))
        val initial = engine.syncAll()
        assertEquals(2, initial.uploaded)

        // Lock both slots locally.
        File(saves, "slot_07.lock").writeText("1")
        File(saves, "slot_08.lock").writeText("1")

        // Remote updates slot 07 (fast-forward!) and deletes slot 08.
        val remote7 = drive.findRemoteByName("slot_07.json")!!.first
        val remote8 = drive.findRemoteByName("slot_08.json")!!.first
        val remote7New = saveJson(200_000L, "2007", previewCn = "远端覆盖")
        drive.updateRemoteFile(remote7.id, remote7New, 200_000L)
        drive.deleteFile(remote8.id)

        val res = engine.syncAll()
        assertEquals(2, res.pendingConflicts.size)
        assertTrue(res.pendingConflicts.all { it.reason == ConflictReason.LOCAL_LOCKED })
        // Local locked files MUST remain untouched!
        assertEquals(saveJson(100_000L, "1007"), File(saves, "slot_07.json").readText())
        assertTrue(File(saves, "slot_08.json").isFile)

        // User chooses USE_CLOUD on slot 08 (accepts remote deletion) -> local deleted + lock cleared.
        val c8 = res.pendingConflicts.first { it.slot == 8 }
        engine.resolveConflict(c8, ConflictChoice.USE_CLOUD)
        assertFalse(File(saves, "slot_08.json").exists())
        assertFalse(File(saves, "slot_08.lock").exists())
    }

    @Test
    fun modifyVsDeleteEmitsConflictAndResolvesBothDirections() = runBlocking {
        val drive = FakeDriveApi()
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 1_000_000L })
        val saves = savesDir()

        // Sync initial baseline for slot 10 and slot 11.
        File(saves, "slot_10.json").writeText(saveJson(100_000L, "1010"))
        File(saves, "slot_11.json").writeText(saveJson(100_000L, "1011"))
        engine.syncAll()

        // Slot 10: local modified, remote deleted.
        val local10Mod = saveJson(150_000L, "1010", previewCn = "本地已修改")
        File(saves, "slot_10.json").writeText(local10Mod)
        drive.deleteFile(drive.findRemoteByName("slot_10.json")!!.first.id)

        // Slot 11: local deleted, remote modified.
        File(saves, "slot_11.json").delete()
        val remote11Mod = saveJson(160_000L, "1011", previewCn = "云端已修改")
        drive.updateRemoteFile(drive.findRemoteByName("slot_11.json")!!.first.id, remote11Mod, 160_000L)

        val res = engine.syncAll()
        assertEquals(2, res.pendingConflicts.size)
        val c10 = res.pendingConflicts.first { it.slot == 10 }
        val c11 = res.pendingConflicts.first { it.slot == 11 }
        assertEquals(ConflictReason.MODIFY_VS_DELETE, c10.reason)
        assertEquals(ConflictReason.MODIFY_VS_DELETE, c11.reason)

        // Slot 10: KEEP_LOCAL -> re-uploads local modified file.
        engine.resolveConflict(c10, ConflictChoice.KEEP_LOCAL)
        assertEquals(local10Mod, drive.findRemoteByName("slot_10.json")!!.second.toString(Charsets.UTF_8))

        // Slot 11: KEEP_LOCAL (local deleted) -> deletes remote file.
        engine.resolveConflict(c11, ConflictChoice.KEEP_LOCAL)
        assertNull(drive.findRemoteByName("slot_11.json"))
    }

    @Test
    fun systemJsonUnionMergeAndLocksConflictResolutionInSyncEngine() = runBlocking {
        val drive = FakeDriveApi()
        val root = drive.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME)
        val remoteSaves = drive.findOrCreateFolder(CloudSyncSchema.SAVES_FOLDER_NAME, root.id)

        val localSys = SystemData().apply {
            gflags[80] = 1
            read.markRead("1001", 1)
            cgFlags.add("CG_A")
        }
        val remoteSys = SystemData().apply {
            gflags[300] = 1
            read.markRead("1001", 2)
            cgFlags.add("CG_B")
        }
        File(tempDir, "system.json").writeText(localSys.toJson())
        drive.putRemoteFile(root.id, "system.json", remoteSys.toJson(), 100_000L)

        // Locks conflict within 5s tie window -> union of lockedSlots.
        val saves = savesDir()
        File(saves, "slot_01.lock").writeText("1")
        File(saves, "locks.json").writeText(LocksCodec.encode(LocksDocument(1, 100_000L, listOf(1))))
        drive.putRemoteFile(
            remoteSaves.id,
            "locks.json",
            LocksCodec.encode(LocksDocument(1, 102_000L, listOf(2))),
            102_000L,
        )

        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 200_000L })
        val res = engine.syncAll()

        assertTrue(res.pendingConflicts.isEmpty())
        assertTrue(res.systemUpdated)
        assertTrue(res.savesUpdated)

        val mergedSys = SystemData().apply { loadJson(File(tempDir, "system.json").readText()) }
        assertEquals(1, mergedSys.gflags[80])
        assertEquals(1, mergedSys.gflags[300])
        assertTrue(mergedSys.read.isRead("1001", 1))
        assertTrue(mergedSys.read.isRead("1001", 2))
        assertEquals(setOf("CG_A", "CG_B"), mergedSys.cgFlags)

        assertTrue(File(saves, "slot_01.lock").isFile)
        assertTrue(File(saves, "slot_02.lock").isFile)
        val remoteLocks = LocksCodec.decodeOrNull(drive.findRemoteByName("locks.json")!!.second)!!
        assertEquals(listOf(1, 2), remoteLocks.lockedSlots)
    }

    @Test
    fun managerExposesConflictQueueAndResolveAllUpdatesStateAndCallbacks() = runBlocking {
        val drive = FakeDriveApi()
        val root = drive.findOrCreateFolder(CloudSyncSchema.ROOT_FOLDER_NAME)
        val remoteSaves = drive.findOrCreateFolder(CloudSyncSchema.SAVES_FOLDER_NAME, root.id)
        val saves = savesDir()

        File(saves, "slot_01.json").writeText(saveJson(100_000L, "1001", previewCn = "L1"))
        File(saves, "slot_02.json").writeText(saveJson(100_000L, "1002", previewCn = "L2"))
        drive.putRemoteFile(remoteSaves.id, "slot_01.json", saveJson(101_000L, "1001", previewCn = "R1"), 101_000L)
        drive.putRemoteFile(remoteSaves.id, "slot_02.json", saveJson(101_000L, "1002", previewCn = "R2"), 101_000L)

        val scope = CoroutineScope(SupervisorJob())
        var saveCallbackCount = 0
        try {
            val secrets = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = "test-client",
                    accessToken = "tok",
                    refreshToken = "ref",
                    expiresAtMs = 9_999_999_999_999L,
                    accountEmail = "tester@example.com",
                ),
            )
            val manager = CloudSyncManager(
                secretStore = secrets,
                isAutoSyncEnabled = { true },
                filesDir = tempDir,
                scope = scope,
                onLocalDataUpdated = { _, savesChanged -> if (savesChanged) saveCallbackCount++ },
                clock = SyncClock { 500_000L },
                driveApiFactory = { drive },
            )

            val result = manager.syncNow()
            assertEquals(2, result.pendingConflicts.size)
            assertEquals(2, manager.uiState.value.pendingConflicts.size)

            manager.resolveAllConflicts(ConflictChoice.USE_CLOUD)
            assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
            assertTrue(saveCallbackCount >= 2)
            assertTrue(File(saves, "slot_01.json").readText().contains("R1"))
            assertTrue(File(saves, "slot_02.json").readText().contains("R2"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun recreatedSavesFolderNeverDeletesLocalSavesAndReuploadsThem() = runBlocking {
        val drive = FakeDriveApi()
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 1_000_000L })
        val saves = savesDir()

        val slot1Content = saveJson(100_000L, "1001", previewCn = "重要存档")
        File(saves, "slot_01.json").writeText(slot1Content)
        val r1 = engine.syncAll()
        assertEquals(1, r1.uploaded)

        // Simulate user trashing/recreating the remote `saves/` subfolder in Google Drive.
        val oldSavesFolder = drive.folders.values.first { it.name == CloudSyncSchema.SAVES_FOLDER_NAME }
        drive.folders.remove(oldSavesFolder.id)
        drive.files.entries.removeAll { oldSavesFolder.id in it.value.first.parents }

        val r2 = engine.syncAll()
        assertEquals(0, r2.deletedLocal)
        assertEquals(1, r2.uploaded)
        assertTrue(File(saves, "slot_01.json").isFile)
        assertEquals(slot1Content, File(saves, "slot_01.json").readText())
        assertEquals(slot1Content, drive.findRemoteByName("slot_01.json")!!.second.toString(Charsets.UTF_8))
    }

    @Test
    fun remoteDrivenLocalDeleteWritesConflictBackupBeforeDeleting() = runBlocking {
        val drive = FakeDriveApi()
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 2_000_000L })
        val saves = savesDir()

        val original = saveJson(100_000L, "1001", previewCn = "即将远端删除")
        File(saves, "slot_09.json").writeText(original)
        engine.syncAll()

        // Delete remotely while local stays unchanged -> local deleted, backup preserved.
        drive.deleteFile(drive.findRemoteByName("slot_09.json")!!.first.id)
        val res = engine.syncAll()

        assertEquals(1, res.deletedLocal)
        assertFalse(File(saves, "slot_09.json").exists())
        val backups = File(tempDir, CloudSyncSchema.CONFLICTS_BACKUP_DIR_NAME).listFiles().orEmpty()
        assertEquals(1, backups.size)
        assertTrue(backups[0].name.startsWith("slot_09.json.conflict-"))
        assertEquals(original, backups[0].readText())
    }

    @Test
    fun toctouGuardSkipsDownloadOverwriteWhenLocalChangesMidDownload() = runBlocking {
        val drive = FakeDriveApi()
        val engine = CloudSyncEngine(drive, tempDir, clock = SyncClock { 3_000_000L })
        val saves = savesDir()

        val v1 = saveJson(100_000L, "1001", previewCn = "v1")
        File(saves, "slot_06.json").writeText(v1)
        engine.syncAll()

        // Remote updates to v2 (fast-forward candidate).
        val remoteV2 = saveJson(200_000L, "1002", previewCn = "remote-v2")
        val remoteId = drive.findRemoteByName("slot_06.json")!!.first.id
        drive.updateRemoteFile(remoteId, remoteV2, 200_000L)

        // While downloading remote-v2, active GameSession writes local-v3!
        val localV3 = saveJson(250_000L, "1003", previewCn = "local-v3-fresh")
        drive.onBeforeDownload = { id ->
            if (id == remoteId) {
                File(saves, "slot_06.json").writeText(localV3)
            }
        }

        val res = engine.syncAll()
        // TOCTOU guard must NOT clobber local-v3!
        assertEquals(0, res.downloaded)
        assertEquals(localV3, File(saves, "slot_06.json").readText())
    }

    @Test
    fun dirtyFlagRunsFollowUpPassWhenSaveArrivesDuringInFlightSync() = runBlocking {
        val drive = FakeDriveApi()
        val saves = savesDir()
        val firstSave = saveJson(100_000L, "1001", previewCn = "first")
        val secondSave = saveJson(200_000L, "1002", previewCn = "second-mid-sync")
        File(saves, "slot_01.json").writeText(firstSave)

        val scope = CoroutineScope(SupervisorJob())
        val enteredFirstSync = CompletableDeferred<Unit>()
        val releaseFirstSync = CompletableDeferred<Unit>()
        var listCount = 0
        drive.onBeforeListChildren = { _ ->
            listCount++
            if (listCount == 1) {
                enteredFirstSync.complete(Unit)
                releaseFirstSync.await()
            }
        }

        try {
            val secrets = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = "test-client",
                    accessToken = "tok",
                    refreshToken = "ref",
                    expiresAtMs = 9_999_999_999_999L,
                ),
            )
            val manager = CloudSyncManager(
                secretStore = secrets,
                isAutoSyncEnabled = { true },
                filesDir = tempDir,
                scope = scope,
                clock = SyncClock { 500_000L },
                driveApiFactory = { drive },
            )

            val syncJob = async { manager.syncNow() }
            enteredFirstSync.await()

            // New save written mid-sync + auto-sync triggered -> sets dirtyWhileSyncing flag.
            File(saves, "slot_02.json").writeText(secondSave)
            manager.triggerAutoSync(reason = "save", debounce = false)
            releaseFirstSync.complete(Unit)

            syncJob.await()
            assertEquals(firstSave, drive.findRemoteByName("slot_01.json")!!.second.toString(Charsets.UTF_8))
            assertEquals(secondSave, drive.findRemoteByName("slot_02.json")!!.second.toString(Charsets.UTF_8))
        } finally {
            scope.cancel()
        }
    }
}
