package com.wa2port.core

import com.wa2port.core.image.AmpLut
import com.wa2port.core.image.BmpDecoder
import com.wa2port.core.image.TgaDecoder
import com.wa2port.core.image.Wa2Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageTest {
    @Test
    fun `premultiply keeps alpha and scales colour`() {
        assertEquals(0x02020202, Wa2Image.premultiply(0x02FFFFFF)) // α=2 white → near-transparent
        assertEquals(0, Wa2Image.premultiply(0x00FFFFFF))
        val p = Wa2Image.premultiply(0x80FF0000.toInt())
        assertEquals(0x80, p ushr 24)
        assertEquals(0x80, (p ushr 16) and 0xFF)
    }

    @Test
    fun `bpp0 char sprites decode as premultiplied 32-bit`() {
        val assets = TestAssets.require()
        val char = assets.kcap("char")!!
        var bpp0 = 0
        var checked = 0
        for (e in char.files.filter { it.name.endsWith(".tga", true) }.take(400)) {
            val head = char.readHead(e, 18)
            val bpp = head[16].toInt() and 0xFF
            if (bpp != 0) continue
            bpp0++
            if (checked >= 12) continue
            val img = TgaDecoder.decode(char.read(e))
            checked++
            assertTrue(img.hasAlpha && img.premultiplied, e.name)
            assertTrue(img.width in 100..1280 && img.height in 300..720, "${e.name} ${img.width}x${img.height}")
            // Top-left corner is transparent background (α=0 or α=2) → premultiplied colour ≤ alpha.
            val c = img[0, 0]
            val a = c ushr 24
            assertTrue(a <= 8, "${e.name}: corner alpha $a")
            assertTrue(((c ushr 16) and 0xFF) <= a && (c and 0xFF) <= a, "${e.name}: not premultiplied")
            // Somewhere in the middle the character is opaque.
            assertTrue(img.alphaAt(img.width / 2, img.height / 2) > 200, "${e.name}: centre transparent")
        }
        println("char.pak bpp=0 sprites in sample: $bpp0, decoded $checked")
        assertTrue(bpp0 > 100)
    }

    @Test
    fun `kaz027100 alpha-2 background premultiplies to near-zero`() {
        val assets = TestAssets.require()
        val data = assets.read("char", "kaz027100.tga") ?: return
        val img = TgaDecoder.decode(data)
        val straight = TgaDecoder.decode(data, premultiply = false)
        assertEquals(2, straight.alphaAt(0, 0))
        val c = img[0, 0]
        assertTrue(((c ushr 16) and 0xFF) <= 2 && ((c ushr 8) and 0xFF) <= 2 && (c and 0xFF) <= 2)
    }

    @Test
    fun `backgrounds masks luts and font atlases decode`() {
        val assets = TestAssets.require()
        val bg = TgaDecoder.decode(assets.read("bak", "b100400.tga")!!)
        assertEquals(1280, bg.width); assertEquals(720, bg.height)
        val mask = BmpDecoder.maskLevels(assets.read("grp", "f0003.bmp")!!)
        assertEquals(1280, mask.width); assertEquals(720, mask.height)
        val levels = HashSet<Int>()
        for (y in 0 until 720 step 37) for (x in 0 until 1280 step 41) levels += mask.level(x, y)
        assertTrue(levels.size > 8, "mask has too few grey levels: ${levels.size}")
        val cg = TgaDecoder.decode(assets.read("grp", "v100400.tga")!!)
        assertTrue(cg.width >= 1280)
        val lut = AmpLut.parse("sepia.AMP", assets.read("grp", "sepia.AMP")!!)
        assertEquals(5, lut.tables.size)
        val nega = AmpLut.parse("nega.AMP", assets.read("grp", "nega.AMP")!!)
        assertEquals(0xFF00FFFF.toInt(), nega.apply(0xFFFF0000.toInt()))
        for ((pak, name) in listOf("fon" to "本体80.tga", "fon" to "袋影80.tga", "fnt" to "本体80.tga")) {
            val data = assets.read(pak, name) ?: error("$pak/$name missing")
            val atlas = TgaDecoder.decodeIndexed(data)
            assertEquals(3200, atlas.width, "$pak/$name")
            assertEquals(3840, atlas.height, "$pak/$name")
        }
        // 14pt atlases (24-px cells).
        val names = assets.kcap("fon")!!.files.map { it.name }
        println("fon.pak: $names")
        println("fnt.pak: ${assets.kcap("fnt")!!.files.map { it.name }}")
    }

    @Test
    fun `loose grp override wins over grp pak`() {
        val assets = TestAssets.require()
        val loose = java.io.File(assets.root, "grp/sys_00011.tga")
        if (!loose.isFile) return
        assertTrue(assets.read("grp", "sys_00011.tga")!!.contentEquals(loose.readBytes()))
    }
}
