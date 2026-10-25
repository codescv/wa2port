package com.wa2port.core.text

/**
 * `WA2.exe` double-byte code → glyph-atlas cell index (0..7679, 80 cells per atlas row).
 *
 * Both the JP (`fnt.pak`) and CN (`fon.pak`) atlases share this layout; CK-GAL replaced only the
 * glyph *pictures*, so CN text bytes are pseudo-SJIS indices into the CN atlas.
 *
 * Verified layout (Phase 0, critic+designer):
 * * cells 0..93 = ASCII 0x20..0x7D, 94..138 = half-width katakana 0xB1..0xDD;
 * * 139.. = lead rows 0x81..0x88 (before 0x889F) enumerated with a per-row trail-range table
 *   ([symbolRows], replaceable from `wa2/sjis_rows.txt` on the classpath);
 * * kanji rows take **189 cells each** (trail 0x40..0xFC *including* 0x7F): 0x889F = 748,
 *   0x8940 = 842, ..., 0x9872 = 3727, 0x989F = 3728 (0x9873..0x989E skipped), 0x9940 = 3822,
 *   0x99..0x9F, 0xE0..0xEA continue (0xEAA4 = 7135); 0xFA40 = 7265 .. 0xFC4B = 7654.
 */
object Wa2FontMap {
    const val CELLS = 7680
    const val CELLS_PER_ROW = 80
    const val KANJI_START = 748 // 0x889F
    private const val ROW = 189

    /**
     * Provisional table (CP932-defined ranges + the 0x81AD..0x81B7 block the engine enumerates);
     * superseded by `wa2/sjis_rows.txt` once the exe routine is lifted.
     */
    private val DEFAULT_ROWS: Map<Int, List<IntRange>> = mapOf(
        0x81 to listOf(0x40..0x7E, 0x80..0xAC, 0xAD..0xB7, 0xB8..0xBF, 0xC8..0xCE, 0xDA..0xE8, 0xF0..0xF7, 0xFC..0xFC),
        0x82 to listOf(0x4F..0x58, 0x60..0x79, 0x81..0x9A, 0x9F..0xF1),
        0x83 to listOf(0x40..0x7E, 0x80..0x96, 0x9F..0xB6, 0xBF..0xD6),
        0x84 to listOf(0x40..0x60, 0x70..0x7E, 0x80..0x91, 0x9F..0xBE),
        0x87 to listOf(0x40..0x5D, 0x5F..0x75, 0x7E..0x7E, 0x80..0x9C),
    )

    /** Inclusive trail ranges per symbol lead byte (0x81..0x88). */
    val symbolRows: Map<Int, List<IntRange>> = loadRows() ?: DEFAULT_ROWS

    private val codeToCell = HashMap<Int, Int>(8192)
    private val cellToCode = IntArray(CELLS) { -1 }

    init {
        fun put(code: Int, cell: Int) {
            if (cell !in 0 until CELLS) return
            codeToCell.putIfAbsent(code, cell)
            if (cellToCode[cell] < 0) cellToCode[cell] = code
        }
        for (c in 0x20..0x7D) put(c, c - 0x20)
        for (c in 0xB1..0xDD) put(c, 94 + (c - 0xB1))
        var cell = 139
        for (lead in 0x81..0x88) {
            for (r in symbolRows[lead].orEmpty()) for (t in r) {
                if (cell >= KANJI_START) break
                put((lead shl 8) or t, cell++)
            }
        }
        // 0x889F..0x88FC
        cell = KANJI_START
        for (t in 0x9F..0xFC) put(0x8800 or t, cell++)
        for (lead in 0x89..0x97) {
            val base = 842 + ROW * (lead - 0x89)
            for (t in 0x40..0xFC) put((lead shl 8) or t, base + (t - 0x40))
        }
        // Row 0x98: 0x9840..0x9872, then 0x989F..0x98FC.
        for (t in 0x40..0x72) put(0x9800 or t, 3677 + (t - 0x40))
        for (t in 0x9F..0xFC) put(0x9800 or t, 3728 + (t - 0x9F))
        val leads = (0x99..0x9F) + (0xE0..0xEA)
        leads.forEachIndexed { i, lead ->
            val base = 3822 + ROW * i
            for (t in 0x40..0xFC) put((lead shl 8) or t, base + (t - 0x40))
        }
        for (lead in 0xFA..0xFC) {
            val base = 7265 + ROW * (lead - 0xFA)
            for (t in 0x40..0xFC) put((lead shl 8) or t, base + (t - 0x40))
        }
    }

    fun isLeadByte(b: Int): Boolean = (b in 0x81..0x9F) || (b in 0xE0..0xFC)

    /** Cell index for a code (single byte < 0x100 or `lead shl 8 | trail`), or -1 when unmapped. */
    fun cellOf(code: Int): Int = codeToCell[code] ?: -1

    /** Code occupying [cell], or -1. */
    fun codeOf(cell: Int): Int = if (cell in 0 until CELLS) cellToCode[cell] else -1

    fun cellX(cell: Int, cellSize: Int): Int = (cell % CELLS_PER_ROW) * cellSize
    fun cellY(cell: Int, cellSize: Int): Int = (cell / CELLS_PER_ROW) * cellSize

    private fun loadRows(): Map<Int, List<IntRange>>? {
        val text = Wa2FontMap::class.java.getResourceAsStream("/wa2/sjis_rows.txt")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        val out = HashMap<Int, List<IntRange>>()
        val lineRe = Regex("""^\s*0x([0-9A-Fa-f]{2})\s*:\s*(.+)$""")
        for (line in text.lines()) {
            val m = lineRe.find(line.substringBefore('#')) ?: continue
            val lead = m.groupValues[1].toInt(16)
            val ranges = m.groupValues[2].split(',').mapNotNull { part ->
                val p = part.trim()
                if (p.isEmpty()) return@mapNotNull null
                val ab = p.split('-').map { it.trim().removePrefix("0x").toInt(16) }
                ab[0]..(ab.getOrElse(1) { ab[0] })
            }
            out[lead] = ranges
        }
        return out.takeIf { it.isNotEmpty() }
    }

}
