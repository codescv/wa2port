package com.wa2port.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ExplainCacheTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun keyIsStableSha256() {
        // sha256 of `["wa2-v1","m","zh-CN","雪菜","雪だね"]`, pinned so prompt/key changes are deliberate.
        val canonical = "[\"wa2-v1\",\"m\",\"zh-CN\",\"雪菜\",\"雪だね\"]"
        assertEquals("ex_" + ExplainCache.sha256Hex(canonical), ExplainCache.key("雪だね", "雪菜", "m"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ExplainCache.sha256Hex("abc"))
        val k = ExplainCache.key("雪だね", "雪菜", "m")
        assertTrue(k.matches(Regex("ex_[0-9a-f]{64}")))
        assertEquals(k, ExplainCache.key("雪だね", "雪菜", "m"))
        assertNotEquals(k, ExplainCache.key("雪だね", null, "m")) // speaker matters
        assertNotEquals(k, ExplainCache.key("雪だね", "雪菜", "m2")) // model matters
        assertNotEquals(k, ExplainCache.key("雪だね", "雪菜", "m", "wa2-v2")) // prompt version matters
        assertEquals(ExplainCache.key("雪だね", null, "m"), ExplainCache.key("雪だね", "", "m"))
    }

    @Test
    fun putGetPersistence() {
        val e = AiSampleExplanation.build(5L)
        val c = ExplainCache(File(tmp, "nested/cache"))
        assertNull(c.get(e.id))
        c.put(e)
        assertEquals(e.copy(fromCache = true), c.get(e.id))
        // A fresh instance (no memory) reads from disk.
        val c2 = ExplainCache(File(tmp, "nested/cache"))
        val loaded = c2.get(e.id)!!
        assertTrue(loaded.fromCache)
        assertEquals(e, loaded.copy(fromCache = false))
        assertEquals(1, c2.stats().entries)
        assertTrue(c2.stats().bytes > 0)
    }

    @Test
    fun threadAppendOrderAndClear() {
        val c = ExplainCache(tmp)
        val id = "ex_abc"
        c.appendThread(id, FollowUpMessage("1", "user", "q1", 1))
        c.appendThread(id, FollowUpMessage("2", "model", "a1", 2))
        c.appendThread(id, FollowUpMessage("3", "user", "q2", 3))
        assertEquals(listOf("q1", "a1", "q2"), c.thread(id).map { it.text })
        assertEquals(0, c.stats().entries) // threads are not entries
        c.put(AiSampleExplanation.build())
        assertEquals(1, c.stats().entries)
        c.clearThread(id)
        assertEquals(emptyList<FollowUpMessage>(), c.thread(id))
        c.appendThread(id, FollowUpMessage("4", "user", "q", 4))
        assertEquals(2, c.clear())
        assertEquals(CacheStats(0, 0), c.stats())
        assertNull(c.get(AiSampleExplanation.build().id))
    }

    @Test
    fun corruptFilesTolerated() {
        val c = ExplainCache(tmp)
        val e = AiSampleExplanation.build()
        File(tmp, "${e.id}.json").writeText("{ not json")
        File(tmp, "ex_t.thread.json").writeText("[[[")
        assertNull(c.get(e.id))
        assertFalse(File(tmp, "${e.id}.json").exists()) // deleted
        assertEquals(emptyList<FollowUpMessage>(), c.thread("ex_t"))
        // An entry whose id does not match its file name is rejected too.
        File(tmp, "ex_other.json").writeText(ExplainJsonCodec.encodeExplanation(e))
        assertNull(c.get("ex_other"))
    }

    @Test
    fun memoryLruBounded() {
        val c = ExplainCache(tmp)
        val base = AiSampleExplanation.build()
        repeat(ExplainCache.MEMORY_ENTRIES + 5) { c.put(base.copy(id = "ex_$it")) }
        // Evicted from memory but still on disk.
        assertEquals("ex_0", c.get("ex_0")?.id)
        assertEquals(ExplainCache.MEMORY_ENTRIES + 5, c.stats().entries)
    }
}
