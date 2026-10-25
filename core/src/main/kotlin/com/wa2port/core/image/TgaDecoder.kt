package com.wa2port.core.image

/**
 * Decoder for the uncompressed / RLE TGA variants found in WA2 archives.
 *
 * Supported: type 1 (8-bpp colour-mapped), 2 (true colour 15/16/24/32 bpp), 3 (8-bpp grey) and their
 * RLE counterparts 9/10/11, bottom-up or top-down origin.
 *
 * WA2 quirk: 1097 of 1292 `char.pak` sprites declare `bpp = 0`; the real depth (32-bpp BGRA) is
 * inferred from the payload length. Alpha in sprites is *straight*; [decode] premultiplies it.
 */
object TgaDecoder {
    data class Header(
        val idLength: Int,
        val colorMapType: Int,
        val imageType: Int,
        val cmFirst: Int,
        val cmLength: Int,
        val cmBpp: Int,
        val width: Int,
        val height: Int,
        val bpp: Int,
        val descriptor: Int,
    ) {
        val topDown: Boolean get() = descriptor and 0x20 != 0
        val rightToLeft: Boolean get() = descriptor and 0x10 != 0
        val isRle: Boolean get() = imageType >= 9
        val baseType: Int get() = if (imageType >= 9) imageType - 8 else imageType
    }

    fun header(data: ByteArray): Header {
        require(data.size >= 18) { "TGA too short (${data.size} B)" }
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        return Header(u8(0), u8(1), u8(2), u16(3), u16(5), u8(7), u16(12), u16(14), u8(16), u8(17))
    }

    /** Decodes to `ARGB` pixels (premultiplied when [premultiply]). */
    fun decode(data: ByteArray, premultiply: Boolean = true): Wa2Image {
        val h = header(data)
        val w = h.width
        val ht = h.height
        require(w > 0 && ht > 0) { "TGA bad size ${w}x$ht" }
        var pos = 18 + h.idLength
        // Colour map.
        var palette: IntArray? = null
        if (h.colorMapType == 1) {
            val entryBytes = (h.cmBpp + 7) / 8
            palette = IntArray(h.cmFirst + h.cmLength)
            for (i in 0 until h.cmLength) {
                palette[h.cmFirst + i] = readColor(data, pos + i * entryBytes, h.cmBpp)
            }
            pos += h.cmLength * entryBytes
        }
        val n = w * ht
        var bpp = h.bpp
        if (bpp == 0) {
            // char.pak quirk: infer bytes-per-pixel from the payload size.
            bpp = ((data.size - pos).toLong() * 8 / n).toInt()
            bpp = when {
                bpp >= 32 -> 32
                bpp >= 24 -> 24
                bpp >= 16 -> 16
                else -> 8
            }
        }
        val bytesPP = (bpp + 7) / 8
        val raw: ByteArray = if (h.isRle) unRle(data, pos, n, bytesPP) else {
            val need = n * bytesPP
            if (pos + need <= data.size) data.copyOfRange(pos, pos + need)
            else ByteArray(need).also { System.arraycopy(data, pos, it, 0, (data.size - pos).coerceAtLeast(0)) }
        }
        val out = IntArray(n)
        val base = h.baseType
        var hasAlpha = false
        for (i in 0 until n) {
            val c = when {
                base == 1 -> palette?.getOrNull(raw[i].toInt() and 0xFF) ?: 0xFF000000.toInt()
                base == 3 -> (raw[i].toInt() and 0xFF).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                else -> readColor(raw, i * bytesPP, bpp)
            }
            out[i] = c
        }
        if (bpp == 32 || (base == 1 && h.cmBpp == 32)) {
            hasAlpha = true
        }
        val pixels = orient(out, w, ht, h.topDown, h.rightToLeft)
        if (hasAlpha && premultiply) {
            for (i in pixels.indices) pixels[i] = Wa2Image.premultiply(pixels[i])
        }
        return Wa2Image(w, ht, pixels, hasAlpha, premultiplied = premultiply || !hasAlpha)
    }

    /**
     * Decodes an 8-bpp colour-mapped/grey TGA without expanding it (font atlases are 3200×3840;
     * expanding them to ARGB would cost ~49 MB each on a phone).
     */
    fun decodeIndexed(data: ByteArray): IndexedImage {
        val h = header(data)
        require(h.baseType == 1 || h.baseType == 3) { "not an 8-bpp indexed TGA (type ${h.imageType})" }
        var pos = 18 + h.idLength
        val palette = IntArray(256) { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        if (h.colorMapType == 1) {
            val entryBytes = (h.cmBpp + 7) / 8
            for (i in 0 until h.cmLength) {
                val idx = h.cmFirst + i
                if (idx < 256) palette[idx] = readColor(data, pos + i * entryBytes, h.cmBpp)
            }
            pos += h.cmLength * entryBytes
        }
        val n = h.width * h.height
        val raw = if (h.isRle) unRle(data, pos, n, 1) else data.copyOfRange(pos, minOf(data.size, pos + n))
            .let { if (it.size < n) it.copyOf(n) else it }
        val rows = if (h.topDown) raw else {
            val flipped = ByteArray(n)
            for (y in 0 until h.height) {
                System.arraycopy(raw, (h.height - 1 - y) * h.width, flipped, y * h.width, h.width)
            }
            flipped
        }
        return IndexedImage(h.width, h.height, rows, palette)
    }

    private fun readColor(b: ByteArray, i: Int, bpp: Int): Int {
        if (i < 0 || i + (bpp + 7) / 8 > b.size) return 0
        return when (bpp) {
            32 -> ((b[i + 3].toInt() and 0xFF) shl 24) or ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
            24 -> (0xFF shl 24) or ((b[i + 2].toInt() and 0xFF) shl 16) or
                ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i].toInt() and 0xFF)
            15, 16 -> {
                val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
                val r = (v shr 10) and 0x1F
                val g = (v shr 5) and 0x1F
                val bl = v and 0x1F
                (0xFF shl 24) or ((r * 255 / 31) shl 16) or ((g * 255 / 31) shl 8) or (bl * 255 / 31)
            }
            8 -> (b[i].toInt() and 0xFF).let { g -> (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
            else -> 0
        }
    }

    private fun unRle(data: ByteArray, start: Int, pixels: Int, bpp: Int): ByteArray {
        val out = ByteArray(pixels * bpp)
        var p = start
        var o = 0
        while (o < out.size && p < data.size) {
            val hdr = data[p++].toInt() and 0xFF
            val count = (hdr and 0x7F) + 1
            if (hdr and 0x80 != 0) {
                if (p + bpp > data.size) break
                repeat(count) {
                    if (o + bpp <= out.size) System.arraycopy(data, p, out, o, bpp)
                    o += bpp
                }
                p += bpp
            } else {
                val len = minOf(count * bpp, out.size - o, data.size - p)
                System.arraycopy(data, p, out, o, len)
                o += count * bpp
                p += count * bpp
            }
        }
        return out
    }

    private fun orient(src: IntArray, w: Int, h: Int, topDown: Boolean, rtl: Boolean): IntArray {
        if (topDown && !rtl) return src
        val out = IntArray(src.size)
        for (y in 0 until h) {
            val sy = if (topDown) y else h - 1 - y
            if (!rtl) {
                System.arraycopy(src, sy * w, out, y * w, w)
            } else {
                for (x in 0 until w) out[y * w + x] = src[sy * w + (w - 1 - x)]
            }
        }
        return out
    }
}

/** An 8-bit indexed image with a 256-entry `ARGB` palette (top-down rows). */
class IndexedImage(val width: Int, val height: Int, val indices: ByteArray, val palette: IntArray) {
    fun index(x: Int, y: Int): Int = indices[y * width + x].toInt() and 0xFF

    /** Grey level (red channel of the palette entry) at ([x], [y]). */
    fun level(x: Int, y: Int): Int = (palette[index(x, y)] ushr 16) and 0xFF
}
