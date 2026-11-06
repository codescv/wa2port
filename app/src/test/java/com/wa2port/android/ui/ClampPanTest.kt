package com.wa2port.android.ui

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `CgViewer` pan clamp uses the 16:9 fitted CG rect, not the container (spec §3.7 / §4). */
class ClampPanTest {
    private val far = Offset(10_000f, 10_000f)
    private val eps = 0.1f

    @Test
    fun portraitPhoneAt2xClampsToFittedWidthAndNoVerticalPan() {
        // Device B portrait: 412 dp wide container (height = full screen) → fitted 412×231.75.
        val (fw, fh) = cgFittedSize(412f, 915f)
        assertEquals(412f, fw, eps)
        assertEquals(231.75f, fh, eps)

        val c = clampPanOffset(far, 2f, 412f, 915f)
        assertEquals(206f, c.x, eps)
        assertEquals(0f, c.y, eps)

        val n = clampPanOffset(-far, 2f, 412f, 915f)
        assertEquals(-206f, n.x, eps)
        assertEquals(0f, n.y, eps)
    }

    @Test
    fun landscapePhoneAt2xUsesPillarboxedFittedRect() {
        // 915×412 landscape → fitted 732.4×412; maxX = (1464.9 − 915)/2 = 274.9, maxY = 206.
        val (fw, fh) = cgFittedSize(915f, 412f)
        assertEquals(732.4f, fw, eps)
        assertEquals(412f, fh, eps)

        val c = clampPanOffset(far, 2f, 915f, 412f)
        assertEquals(274.9f, c.x, eps)
        assertEquals(206f, c.y, eps)

        val n = clampPanOffset(-far, 2f, 915f, 412f)
        assertEquals(-274.9f, n.x, eps)
        assertEquals(-206f, n.y, eps)
    }

    @Test
    fun offsetsInsideTheLimitsAreKept() {
        val c = clampPanOffset(Offset(100f, -50f), 2f, 915f, 412f)
        assertEquals(100f, c.x, eps)
        assertEquals(-50f, c.y, eps)
    }

    @Test
    fun unzoomedOrEmptyContainerResetsToZero() {
        assertEquals(Offset.Zero, clampPanOffset(far, 1f, 915f, 412f))
        assertEquals(Offset.Zero, clampPanOffset(far, 2f, 0f, 412f))
        assertEquals(Offset.Zero, clampPanOffset(far, 2f, 915f, 0f))
    }
}
