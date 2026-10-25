package com.wa2port.core

import com.wa2port.core.script.LscrProgram
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.text.ScenarioTextParser
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.Wa2FontMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScenarioTest {
    private val store by lazy { ScenarioStore(TestAssets.require()) }

    @Test
    fun `font map anchors`() {
        assertEquals(0, Wa2FontMap.cellOf(0x20))
        assertEquals(139, Wa2FontMap.cellOf(0x8140))
        assertEquals(140, Wa2FontMap.cellOf(0x8141))
        assertEquals(748, Wa2FontMap.cellOf(0x889F)) // 亜 / 一
        assertEquals(842, Wa2FontMap.cellOf(0x8940))
        assertEquals(905, Wa2FontMap.cellOf(0x897F)) // CN 使
        assertEquals(1350, Wa2FontMap.cellOf(0x8BC2)) // 仰
        assertEquals(1353, Wa2FontMap.cellOf(0x8BC5)) // 暁 / 啊
        assertEquals(3727, Wa2FontMap.cellOf(0x9872)) // 腕
        assertEquals(3728, Wa2FontMap.cellOf(0x989F)) // 弌
        assertEquals(-1, Wa2FontMap.cellOf(0x9880))
        assertEquals(7135, Wa2FontMap.cellOf(0xEAA4)) // 熙
        assertEquals(7265, Wa2FontMap.cellOf(0xFA40))
        assertEquals(7654, Wa2FontMap.cellOf(0xFC4B))
        assertEquals(0x8BC5, Wa2FontMap.codeOf(1353))
        assertEquals(7199, Wa2FontMap.cellOf(0xF07F))
        assertEquals(7264, Wa2FontMap.cellOf(0xF0C0))
        assertEquals(94, Wa2FontMap.cellOf(0xB1)) // half-width kana via 0xFF00|b
        assertEquals(-1, Wa2FontMap.cellOf(0x09)) // stray ck-gal single bytes draw nothing
        assertEquals(-1, Wa2FontMap.cellOf(0x7E))
        assertEquals(-1, Wa2FontMap.cellOf(0x7F))
        assertEquals(7655, Wa2FontMap.mappedCells)
        assertEquals(0x817F, Wa2FontMap.codeOf(Wa2FontMap.cellOf(0x817F)))
        assertEquals(747, Wa2FontMap.cellOf(0x878F))
    }

    @Test
    fun `cp932 extensions decode`() {
        val d = com.wa2port.core.text.BilingualTextDecoder()
        assertEquals("\uFF5E", d.jpChar(0x8160))
        assertEquals("①", d.jpChar(0x8740))
        assertEquals("亜", d.jpChar(0x889F))
        assertEquals("ｱ", d.jpChar(0xB1))
        assertEquals("", d.jpChar(0x09))
    }

    @Test
    fun `ruby and markup parse sjis-aware`() {
        val cp = com.wa2port.core.archive.Cp932.charset
        val t = ScenarioTextParser.parse("<R空港|ここ>に着いた\\nよ<W120>。<F14「小声」>".toByteArray(cp))
        val ruby = t.items.first() as TextItem.Ruby
        assertEquals("空港", String(ruby.base.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray(), cp))
        assertTrue(t.items.contains(TextItem.NewLine))
        assertTrue(t.items.any { it is TextItem.Wait && it.value == 120 })
        assertTrue(t.items.any { it is TextItem.Run && it.size == 14 })
        // Trail byte 0x7C ('|') and 0x5C ('\') inside double-byte chars are not markup.
        val tricky = byteArrayOf(0x95.toByte(), 0x5C, 0x83.toByte(), 0x7C)
        assertEquals(2, ScenarioTextParser.parse(tricky).baseCodes().size)
    }

    @Test
    fun `all 187 scenes parse with exact JP CN token alignment`() {
        val names = store.sceneNames
        println("scenes: ${names.size} first=${names.take(5)} last=${names.takeLast(5)}")
        assertEquals(187, names.size)
        var tokens = 0
        var refs = 0
        var cnCodes = 0
        var unmapped = 0
        var code7f = 0
        val badRefs = ArrayList<String>()
        for (n in names) {
            val sc = store.scene(n)!!
            val cn = sc.cn!!
            assertEquals(sc.jp.size, cn.size, "token count mismatch in $n")
            tokens += sc.jp.size
            // Every (3,X) text reference in the .bnr addresses a real token in both languages.
            val w = sc.program.words
            var i = sc.program.headerBytes / 4
            while (i < w.size) {
                when (w[i]) {
                    5 -> i += 3
                    3 -> {
                        refs++
                        if (w[i + 1] !in 0 until sc.jp.size) badRefs += "$n:${w[i + 1]}"
                        i += 2
                    }
                    1, 2, 4, 6 -> i += 2
                    0 -> i += LscrProgram.controlLength(w, i)
                    else -> error("$n: unknown tag ${w[i]} at word $i")
                }
            }
            // Every CN double-byte code maps to an atlas cell.
            for (k in 0 until cn.size) {
                for (c in cn.rich(k).baseCodes()) {
                    if (c < 0x100) continue
                    cnCodes++
                    if ((c and 0xFF) == 0x7F) code7f++
                    if (Wa2FontMap.cellOf(c) < 0) unmapped++
                }
            }
        }
        println("tokens=$tokens textRefs=$refs cnCodes=$cnCodes cn0x7F=$code7f unmapped=$unmapped badRefs=${badRefs.take(10)}")
        assertTrue(badRefs.isEmpty(), "bad token refs: ${badRefs.take(20)}")
        assertTrue(code7f > 6000, "expected the ~6564 0x??7F CN codes, got $code7f")
        assertEquals(0, unmapped, "CN codes without an atlas cell")
    }

    @Test
    fun `function tables and globals load`() {
        val f = store.functions
        assertEquals("SLoad", f[0]?.name)
        assertEquals("SetMessageE", f[131]?.name)
        assertEquals("SetSelect", f[209]?.name)
        assertEquals("GetSkip2", f[238]?.name)
        assertEquals(9, f[146]?.argc)
        assertEquals(30, (f[146]?.defaults?.get(3) as com.wa2port.core.script.Value.I).v)
        assertEquals(29, store.globalVars.size)
        assertEquals("FLG_かずさ本気度", store.globalVars[5].name)
        assertEquals(62, store.gflagNames.size)
        assertTrue(store.gflagNames.all { it.startsWith("_GFLAG") }, store.gflagNames.take(3).toString())
    }
}
