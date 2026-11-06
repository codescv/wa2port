package com.wa2port.android.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [ScreenClass]: portrait by measured constraints; compact = portrait or narrower than 600 dp (spec §1.2). */
class CompactTest {
    @Test
    fun portraitIsMeasured() {
        assertTrue(ScreenClass.isPortrait(412f, 915f))
        assertTrue(ScreenClass.isPortrait(673f, 841f))
        assertFalse(ScreenClass.isPortrait(915f, 412f))
        assertFalse(ScreenClass.isPortrait(700f, 700f))
    }

    @Test
    fun portraitIsAlwaysCompact() {
        for (w in listOf(320f, 360f, 412f, 600f, 673f, 800f, 1200f)) assertTrue(ScreenClass.isCompact(true, w), "portrait $w")
    }

    @Test
    fun landscapeCompactBelow600() {
        assertTrue(ScreenClass.isCompact(false, 599f))
        assertTrue(ScreenClass.isCompact(false, 480f))
        assertFalse(ScreenClass.isCompact(false, 600f))
        assertFalse(ScreenClass.isCompact(false, 640f))
        assertFalse(ScreenClass.isCompact(false, 915f))
        assertFalse(ScreenClass.isCompact(false, 1280f))
        assertEquals(600f, ScreenClass.COMPACT_MAX_WIDTH_DP)
    }
}
