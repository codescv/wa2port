package com.wa2port.core.image

/**
 * Decoder for the Windows BMP files in `grp.pak` (dissolve masks `f0%03d.bmp`, UI bitmaps).
 *
 * Supports `BITMAPINFOHEADER`+ with BI_RGB 8-bpp palettized, 24-bpp and 32-bpp, plus BI_RLE8,
 * bottom-up and top-down. Masks are 1280×720 8-bpp grey ramps; [maskLevels] extracts the grey value
 * per pixel (0..255) which drives the pattern dissolve (`pf0x.psh`).
 */
object BmpDecoder {
    fun decode(data: ByteArray): Wa2Image {
        val d = parse(data)
        val px = IntArray(d.width * d.height) { i ->
            val c = d.pixel(i)
            c
        }
        return Wa2Image(d.width, d.height, px, hasAlpha = false, premultiplied = true)
    }

    /** Grey value (max of R,G,B → for grey ramps all equal) per pixel, top-down. */
    fun maskLevels(data: ByteArray): IndexedImage {
        val d = parse(data)
        val out = ByteArray(d.width * d.height)
        for (i in out.indices) {
            val c = d.pixel(i)
            out[i] = ((c ushr 16) and 0xFF).toByte()
        }
        val pal = IntArray(256) { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        return IndexedImage(d.width, d.height, out, pal)
    }

    private class Decoded(val width: Int, val height: Int, val argb: IntArray) {
        fun pixel(i: Int) = argb[i]
    }

    private fun parse(data: ByteArray): Decoded {
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun s32(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)
        require(data.size >= 54 && data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte()) { "not a BMP" }
        val dataOff = s32(10)
        val hdrSize = s32(14)
        val w = s32(18)
        val rawH = s32(22)
        val bpp = u16(28)
        val compression = s32(30)
        var colors = s32(46)
        val topDown = rawH < 0
        val h = kotlin.math.abs(rawH)
        require(w > 0 && h > 0) { "BMP bad size" }
        val palOff = 14 + hdrSize
        if (bpp <= 8 && colors == 0) colors = 1 shl bpp
        val palette = IntArray(256)
        for (i in 0 until minOf(colors, 256)) {
            val p = palOff + i * 4
            if (p + 3 < data.size) palette[i] = (0xFF shl 24) or (u8(p + 2) shl 16) or (u8(p + 1) shl 8) or u8(p)
        }
        val out = IntArray(w * h)
        fun put(x: Int, row: Int, c: Int) {
            val y = if (topDown) row else h - 1 - row
            if (x in 0 until w && y in 0 until h) out[y * w + x] = c
        }
        if (compression == 1 && bpp == 8) {
            // BI_RLE8.
            var p = dataOff
            var x = 0
            var row = 0
            loop@ while (p + 1 < data.size) {
                val a = u8(p)
                val b = u8(p + 1)
                p += 2
                if (a > 0) {
                    repeat(a) { put(x++, row, palette[b]) }
                } else when (b) {
                    0 -> { x = 0; row++ }
                    1 -> break@loop
                    2 -> { x += u8(p); row += u8(p + 1); p += 2 }
                    else -> {
                        for (k in 0 until b) put(x++, row, palette[u8(p + k)])
                        p += b + (b and 1)
                    }
                }
            }
            return Decoded(w, h, out)
        }
        val stride = ((w * bpp + 31) / 32) * 4
        for (row in 0 until h) {
            val base = dataOff + row * stride
            for (x in 0 until w) {
                val c = when (bpp) {
                    8 -> if (base + x < data.size) palette[u8(base + x)] else 0
                    24 -> {
                        val p = base + x * 3
                        if (p + 2 < data.size) (0xFF shl 24) or (u8(p + 2) shl 16) or (u8(p + 1) shl 8) or u8(p) else 0
                    }
                    32 -> {
                        val p = base + x * 4
                        if (p + 3 < data.size) (0xFF shl 24) or (u8(p + 2) shl 16) or (u8(p + 1) shl 8) or u8(p) else 0
                    }
                    else -> 0
                }
                put(x, row, c)
            }
        }
        return Decoded(w, h, out)
    }
}
