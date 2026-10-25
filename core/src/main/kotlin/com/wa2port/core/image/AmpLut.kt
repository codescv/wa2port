package com.wa2port.core.image

/**
 * `.AMP` colour-grading lookup tables from `grp.pak` (`WA2.exe` LUT pixel loop 0x438cad).
 *
 * Layouts: 1280 B = five 256-entry tables `T0..T4` where per channel `c' = T0[Tc[c]]` with
 * T1 = R, T2 = G, T3 = B and T0 the master curve applied **after** the channel curve (T4 unused);
 * 256 B = `T0` only (`c' = T0[c]`, e.g. `nega`, `rev*`); 768 B = R, G, B curves (`evening`).
 * Pixels with alpha 0 are left untouched; partially transparent pixels blend the graded colour by
 * alpha (`(lut(c)·a + c·(255−a)) >> 8`, on straight colours).
 */
class AmpLut(val name: String, val tables: List<IntArray>) {
    /** Effective per-channel curves (master already folded in). */
    val red: IntArray
    val green: IntArray
    val blue: IntArray

    init {
        val identity = IntArray(256) { it }
        when {
            tables.size >= 4 -> {
                val m = tables[0]
                red = IntArray(256) { m[tables[1][it]] }
                green = IntArray(256) { m[tables[2][it]] }
                blue = IntArray(256) { m[tables[3][it]] }
            }
            tables.size == 3 -> { red = tables[0]; green = tables[1]; blue = tables[2] }
            tables.isNotEmpty() -> { red = tables[0]; green = tables[0]; blue = tables[0] }
            else -> { red = identity; green = identity; blue = identity }
        }
    }

    /** Applies the LUT to one straight (non-premultiplied) `ARGB` pixel; α = 0 is skipped. */
    fun apply(argb: Int): Int {
        val a = argb ushr 24
        if (a == 0) return argb
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        if (a == 255) return (argb and 0xFF000000.toInt()) or (red[r] shl 16) or (green[g] shl 8) or blue[b]
        val r2 = (red[r] * a + r * (255 - a)) shr 8
        val g2 = (green[g] * a + g * (255 - a)) shr 8
        val b2 = (blue[b] * a + b * (255 - a)) shr 8
        return (a shl 24) or (r2 shl 16) or (g2 shl 8) or b2
    }

    /** Packs the effective curves into a 256×1 RGBA strip (for a GL LUT texture). */
    fun toRgbaStrip(): ByteArray {
        val out = ByteArray(256 * 4)
        for (i in 0 until 256) {
            out[i * 4] = red[i].toByte()
            out[i * 4 + 1] = green[i].toByte()
            out[i * 4 + 2] = blue[i].toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    /** Returns a graded copy of [img] (premultiplied images are unpremultiplied around the lookup). */
    fun applyTo(img: Wa2Image): Wa2Image {
        val out = IntArray(img.pixels.size)
        for (i in out.indices) {
            val p = img.pixels[i]
            out[i] = if (!img.premultiplied || p ushr 24 == 255) apply(p) else if (p ushr 24 == 0) p else {
                Wa2Image.premultiply(apply(Wa2Image.unpremultiply(p)))
            }
        }
        return Wa2Image(img.width, img.height, out, img.hasAlpha, img.premultiplied)
    }

    companion object {
        fun parse(name: String, data: ByteArray): AmpLut {
            require(data.size >= 256 && data.size % 256 == 0) { "$name: bad AMP size ${data.size}" }
            val tables = (0 until data.size / 256).map { t -> IntArray(256) { data[t * 256 + it].toInt() and 0xFF } }
            return AmpLut(name, tables)
        }

        /** Default sprite LUT per `SetTimeMode` when the background has no `B….amp` (.data 0x4be328). */
        fun timeModeDefault(timeMode: Int): String? = when (timeMode) {
            1 -> "evening.AMP"
            2 -> "night.AMP"
            3 -> "indoor.AMP"
            else -> null
        }
    }
}
