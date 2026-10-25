package com.wa2port.core.text

/**
 * `WA2.exe` code → glyph-atlas cell index (lifted from routine `0x401050`, range table B at
 * `.rdata 0x4a14b8`: 59 `{lo, hi}` ranges, 7655 cells; verified against every JP/CN code and the
 * `fnt.pak` / `fon.pak` atlases by @designer, @critic and @fontmap).
 *
 * Both the JP (`fnt.pak`) and CN (`fon.pak`) atlases share this layout; CK-GAL replaced only the
 * glyph *pictures*, so CN text bytes are pseudo-SJIS indices into the CN atlas. Atlas position:
 * `col = cell % 80`, `row = cell / 80`, 40-px cells in `本体80.tga`/`袋影80.tga` (3200×3840) or 24-px
 * cells in `14pt本体.tga`/`14pt袋.tga` (1920×2304).
 *
 * Code construction: double-byte pair → `lead shl 8 or trail` (trail 0x7F is valid); single byte
 * `b` → `b` for `b < 0x80`, else `0xFF00 or b` (half-width kana). Unmapped codes (e.g. the 7 stray
 * single bytes in ck-gal text) return -1 = draw nothing.
 */
object Wa2FontMap {
    const val CELLS = 7680
    const val CELLS_PER_ROW = 80
    const val KANJI_START = 748 // 0x889F
    const val NO_GLYPH_CELL = 7655

    /** Range table B: pairs of inclusive `lo, hi` code16 values, cells assigned consecutively. */
    private val RANGES = intArrayOf(
        0x0020, 0x007D, 0xFFB1, 0xFFDD,
        0x8140, 0x81AC, 0x81B8, 0x81BF, 0x81C8, 0x81CE, 0x81DA, 0x81FC,
        0x824F, 0x8258, 0x8260, 0x8279, 0x8281, 0x829A, 0x829F, 0x82F1,
        0x8340, 0x8396, 0x839F, 0x83B6, 0x83BF, 0x83D6,
        0x8440, 0x8460, 0x8470, 0x8491, 0x849F, 0x84BE,
        0x8740, 0x875D, 0x875F, 0x8775, 0x877E, 0x878F,
        0x889F, 0x88FC,
        0x8940, 0x89FC, 0x8A40, 0x8AFC, 0x8B40, 0x8BFC, 0x8C40, 0x8CFC, 0x8D40, 0x8DFC,
        0x8E40, 0x8EFC, 0x8F40, 0x8FFC, 0x9040, 0x90FC, 0x9140, 0x91FC, 0x9240, 0x92FC,
        0x9340, 0x93FC, 0x9440, 0x94FC, 0x9540, 0x95FC, 0x9640, 0x96FC, 0x9740, 0x97FC,
        0x9840, 0x9872, 0x989F, 0x98FC,
        0x9940, 0x99FC, 0x9A40, 0x9AFC, 0x9B40, 0x9BFC, 0x9C40, 0x9CFC, 0x9D40, 0x9DFC,
        0x9E40, 0x9EFC, 0x9F40, 0x9FFC,
        0xE040, 0xE0FC, 0xE140, 0xE1FC, 0xE240, 0xE2FC, 0xE340, 0xE3FC, 0xE440, 0xE4FC,
        0xE540, 0xE5FC, 0xE640, 0xE6FC, 0xE740, 0xE7FC, 0xE840, 0xE8FC, 0xE940, 0xE9FC,
        0xEA40, 0xEAA4,
        0xF040, 0xF0C0,
        0xFA40, 0xFAFC, 0xFB40, 0xFBFC, 0xFC40, 0xFC4B,
    )

    private val rangeStart: IntArray = IntArray(RANGES.size / 2).also { starts ->
        var acc = 0
        for (k in starts.indices) {
            starts[k] = acc
            acc += RANGES[2 * k + 1] - RANGES[2 * k] + 1
        }
    }

    /** Total number of mapped cells (7655). */
    val mappedCells: Int = rangeStart.last() + (RANGES[RANGES.size - 1] - RANGES[RANGES.size - 2] + 1)

    private val cellToCode = IntArray(CELLS) { -1 }.also { arr ->
        for (k in rangeStart.indices) {
            val lo = RANGES[2 * k]
            val hi = RANGES[2 * k + 1]
            for (c in lo..hi) arr[rangeStart[k] + (c - lo)] = c
        }
    }

    fun isLeadByte(b: Int): Boolean = (b in 0x81..0x9F) || (b in 0xE0..0xFC)

    /** Normalises a parsed code (single byte 0x80..0xFF → `0xFF00 or b`). */
    fun code16(code: Int): Int = if (code in 0x80..0xFF) 0xFF00 or code else code

    /** Cell index for an engine code (single byte or `lead shl 8 or trail`), or -1 when it has no glyph. */
    fun cellOf(code: Int): Int {
        val c = code16(code)
        for (k in rangeStart.indices) {
            val lo = RANGES[2 * k]
            val hi = RANGES[2 * k + 1]
            if (c in lo..hi) return rangeStart[k] + (c - lo)
        }
        return -1
    }

    /** Code occupying [cell] (half-width kana as 0xB1..0xDD), or -1. */
    fun codeOf(cell: Int): Int {
        if (cell !in 0 until CELLS) return -1
        val c = cellToCode[cell]
        return if (c in 0xFF00..0xFFFF) c and 0xFF else c
    }

    fun cellX(cell: Int, cellSize: Int): Int = (cell % CELLS_PER_ROW) * cellSize
    fun cellY(cell: Int, cellSize: Int): Int = (cell / CELLS_PER_ROW) * cellSize
}
