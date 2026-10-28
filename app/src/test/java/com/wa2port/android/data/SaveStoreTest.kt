package com.wa2port.android.data

import com.wa2port.core.engine.AudioState
import com.wa2port.core.engine.SaveData
import com.wa2port.core.engine.StageState
import com.wa2port.core.script.VmSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SaveStoreTest {
    @TempDir
    lateinit var dir: File

    private fun data(ts: Long, scene: String = "1001") = SaveData(
        timestamp = ts,
        scene = scene,
        vm = VmSnapshot(scene, 0, emptyList(), emptyList(), emptyList(), emptyMap(), null),
        stage = StageState(),
        audio = AudioState(),
    )

    @Test
    fun reservedSlotsMapToTheirOwnFiles() {
        val store = SaveStore(dir)
        assertEquals("auto.json", store.file(SaveStore.AUTO_SLOT).name)
        assertEquals("undo.json", store.file(SaveStore.UNDO_SLOT).name)
        assertEquals("quick.json", store.file(SaveStore.QUICK_SLOT).name)
        assertEquals("slot_07.json", store.file(7).name)
    }

    @Test
    fun latestContinuableIgnoresUndoEvenWhenNewest() = runBlocking {
        val store = SaveStore(dir)
        assertNull(store.latestContinuable())
        store.write(3, data(100))
        store.write(SaveStore.AUTO_SLOT, data(300, "2001"))
        store.write(SaveStore.QUICK_SLOT, data(200))
        store.write(SaveStore.UNDO_SLOT, data(999))
        val latest = store.latestContinuable()
        assertEquals(SaveStore.AUTO_SLOT, latest?.slot)
        assertEquals("2001", latest?.scene)
        assertEquals(SaveStore.AUTO_SLOT, store.readAuto()?.slot)
        assertFalse(SaveStore.UNDO_SLOT in store.listMeta())
    }

    @Test
    fun latestContinuablePicksManualSlotWhenNewer() = runBlocking {
        val store = SaveStore(dir)
        store.write(SaveStore.AUTO_SLOT, data(100))
        store.write(12, data(500))
        assertEquals(12, store.latestContinuable()?.slot)
    }

    @Test
    fun quickSaveKeepsPreviousForUndo() = runBlocking {
        val store = SaveStore(dir)
        store.write(SaveStore.QUICK_SLOT, data(1, "1001"))
        store.write(SaveStore.QUICK_SLOT, data(2, "1002"))
        assertEquals("1002", store.read(SaveStore.QUICK_SLOT)?.scene)
        val restored = store.restoreQuickPrev()
        assertEquals("1001", restored?.scene)
        assertEquals("1001", store.read(SaveStore.QUICK_SLOT)?.scene)
        assertFalse(File(dir, "quick.prev.json").exists())
    }

    @Test
    fun undoingTheFirstQuickSaveRemovesIt() = runBlocking {
        val store = SaveStore(dir)
        store.write(SaveStore.QUICK_SLOT, data(1))
        assertTrue(store.exists(SaveStore.QUICK_SLOT))
        assertNull(store.restoreQuickPrev())
        assertFalse(store.exists(SaveStore.QUICK_SLOT))
    }

    @Test
    fun lockedSlotPreventsOverwriteAndDelete() = runBlocking {
        val store = SaveStore(dir)
        assertTrue(store.write(5, data(100, "1001")))
        assertFalse(store.isLocked(5))

        store.setLocked(5, true)
        assertTrue(store.isLocked(5))
        assertEquals("slot_05.lock", store.lockFile(5).name)
        assertEquals(true, store.listMeta()[5]?.locked)

        // Overwrite and delete are rejected while locked.
        assertFalse(store.write(5, data(200, "2001")))
        assertFalse(store.delete(5))
        assertEquals("1001", store.read(5)?.scene)

        // Quick slot also supports locking via quick.lock.
        assertTrue(store.write(SaveStore.QUICK_SLOT, data(150, "1002")))
        store.setLocked(SaveStore.QUICK_SLOT, true)
        assertEquals("quick.lock", store.lockFile(SaveStore.QUICK_SLOT).name)
        assertFalse(store.write(SaveStore.QUICK_SLOT, data(250, "2002")))
        assertFalse(store.delete(SaveStore.QUICK_SLOT))

        // Reserved negative slots ignore locking.
        store.setLocked(SaveStore.AUTO_SLOT, true)
        assertFalse(store.isLocked(SaveStore.AUTO_SLOT))
        assertTrue(store.write(SaveStore.AUTO_SLOT, data(300, "1003")))

        // Unlocking restores normal write and delete.
        store.setLocked(5, false)
        assertFalse(store.isLocked(5))
        assertTrue(store.write(5, data(400, "2001")))
        assertEquals("2001", store.read(5)?.scene)
        assertTrue(store.delete(5))
        assertFalse(store.exists(5))
    }

    @Test
    fun displayLangToggleJpBoth() {
        assertEquals(DisplayLang.JP, DisplayLang.BOTH.toggleJpBoth())
        assertEquals(DisplayLang.BOTH, DisplayLang.JP.toggleJpBoth())
        assertEquals(DisplayLang.BOTH, DisplayLang.CN.toggleJpBoth())
    }
}
