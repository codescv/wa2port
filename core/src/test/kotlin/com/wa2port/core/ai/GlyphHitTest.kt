package com.wa2port.core.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GlyphHitTest {
    // size 20, pitch 30 (lineSpacing 1.5), band 10: line 0 glyph tops at 10, line 1 at 40.
    private val size = 20f
    private val pitch = 30f
    private val glyphs = listOf(
        GlyphHit.Box(0, 0f, 10f, 20f),
        GlyphHit.Box(1, 20f, 10f, 20f),
        GlyphHit.Box(2, 40f, 10f, 10f), // half-width
        GlyphHit.Box(3, 0f, 40f, 20f),
        GlyphHit.Box(4, 20f, 44f, 16f), // whisper glyph on line 1 (smaller, baseline-aligned)
    )

    private fun hit(x: Float, y: Float, visible: Int = Int.MAX_VALUE) = GlyphHit.glyphIndexAt(glyphs, pitch, size, x, y, visible)

    @Test
    fun insideGlyphs() {
        assertEquals(0, hit(5f, 15f))
        assertEquals(1, hit(20f, 29f))
        assertEquals(2, hit(45f, 2f)) // ruby band above the glyph still belongs to line 0
        assertEquals(3, hit(1f, 30f))
        assertEquals(4, hit(30f, 59f))
    }

    @Test
    fun edgesAndMisses() {
        assertEquals(2, hit(52f, 15f)) // within 0.35·size after the last glyph
        assertEquals(-1, hit(58f, 15f))
        assertEquals(0, hit(-6f, 15f)) // before the first glyph
        assertEquals(-1, hit(-8f, 15f))
        assertEquals(-1, hit(5f, -1f))
        assertEquals(-1, hit(5f, 61f)) // no line 2
        assertEquals(-1, GlyphHit.glyphIndexAt(emptyList(), pitch, size, 0f, 0f))
    }

    @Test
    fun unrevealedGlyphsNeverHit() {
        assertEquals(-1, hit(25f, 15f, visible = 1))
        assertEquals(0, hit(5f, 15f, visible = 1))
        assertEquals(-1, hit(5f, 45f, visible = 3))
    }
}
