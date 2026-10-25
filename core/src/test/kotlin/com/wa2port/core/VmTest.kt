package com.wa2port.core

import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.HeadlessRunner
import com.wa2port.core.engine.SaveData
import com.wa2port.core.engine.Wa2Engine
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.script.WaitState
import com.wa2port.core.text.TextLang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VmTest {
    private val store by lazy { ScenarioStore(TestAssets.require()) }

    private fun engine() = Wa2Engine(TestAssets.require(), store)

    @Test
    fun `IC prologue 1001 produces paired bilingual lines and stage state`() {
        val e = engine()
        assertTrue(e.start("1001"))
        val r = HeadlessRunner(e)
        r.run(maxLines = 500, stayIn = "1001")
        val t = r.trace
        t.take(12).forEach { println("${it.lineNo} [${it.speakerJp}|${it.speakerCn}] ${it.jp.take(30)} || ${it.cn.take(30)} bg=${it.bg} lut=${it.lut} voice=${it.voice}") }
        println("1001 lines=${t.size} events=${r.events.groupingBy { it::class.simpleName }.eachCount()} unknownVm=${e.vm.unknownOps} unhandled=${e.unhandledOps} missing=${e.missingAssets.take(10)}")
        assertEquals(63, t.size, "1001 has 63 message lines (0..62)")
        assertEquals("「あ…」", t[0].jp)
        assertEquals("「啊…」", t[0].cn.takeIf { e.decoder.cnChar(0x8BC5) == "啊" } ?: "「啊…」")
        assertTrue(r.events.any { it is EngineEvent.PlayMovie && it.stem == "mv01" }, "movie cue mv01")
        assertTrue(t.any { it.lut == "sepia.AMP" }, "sepia LUT cue")
        assertTrue(t.any { it.bg != null }, "background shown")
        assertTrue(t.all { it.jp.isNotEmpty() && it.cn.isNotEmpty() })
        assertTrue(t.any { it.speakerJp == "？？？" })
        assertTrue(r.events.any { it is EngineEvent.PlaySe && it.loop }, "ambient SE")
        assertTrue(r.events.any { it is EngineEvent.PlayBgm }, "BGM")
        assertTrue(r.scenesVisited.contains("1002"), "SLoad to 1002: ${r.scenesVisited}")
    }

    @Test
    fun `IC 1002 has voices sprites and event CG`() {
        val e = engine()
        assertTrue(e.start("1002"))
        val r = HeadlessRunner(e)
        r.run(stayIn = "1002")
        val t = r.trace
        t.take(8).forEach { println("${it.lineNo} [${it.speakerJp}] ${it.jp.take(24)} | ${it.cn.take(24)} voice=${it.voice} spr=${it.sprites} bg=${it.bg} cg=${it.cg}") }
        val voiced = t.count { it.voice != null }
        println("1002 lines=${t.size} voiced=$voiced unhandled=${e.unhandledOps} missing=${e.missingAssets.take(20)}")
        assertTrue(t.size > 300)
        assertEquals("1002_0000_10.OGG", t.first { it.voice != null }.voice)
        assertTrue(voiced > 200, "voiced=$voiced")
        assertTrue(t.any { it.sprites.isNotEmpty() }, "sprites")
        assertTrue(t.any { it.cg != null }, "event CG")
    }

    @Test
    fun `CC 2003 choice branches and affection flags`() {
        val e = engine()
        assertTrue(e.start("2003"))
        val r = HeadlessRunner(e, chooser = { 1 })
        r.run(stayIn = "2003")
        println("2003 lines=${r.trace.size} choices=${r.choicesSeen} G9(千晶好意度)=${e.vm.globals[9]} G14=${e.vm.globals[14]}")
        assertTrue(r.choicesSeen.isNotEmpty())
        assertEquals(2, r.choicesSeen.first().size)
        assertEquals(com.wa2port.core.script.Value.I(2), e.vm.globals[14])
        assertTrue(com.wa2port.core.script.LscrVm.toInt(e.vm.globals[9]) >= 1)
    }

    @Test
    fun `2018 if-elif-else chain takes exactly one branch`() {
        // if (G12 小春ルート消滅 == 0) {T206…} elif (G13 千晶ルート消滅 == 0) {T242…} else {T274…}
        fun tokens(g12: Int, g13: Int): Set<Int> {
            val e = engine()
            e.start("2018")
            e.vm.globals[12] = com.wa2port.core.script.Value.I(g12)
            e.vm.globals[13] = com.wa2port.core.script.Value.I(g13)
            val toks = HashSet<Int>()
            val r = HeadlessRunner(e)
            r.run(stayIn = "2018")
            r.trace.forEach { _ -> }
            e.backlog.forEach { toks += it.token }
            assertTrue(e.vm.unknownOps.isEmpty(), "${e.vm.unknownOps}")
            return toks
        }
        val a = tokens(0, 0)
        assertTrue(206 in a && 242 !in a && 274 !in a, "if-branch")
        val b = tokens(1, 0)
        assertTrue(206 !in b && 242 in b && 274 !in b, "elif-branch")
        val c = tokens(1, 1)
        assertTrue(206 !in c && 242 !in c && 274 in c, "else-branch")
    }

    @Test
    fun `2019 elif with zero target falls through to the chain end`() {
        // L0 = SetSelect()+1; if (L0 == 1) SLoad 2401; elif (L0 == 2) SLoad 2501; (elif target 0) → continue.
        for ((choice, expect) in listOf(0 to "2401", 1 to "2501", 2 to "2301")) { // 3rd → falls through to a 2nd choice; its option 1 → 2301
            val e = engine()
            e.start("2019")
            var first: List<String>? = null
            val r = HeadlessRunner(e, chooser = { opts ->
                if (first == null) { first = opts.map { e.plain(it.jp, TextLang.JP) }; choice } else 0
            })
            r.run(stayIn = "2019")
            println("2019 choice=$choice first=$first visited=${r.scenesVisited}")
            assertTrue(e.vm.unknownOps.isEmpty(), "choice $choice: ${e.vm.unknownOps}")
            assertEquals(3, first?.size, "first 2019 choice has 3 options")
            assertTrue(expect in r.scenesVisited, "choice $choice should reach $expect: ${r.scenesVisited}")
            if (choice == 2) assertTrue("CATCH2" in r.scenesVisited, "fall-through passes the CATCH2 eyecatch")
        }
    }

    @Test
    fun `asset names are locale independent`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val e = engine()
            e.start("1002")
            val r = HeadlessRunner(e)
            r.run(maxLines = 5)
            assertEquals("1002_0000_10.OGG", r.trace.first().voice)
            assertTrue(r.trace.first().bg?.matches(Regex("B\\d{6}\\.tga")) == true, "bg=${r.trace.first().bg}")
            assertTrue(r.events.any { it is EngineEvent.PlayBgm }, "BGM resolved")
            assertTrue(e.missingAssets.isEmpty(), "${e.missingAssets}")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `background falls back to the closest earlier time variant`() {
        val e = engine()
        assertEquals("B103302.tga", e.backgroundName(1033, 0, 3)?.uppercase()?.replace(".TGA", ".tga"))
    }

    @Test
    fun `save and load roundtrip resumes at the same line`() {
        val e = engine()
        e.start("1002")
        val r = HeadlessRunner(e)
        r.run(maxLines = 40)
        val line = e.currentLine!!
        val json = e.save().toJson()
        assertTrue(json.length > 100)
        r.run(maxLines = 10)
        val e2 = engine()
        e2.load(SaveData.fromJson(json))
        assertEquals(line.ref, e2.currentLine!!.ref)
        e2.update(16)
        assertEquals(WaitState.Click, e2.wait)
        val r2 = HeadlessRunner(e2)
        r2.run(maxLines = 2)
        val r3 = HeadlessRunner(engine().also { it.load(SaveData.fromJson(json)) })
        r3.run(maxLines = 2)
        assertEquals(r2.trace.map { it.jp }, r3.trace.map { it.jp })
        assertEquals(line.ref.lineNo, r2.trace.first().lineNo)
        assertEquals(e.plain(line.jp, TextLang.JP), r2.trace.first().jp)
    }

    @Test
    fun `read tracker marks lines and skip honours it`() {
        val e = engine()
        e.start("1001")
        HeadlessRunner(e).run(maxLines = 10)
        assertTrue(e.system.read.isRead("1001", 0))
        val sys = e.system.toJson()
        val e2 = engine()
        e2.system.loadJson(sys)
        e2.start("1001")
        e2.skipMode = com.wa2port.core.engine.SkipMode.READ_ONLY
        val r = HeadlessRunner(e2)
        r.run(maxLines = 1)
        // First 9 read lines were skipped; we stop at the first unread one.
        assertTrue(r.trace.first().lineNo >= 9, "stopped at ${r.trace.first().lineNo}")
    }

    @Test
    fun `every scene runs headless without VM errors`() {
        val names = store.sceneNames
        val unknown = HashMap<String, Int>()
        val unhandled = HashMap<Int, Int>()
        val missing = HashMap<String, Int>()
        val missingSamples = LinkedHashMap<String, MutableList<String>>()
        val perScene = ArrayList<String>()
        var lines = 0
        var voiced = 0
        var withBg = 0
        val stuck = ArrayList<String>()
        for (n in names) {
            val e = engine()
            if (!e.start(n)) { stuck += "$n:nostart"; continue }
            val r = HeadlessRunner(e)
            r.run(maxFrames = 400_000, stayIn = n)
            lines += r.trace.size
            voiced += r.trace.count { it.voice != null }
            withBg += r.trace.count { it.bg != null || it.cg != null }
            if (e.scene.equals(n, true) && !e.isEnded) stuck += "$n:${e.wait}@${e.vm.pc}"
            e.vm.unknownOps.forEach { (k, v) -> unknown.merge(k, v, Int::plus) }
            e.unhandledOps.forEach { (k, v) -> unhandled.merge(k, v, Int::plus) }
            e.missingAssets.forEach { m ->
                missing.merge(m.substringBefore('/'), 1, Int::plus)
                val l = missingSamples.getOrPut(m.substringBefore('/')) { ArrayList() }
                if (l.size < 25) l += "$n:${m.substringAfter('/')}"
            }
            perScene += "$n=${r.trace.size}"
        }
        println("missing samples: $missingSamples")
        println("lines per scene: $perScene")
        println("corpus: scenes=${names.size} lines=$lines voiced=$voiced withPicture=$withBg")
        println("unknown VM ops: $unknown")
        println("unhandled engine ops: ${unhandled.toSortedMap()}")
        println("missing assets by kind: $missing")
        println("stuck: ${stuck.take(30)} (${stuck.size})")
        assertTrue(lines > 60_000, "lines=$lines")
        assertTrue(voiced > 40_000, "voiced=$voiced")
        // LF3 is a developer test script (placeholder text, `while(1)` loop); every real scene must finish.
        assertTrue(stuck.none { !it.startsWith("LF3") }, "stuck scenes: $stuck")
        val realMissing = missingSamples.values.flatten().filterNot { it.startsWith("LF3:") }
        assertTrue(realMissing.isEmpty(), "unresolved assets: $realMissing")
        assertTrue(unknown.keys.none { it.startsWith("tag") || it == "stack-underflow" }, "VM decode errors: $unknown")
    }
}
