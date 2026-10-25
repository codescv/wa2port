package com.wa2port.core.image

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoded image, top-down rows, `0xAARRGGBB` pixels.
 *
 * Pixels are **premultiplied** when [premultiplied] is true (the default for every decoder in this
 * package), so they can be composited with plain `ONE, ONE_MINUS_SRC_ALPHA` blending and never need
 * `alpha == 0` cut-out tests (WA2 sprites use α=0 *and* α=2 as their "transparent" values).
 */
class Wa2Image(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val hasAlpha: Boolean,
    val premultiplied: Boolean,
) {
    init {
        require(pixels.size == width * height) { "pixel buffer ${pixels.size} != ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun alphaAt(x: Int, y: Int): Int = pixels[y * width + x] ushr 24

    /** RGBA byte order (as used by Android `ARGB_8888` buffers and GL `RGBA/UNSIGNED_BYTE`). */
    fun toRgbaBytes(): ByteArray {
        val out = ByteArray(pixels.size * 4)
        var o = 0
        for (p in pixels) {
            out[o++] = (p ushr 16).toByte()
            out[o++] = (p ushr 8).toByte()
            out[o++] = p.toByte()
            out[o++] = (p ushr 24).toByte()
        }
        return out
    }

    fun toRgbaBuffer(): ByteBuffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder()).apply {
        put(toRgbaBytes())
        flip()
    }

    /** Returns a copy with straight (non-premultiplied) alpha. */
    fun unpremultiplied(): Wa2Image {
        if (!premultiplied) return this
        val out = IntArray(pixels.size) { unpremultiply(pixels[it]) }
        return Wa2Image(width, height, out, hasAlpha, false)
    }

    /** Copies the [w]×[h] sub-rectangle at ([x], [y]). */
    fun crop(x: Int, y: Int, w: Int, h: Int): Wa2Image {
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val sy = y + row
            if (sy !in 0 until height) continue
            for (col in 0 until w) {
                val sx = x + col
                if (sx in 0 until width) out[row * w + col] = pixels[sy * width + sx]
            }
        }
        return Wa2Image(w, h, out, hasAlpha, premultiplied)
    }

    companion object {
        fun premultiply(argb: Int): Int {
            val a = argb ushr 24
            if (a == 255) return argb
            if (a == 0) return 0
            val r = ((argb ushr 16) and 0xFF) * a + 127
            val g = ((argb ushr 8) and 0xFF) * a + 127
            val b = (argb and 0xFF) * a + 127
            return (a shl 24) or ((r / 255) shl 16) or ((g / 255) shl 8) or (b / 255)
        }

        fun unpremultiply(argb: Int): Int {
            val a = argb ushr 24
            if (a == 255 || a == 0) return argb
            val r = minOf(255, (((argb ushr 16) and 0xFF) * 255 + a / 2) / a)
            val g = minOf(255, (((argb ushr 8) and 0xFF) * 255 + a / 2) / a)
            val b = minOf(255, ((argb and 0xFF) * 255 + a / 2) / a)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
