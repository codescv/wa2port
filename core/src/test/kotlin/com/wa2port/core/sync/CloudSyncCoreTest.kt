package com.wa2port.core.sync

import com.wa2port.core.engine.AudioState
import com.wa2port.core.engine.BmpLayer
import com.wa2port.core.engine.CalendarCard
import com.wa2port.core.engine.SaveData
import com.wa2port.core.engine.StageState
import com.wa2port.core.engine.SystemData
import com.wa2port.core.script.CallFrame
import com.wa2port.core.script.SwitchFrame
import com.wa2port.core.script.Value
import com.wa2port.core.script.VmSnapshot
import com.wa2port.core.script.WaitState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudSyncCoreTest {

    @Test
    fun schemaAcceptsValidSlotsAndRejectsExcludedFiles() {
        assertEquals(-1, CloudSyncSchema.slotForSaveFileName("auto.json"))
        assertEquals(0, CloudSyncSchema.slotForSaveFileName("quick.json"))
        assertEquals(1, CloudSyncSchema.slotForSaveFileName("slot_01.json"))
        assertEquals(36, CloudSyncSchema.slotForSaveFileName("slot_36.json"))
        assertEquals("saves/slot_07.json", CloudSyncSchema.relativePathForSlot(7))

        assertFalse(CloudSyncSchema.isSyncedSaveFileName("undo.json"))
        assertFalse(CloudSyncSchema.isSyncedSaveFileName("quick.prev.json"))
        assertFalse(CloudSyncSchema.isSyncedSaveFileName("slot_00.json"))
        assertFalse(CloudSyncSchema.isSyncedSaveFileName("slot_37.json"))
        assertFalse(CloudSyncSchema.isSyncedSaveFileName("slot_01.lock"))
        assertFalse(CloudSyncSchema.isSyncedSaveFileName("slot_01.json.tmp"))
        assertNull(CloudSyncSchema.relativePathForSlot(-2))
        assertNull(CloudSyncSchema.relativePathForSlot(37))
    }

    @Test
    fun systemDataMergerPerformsLosslessUnionAndRejectsUnparseableJson() {
        val local = SystemData().apply {
            gflags[80] = 1
            gflags[200] = 2
            read.markRead("1001", 0)
            read.markRead("1001", 5)
            read.markRead("1002", 12)
            cgFlags.add("EV_001")
            bgmFlags.add(4)
        }
        val remote = SystemData().apply {
            gflags[81] = 1
            gflags[200] = 5
            read.markRead("1001", 5)
            read.markRead("1001", 10)
            read.markRead("2001", 3)
            cgFlags.add("EV_002")
            bgmFlags.add(63)
        }

        val mergedJson = SystemDataMerger.mergeJson(local.toJson(), remote.toJson())
        assertNotNull(mergedJson)

        val restored = SystemData().apply { loadJson(mergedJson!!) }
        assertEquals(1, restored.gflags[80])
        assertEquals(1, restored.gflags[81])
        assertEquals(5, restored.gflags[200])
        assertEquals(1, restored.gflags[104])
        assertEquals(1, restored.gflags[163])
        assertTrue(restored.read.isRead("1001", 0))
        assertTrue(restored.read.isRead("1001", 5))
        assertTrue(restored.read.isRead("1001", 10))
        assertTrue(restored.read.isRead("1002", 12))
        assertTrue(restored.read.isRead("2001", 3))
        assertEquals(setOf("EV_001", "EV_002"), restored.cgFlags)
        assertEquals(setOf(4, 63), restored.bgmFlags)

        // Unparseable / non-object JSON must return null so sync emits a conflict.
        assertNull(SystemDataMerger.mergeJson("{broken json", remote.toJson()))
        assertNull(SystemDataMerger.mergeJson(local.toJson(), "[1, 2, 3]"))
    }

    @Test
    fun locksCodecResolvesByTimestampOrUnionOnTie() {
        val local = LocksDocument(version = 1, updatedAtMs = 100_000L, lockedSlots = listOf(5, 1))
        val remoteNewer = LocksDocument(version = 1, updatedAtMs = 106_000L, lockedSlots = listOf(2))
        val resolvedNewer = LocksCodec.resolveConflict(local, remoteNewer, nowMs = 200_000L)
        assertEquals(listOf(2), resolvedNewer.lockedSlots)
        assertEquals(106_000L, resolvedNewer.updatedAtMs)

        // Within 5s tie window -> union of lockedSlots.
        val remoteTie = LocksDocument(version = 1, updatedAtMs = 103_000L, lockedSlots = listOf(0, 2))
        val resolvedUnion = LocksCodec.resolveConflict(local, remoteTie, nowMs = 200_000L)
        assertEquals(listOf(0, 1, 2, 5), resolvedUnion.lockedSlots)
        assertEquals(200_000L, resolvedUnion.updatedAtMs)
    }

    @Test
    fun saveConflictEvaluatorEnforcesTimestampTieGuardMissingTsAndLockProtection() {
        val t1 = SavePreviewMeta(timestampMs = 100_000L, timestampReliable = true, scene = "1001")
        val t2Newer = SavePreviewMeta(timestampMs = 105_000L, timestampReliable = true, scene = "1002")
        val t2Tie = SavePreviewMeta(timestampMs = 104_999L, timestampReliable = true, scene = "1002")
        val tMissing = SavePreviewMeta(timestampMs = 150_000L, timestampReliable = false, scene = "1002")

        // >= 5s diff -> newer wins when unlocked.
        assertEquals(SaveConflictDecision.AutoRemoteWins, SaveConflictEvaluator.evaluate(t1, t2Newer, isLocallyLocked = false))
        assertEquals(SaveConflictDecision.AutoLocalWins, SaveConflictEvaluator.evaluate(t2Newer, t1, isLocallyLocked = false))

        // Locally locked slot: remote newer -> interactive choice; local newer -> auto local wins.
        assertEquals(
            SaveConflictDecision.NeedsUserChoice(ConflictReason.LOCAL_LOCKED),
            SaveConflictEvaluator.evaluate(t1, t2Newer, isLocallyLocked = true),
        )
        assertEquals(
            SaveConflictDecision.AutoLocalWins,
            SaveConflictEvaluator.evaluate(t2Newer, t1, isLocallyLocked = true),
        )

        // < 5s tie -> interactive choice.
        assertEquals(
            SaveConflictDecision.NeedsUserChoice(ConflictReason.TIMESTAMP_TIE),
            SaveConflictEvaluator.evaluate(t1, t2Tie, isLocallyLocked = false),
        )

        // Missing / unreliable JSON timestamp -> interactive choice.
        assertEquals(
            SaveConflictDecision.NeedsUserChoice(ConflictReason.MISSING_TIMESTAMP),
            SaveConflictEvaluator.evaluate(t1, tMissing, isLocallyLocked = false),
        )
    }

    @Test
    fun savePreviewParserMarksMissingOrNonPositiveTimestampUnreliable() {
        val valid = SavePreviewParser.parse(
            """{"version":1,"timestamp":1700000000000,"scene":"1005","previewJp":"雪だね","previewCn":"下雪了"}""",
            fallbackMtimeMs = 1234L,
        )
        assertTrue(valid.timestampReliable)
        assertEquals(1700000000000L, valid.timestampMs)
        assertEquals("1005", valid.scene)
        assertEquals("雪だね", valid.previewJp)
        assertEquals("下雪了", valid.previewCn)

        val missing = SavePreviewParser.parse("""{"version":1,"scene":"1005"}""", fallbackMtimeMs = 5555L)
        assertFalse(missing.timestampReliable)
        assertEquals(5555L, missing.timestampMs)

        val zero = SavePreviewParser.parse("""{"version":1,"timestamp":0,"scene":"1005"}""", fallbackMtimeMs = 7777L)
        assertFalse(zero.timestampReliable)
        assertEquals(7777L, zero.timestampMs)
    }

    @Test
    fun saveDataFromJsonLoadsIosGoldenFixtureAndPreservesAndroidRoundTrip() {
        val iosFloat15Bits = java.lang.Float.floatToRawIntBits(1.5f).toUInt().toLong()
        val iosFloat25Bits = java.lang.Float.floatToRawIntBits(2.5f).toUInt().toLong()
        val iosGoldenJson = """
            {
              "version": 1,
              "timestamp": 1732070000123,
              "scene": "1002",
              "previewJp": "雪が降ってきたね",
              "previewCn": "下雪了呢",
              "vm": {
                "script": "1002",
                "pc": 256,
                "callStack": [{"script": "1001", "returnPc": 64}],
                "switchStack": [42, 88],
                "globals": [
                  {"k": "i", "v": 123},
                  {"k": "f", "b": $iosFloat15Bits},
                  {"k": "t", "v": 9},
                  {"k": "s", "s": "kazusa"},
                  {"k": "g", "v": 4},
                  {"k": "la", "v": 2},
                  {"k": "lr", "v": 2, "e": 5}
                ],
                "locals": [
                  {"a": 1, "e": 2, "v": {"k": "i", "v": 77}},
                  {"a": 1, "e": 3, "v": {"k": "f", "b": $iosFloat25Bits}}
                ],
                "ended": false
              },
              "stage": {
                "bg": "",
                "bgDir": "bak",
                "spriteLut": "",
                "cg": "",
                "sprites": [],
                "layers": [
                  {
                    "slot": 0,
                    "image": "ef01.tga",
                    "dir": "grp",
                    "layer": 2,
                    "visible": true,
                    "alpha": 200,
                    "x": 10,
                    "y": 20,
                    "zoom": 1.25,
                    "params": [[1, 10], [2, 20]]
                  }
                ],
                "lut": "",
                "spriteEffectLut": "",
                "chroma": 0,
                "timeMode": 1,
                "weather": -1,
                "tint": 8421504,
                "shake": 0,
                "serial": 7,
                "windowVisible": true,
                "novelMode": false,
                "flashSerial": 1,
                "calendar": {"year": 2007, "month": 11, "day": 12}
              },
              "audio": {
                "bgmTrack": 4,
                "bgmVolume": 220,
                "loops": [
                  {"ch": 0, "name": "rain.ogg", "vol": 180}
                ]
              },
              "voiceScene": 1002,
              "speakerToken": 3,
              "pendingChoices": [11, 12],
              "backlog": [],
              "thumbnail": "",
              "replayMode": 0
            }
        """.trimIndent()

        val loaded = SaveData.fromJson(iosGoldenJson)
        assertEquals(1732070000123L, loaded.timestamp)
        assertEquals("1002", loaded.scene)
        assertEquals("雪が降ってきたね", loaded.previewJp)
        assertEquals("下雪了呢", loaded.previewCn)
        assertNull(loaded.thumbnail)

        // VM fields
        assertEquals("1002", loaded.vm.script)
        assertEquals(256, loaded.vm.pc)
        assertEquals(listOf(CallFrame("1001", 64)), loaded.vm.callStack)
        assertEquals(listOf(SwitchFrame(42), SwitchFrame(88)), loaded.vm.switchStack)
        assertEquals(
            listOf(
                Value.I(123),
                Value.F(1.5f),
                Value.Text(9),
                Value.Str("kazusa"),
                Value.GRef(4),
                Value.LArr(2),
                Value.LRef(2, 5),
            ),
            loaded.vm.globals,
        )
        assertEquals(Value.I(77), loaded.vm.locals[1]?.get(2))
        assertEquals(Value.F(2.5f), loaded.vm.locals[1]?.get(3))
        assertNull(loaded.vm.wait)

        // Stage fields
        assertNull(loaded.stage.bg)
        assertNull(loaded.stage.cg)
        assertNull(loaded.stage.spriteLut)
        assertNull(loaded.stage.lut)
        assertNull(loaded.stage.spriteEffectLut)
        assertNull(loaded.stage.weather)
        assertEquals(CalendarCard(2007, 11, 12), loaded.stage.calendar)
        assertEquals(1, loaded.stage.layers.size)
        assertEquals(mapOf(1 to 10, 2 to 20), loaded.stage.layers[0].params)

        // Audio fields
        assertEquals(4, loaded.audio.bgmTrack)
        assertEquals(220, loaded.audio.bgmVolume)
        assertEquals("rain.ogg" to 180, loaded.audio.loops[0])

        // iOS ended=true maps to WaitState.Ended
        val iosEnded = iosGoldenJson.replace("\"ended\": false", "\"ended\": true")
        assertEquals(WaitState.Ended, SaveData.fromJson(iosEnded).vm.wait)

        // Native Android round-trip remains lossless
        val androidNative = SaveData(
            timestamp = 1732070099999L,
            scene = "2001",
            previewJp = "JP",
            previewCn = "CN",
            vm = VmSnapshot(
                script = "2001",
                pc = 12,
                callStack = listOf(CallFrame("2000", 4)),
                switchStack = listOf(SwitchFrame(99)),
                globals = listOf(Value.I(7), Value.F(3.25f), Value.Text(5)),
                locals = mapOf(2 to mapOf(0 to Value.Str("setsuna"))),
                wait = WaitState.Ended,
            ),
            stage = StageState(
                bg = "B000100.tga",
                weather = 4,
                layers = listOf(BmpLayer(slot = 1, image = "a.tga", params = mapOf(3 to 30))),
            ),
            audio = AudioState(bgmTrack = 7, loops = mapOf(1 to ("wind.ogg" to 120))),
        )
        assertEquals(androidNative, SaveData.fromJson(androidNative.toJson()))
    }
}
