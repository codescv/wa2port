package com.wa2port.core

import com.wa2port.core.engine.GFLAG_COUNT
import com.wa2port.core.engine.HeadlessRunner
import com.wa2port.core.engine.SystemData
import com.wa2port.core.engine.Wa2Engine
import com.wa2port.core.gallery.BgmGallery
import com.wa2port.core.gallery.BgmPage
import com.wa2port.core.gallery.ReplayCategory
import com.wa2port.core.gallery.SceneReplayGallery
import com.wa2port.core.script.ScenarioStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BgmAndReplayTest {
    private val store by lazy { ScenarioStore(TestAssets.require()) }

    @Test
    fun `SystemData gflags supports 1024 entries and persists BGM and replay flags`() {
        val sys = SystemData()
        assertEquals(GFLAG_COUNT, sys.gflags.size)
        sys.gflags[80] = 1
        sys.gflags[104] = 1
        sys.gflags[322] = 1
        sys.gflags[900] = 1

        val restored = SystemData().also { it.loadJson(sys.toJson()) }
        assertEquals(1, restored.gflags[80])
        assertEquals(1, restored.gflags[104])
        assertEquals(1, restored.gflags[322])
        assertEquals(1, restored.gflags[900])
    }

    @Test
    fun `all 63 BGM gallery tracks resolve in BGM archives and follow WA2 exe unlock rules`() {
        val assets = TestAssets.require()
        val tracks = BgmGallery.tracks
        assertEquals(63, tracks.size)
        assertEquals(31, BgmGallery.filter(BgmPage.PAGE_1).size)
        assertEquals(32, BgmGallery.filter(BgmPage.PAGE_2).size)
        assertEquals((1..63).toList(), tracks.map { it.slotNumber })

        val sys = SystemData()
        var introLoopCount = 0
        var singleCount = 0
        for (t in tracks) {
            assertFalse(t.isUnlocked(sys), "Slot #${t.slotNumber} should start locked")
            val ev = BgmGallery.resolvePlayEvent(assets, t, sys)
            assertNotNull(ev, "Slot #${t.slotNumber} (bgmId=${t.bgmId}) failed to resolve in BGM.PAK")
            if (ev!!.intro != null) introLoopCount++ else singleCount++
        }
        println("BGM Gallery: total=${tracks.size} intro+loop=$introLoopCount single=$singleCount")
        assertTrue(introLoopCount > 30 && singleCount > 15)

        // Slot #25 (bgmId=21, Live at Campus Fes) switches to BGM_035 when gflags[135] != 0
        val slot25 = tracks[24]
        assertEquals(21, slot25.bgmId)
        assertEquals(21, slot25.effectiveBgmId(sys))
        sys.gflags[135] = 1
        assertTrue(slot25.isUnlocked(sys))
        assertEquals(35, slot25.effectiveBgmId(sys))
        val ev35 = BgmGallery.resolvePlayEvent(assets, slot25, sys)
        assertNotNull(ev35)
        assertEquals("BGM_035.OGG", ev35!!.loop)

        // Slot #31 (bgmId=34, Full Version) auto-unlocks when IC OP (104) and Coda OP (163) are both unlocked
        val slot31 = tracks[30]
        assertEquals(34, slot31.bgmId)
        assertFalse(slot31.isUnlocked(sys))
        sys.gflags[104] = 1
        assertFalse(slot31.isUnlocked(sys))
        sys.gflags[163] = 1
        assertTrue(slot31.isUnlocked(sys))
        BgmGallery.applyAutoUnlocks(sys)
        assertEquals(1, sys.gflags[134])
    }

    @Test
    fun `all 24 Scene Replay slots have thumbnails and run through 9999 bnr dispatcher`() {
        val assets = TestAssets.require()
        val entries = SceneReplayGallery.entries
        assertEquals(24, entries.size)
        assertEquals(1, SceneReplayGallery.filter(ReplayCategory.IC).size)
        assertEquals(13, SceneReplayGallery.filter(ReplayCategory.CC).size)
        assertEquals(10, SceneReplayGallery.filter(ReplayCategory.CODA).size)

        val sys = SystemData()
        for (entry in entries) {
            assertFalse(entry.isUnlocked(sys))
            assertTrue(assets.exists("grp", entry.thumbFile), "Missing thumbnail ${entry.thumbFile} for slot #${entry.slotNumber}")
            assertTrue(assets.exists("grp", entry.cgFile), "Missing full CG ${entry.cgFile} for slot #${entry.slotNumber}")
        }

        // Verify that starting each of the 24 slots via 9999.bnr dispatches into the target script,
        // produces dialogue lines, and exits via GoTitle() (isEnded = true) at the end of the replay!
        for (entry in entries) {
            val e = Wa2Engine(assets, store)
            assertTrue(e.startReplay(entry.slotNumber, entry.scriptName), "Failed to start replay slot #${entry.slotNumber}")
            assertEquals(entry.slotNumber, e.replayMode)
            val r = HeadlessRunner(e)
            r.run(maxLines = 25)
            assertTrue(
                r.scenesVisited.any { it.equals(entry.scriptName, ignoreCase = true) },
                "Replay slot #${entry.slotNumber} did not reach ${entry.scriptName}: visited=${r.scenesVisited}",
            )
            assertTrue(r.trace.isNotEmpty(), "Replay slot #${entry.slotNumber} produced no dialogue lines")
        }

        // Run Slot #1 (1012_030_2) and Slot #3 (2031 multi-replay script) to completion and verify they terminate via GoTitle()
        for (slot in listOf(1, 3)) {
            val entry = SceneReplayGallery.bySlot(slot)!!
            val e = Wa2Engine(assets, store)
            assertTrue(e.startReplay(slot, entry.scriptName))
            val r = HeadlessRunner(e)
            r.run(maxFrames = 200_000)
            println("Replay #$slot (${entry.scriptName} - ${entry.titleCn}): lines=${r.trace.size} ended=${e.isEnded}")
            assertTrue(r.trace.size > 20, "Replay #$slot lines=${r.trace.size}")
            assertTrue(e.isEnded, "Replay #$slot should finish with GoTitle() (isEnded=true), wait=${e.wait}")
        }
    }
}
