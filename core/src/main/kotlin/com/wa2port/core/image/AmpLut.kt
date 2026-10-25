package com.wa2port.core.image

/**
 * `.AMP` colour-grading lookup tables from `grp.pak`.
 *
 * Observed sizes: 256 B (one curve applied to R, G and B: `nega`, `rev*`), 768 B (R, G, B curves:
 * `evening`) and 1280 B (five curves). For the 5-curve form the corpus shows curve 0 behaving like a
 * master/luma curve and curves 1..3 like R, G, B (sepia: R>G>B; night: B lifted), curve 4 identity in
 * every file — we apply `master` first and then the per-channel curves. `sepia*` tables additionally
 * desaturate (the engine's flashback filter is monochrome).
 */
class AmpLut(val name: String, val tables: List<IntArray>) {
    val master: IntArray? = if (tables.size >= 5) tables[0] else null
    val red: IntArray = when {
        tables.size >= 5 -> tables[1]
        tables.size >= 3 -> tables[0]
        else -> tables[0]
    }
    val green: IntArray = when {
        tables.size >= 5 -> tables[2]
        tables.size >= 3 -> tables[1]
        else -> tables[0]
    }
    val blue: IntArray = when {
        tables.size >= 5 -> tables[3]
        tables.size >= 3 -> tables[2]
        else -> tables[0]
    }
    val monochrome: Boolean = name.lowercase().startsWith("sepia")

    /** Applies the LUT to one straight or premultiplied opaque `ARGB` pixel (alpha is preserved). */
    fun apply(argb: Int): Int {
        var r = (argb ushr 16) and 0xFF
        var g = (argb ushr 8) and 0xFF
        var b = argb and 0xFF
        if (monochrome) {
            val y = (r * 77 + g * 150 + b * 29) shr 8
            r = y; g = y; b = y
        }
        master?.let { m -> r = m[r]; g = m[g]; b = m[b] }
        return (argb and 0xFF000000.toInt()) or (red[r] shl 16) or (green[g] shl 8) or blue[b]
    }

    /** Packs the effective curves into a 256×1 RGBA strip (for a GL LUT texture). */
    fun toRgbaStrip(): ByteArray {
        val out = ByteArray(256 * 4)
        for (i in 0 until 256) {
            val m = master?.get(i) ?: i
            out[i * 4] = red[m].toByte()
            out[i * 4 + 1] = green[m].toByte()
            out[i * 4 + 2] = blue[m].toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    fun applyTo(img: Wa2Image): Wa2Image {
        val out = IntArray(img.pixels.size)
        for (i in out.indices) {
            val p = img.pixels[i]
            val a = p ushr 24
            out[i] = if (a == 255 || !img.premultiplied) apply(p) else if (a == 0) 0 else {
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
    }
}
