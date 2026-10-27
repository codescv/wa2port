package com.wa2port.core.ai

import kotlin.math.floor

/**
 * Pure hit-test math for tap-to-analyze (JVM-testable; the Android renderer feeds it the glyph
 * boxes of `GlyphAtlasText.layout()`).
 *
 * Layout model: line `n` occupies the band `[n·pitch, (n+1)·pitch)` of the text box; a glyph's box
 * top lies inside its line band (`n·pitch + (pitch − size)` for full-size glyphs).
 */
object GlyphHit {
    /** One laid-out base glyph in text-box coordinates. [index] counts `RichText.baseCodes()`. */
    data class Box(val index: Int, val x: Float, val top: Float, val advance: Float)

    /** Horizontal tolerance beyond a line's first / last glyph, as a multiple of the glyph size. */
    const val EDGE_TOLERANCE = 0.35f

    /**
     * Index of the glyph under ([localX], [localY]) or −1. A hit requires [localY] inside the
     * glyph's line band and [localX] inside `[x, x + advance)`; taps just before the first or after
     * the last glyph of a line (within [EDGE_TOLERANCE] × [sizePx]) snap to it. Glyphs with index ≥
     * [visibleGlyphs] (not yet revealed) never hit.
     */
    fun glyphIndexAt(glyphs: List<Box>, pitch: Float, sizePx: Float, localX: Float, localY: Float, visibleGlyphs: Int = Int.MAX_VALUE): Int {
        if (pitch <= 0f || localY < 0f || glyphs.isEmpty()) return -1
        val line = floor(localY / pitch).toInt()
        val onLine = glyphs.filter { it.index < visibleGlyphs && floor(it.top / pitch).toInt() == line }
        if (onLine.isEmpty()) return -1
        onLine.firstOrNull { localX >= it.x && localX < it.x + it.advance }?.let { return it.index }
        val tol = EDGE_TOLERANCE * sizePx
        val first = onLine.minBy { it.x }
        val last = onLine.maxBy { it.x + it.advance }
        if (localX < first.x && localX >= first.x - tol) return first.index
        if (localX >= last.x + last.advance && localX < last.x + last.advance + tol) return last.index
        return -1
    }
}
