package com.wa2port.core

import com.wa2port.core.archive.KcapArchive
import com.wa2port.core.archive.LacArchive
import com.wa2port.core.archive.OkumuraLzss
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchiveTest {
    @Test
    fun `lzss decodes literals and back references`() {
        // flags 0b11111110 → item0 = backref, items 1..7 literals; ring is zero-filled.
        val src = byteArrayOf(0xFE.toByte(), 0xEE.toByte(), 0xF0.toByte(), 'a'.code.toByte(), 'b'.code.toByte())
        val out = OkumuraLzss.decompress(src, 0, src.size, 5)
        assertArrayEquals(byteArrayOf(0, 0, 0, 'a'.code.toByte(), 'b'.code.toByte()), out)
    }

    @Test
    fun `all 47 install archives open and parse`() {
        val assets = TestAssets.require()
        val paks = assets.allPakFiles()
        var kcap = 0
        var lac = 0
        var asf = 0
        val report = StringBuilder()
        for (f in paks) {
            when {
                KcapArchive.isKcap(f) -> KcapArchive(f).use { a ->
                    kcap++
                    assertTrue(a.files.isNotEmpty(), "${f.name} has no files")
                    // Decompress the first and last real file to prove offsets/LZSS are sane.
                    for (e in listOf(a.files.first(), a.files.last())) {
                        val data = a.read(e)
                        assertTrue(data.isNotEmpty() || e.size == 0, "${f.name}/${e.name} empty")
                    }
                    report.append("KCAP ${f.parentFile.name}/${f.name}: ${a.files.size} files\n")
                }
                LacArchive.isLac(f) -> LacArchive(f).use { a ->
                    lac++
                    assertTrue(a.entries.isNotEmpty())
                    val last = a.entries.maxBy { it.offset }
                    assertTrue(last.offset + last.size <= f.length(), "${f.name}: entry past EOF")
                    val magic = a.read(a.entries.first()).copyOf(4).toString(Charsets.ISO_8859_1)
                    assertTrue(magic == "OggS" || magic == "RIFF", "${f.name}: payload magic $magic")
                    report.append("LAC  ${f.parentFile.name}/${f.name}: ${a.entries.size} entries\n")
                }
                else -> {
                    // Movies: raw ASF (GUID 30 26 B2 75 …).
                    val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
                    assertArrayEquals(byteArrayOf(0x30, 0x26, 0xB2.toByte(), 0x75), head, "${f.name} is not ASF")
                    asf++
                }
            }
        }
        println(report)
        println("archives: kcap=$kcap lac=$lac asf=$asf total=${paks.size}")
        // 47 archives listed in INSTALL.INF + CK-GAL's ck-gal.pak and fon.pak.
        assertTrue(paks.size >= 47, "expected ≥47 archives, found ${paks.size}")
        assertEquals(6, lac, "BGM/SE/VOICE + IC copies")
    }

    @Test
    fun `ck-gal csize quirk is handled by decompressing to usize`() {
        val assets = TestAssets.require()
        val cn = assets.kcap("ck-gal")!!
        val e = cn.find("1001.txt")!!
        val data = cn.read(e)
        // Ends with ",1002," + "" tokens: the last 8 bytes would be lost if csize were trusted.
        val tail = String(data, data.size - 8, 8, Charsets.ISO_8859_1)
        assertTrue(tail.contains("1002"), "tail='$tail'")
    }

    @Test
    fun `lac readers expose audio`() {
        val assets = TestAssets.require()
        assertEquals(108, assets.lac("BGM")!!.entries.size)
        assertEquals(1132, assets.lac("SE")!!.entries.size)
        assertEquals(38736, assets.lac("VOICE")!!.entries.size)
        assertEquals(7271, assets.lac("VOICE", ic = true)!!.entries.size)
        val v = assets.audio("VOICE", "1002_0000_10.OGG")!!
        assertTrue(v.file.name.equals("VOICE.PAK", true) && v.file.parentFile.name == "IC")
        assertEquals("OggS", String(assets.readAudio("BGM", "BGM_001_A.OGG")!!, 0, 4, Charsets.ISO_8859_1))
        assertEquals("RIFF", String(assets.readAudio("SE", "SE_0000.WAV")!!, 0, 4, Charsets.ISO_8859_1))
    }
}
