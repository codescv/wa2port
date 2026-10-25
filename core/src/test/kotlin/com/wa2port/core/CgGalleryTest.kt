package com.wa2port.core

import com.wa2port.core.engine.SystemData
import com.wa2port.core.gallery.CgCategory
import com.wa2port.core.gallery.CgGallery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CgGalleryTest {
    @Test
    fun `indexes 149 cards and 272 variants from exe card table excluding layer parts and hidden CGs`() {
        val assets = TestAssets.require()
        val gallery = CgGallery.fromAssets(assets)

        val byCat = gallery.groups.groupingBy { it.category }.eachCount()
        val totalVariations = gallery.groups.sumOf { it.variations.size }
        val byArchive = gallery.variationArchives.values.groupingBy { it }.eachCount()
        println(
            "CgGallery: cards=${gallery.groups.size} variations=$totalVariations " +
                "IC=${byCat[CgCategory.IC] ?: 0} CC=${byCat[CgCategory.CC] ?: 0} OTHER=${byCat[CgCategory.OTHER] ?: 0} " +
                "archives=$byArchive",
        )

        // Exact counts from WA2.exe .data 0x4c01b0: 149 cards (27 IC + 122 CC), 272 variants.
        assertEquals(149, gallery.groups.size, "expected 149 CG cards from WA2.exe table")
        assertEquals(27, byCat[CgCategory.IC] ?: 0, "expected 27 IC cards (records 0..26)")
        assertEquals(122, byCat[CgCategory.CC] ?: 0, "expected 122 CC/Coda cards (records 36..161)")
        assertEquals(272, totalVariations, "expected 272 total CG variants")

        for (group in gallery.groups) {
            assertTrue(group.variations.isNotEmpty(), "empty variations in ${group.id}")
            for (v in group.variations) {
                assertFalse(v.contains("_bg", ignoreCase = true), "layer part leaked: $v")
                assertFalse(v.contains("_chr", ignoreCase = true), "layer part leaked: $v")
                assertFalse(v.contains("_eff", ignoreCase = true), "layer part leaked: $v")
                assertFalse(v in CgGallery.HIDDEN_FILES, "hidden unreferenced CG leaked into card: $v")
                assertNotNull(gallery.archiveOf(v), "missing archive record for $v")
                assertTrue(assets.exists("grp", v), "missing asset in grp for $v")
            }
        }

        // Verify the 8 unreferenced CGs and 9 composite layer parts are excluded.
        for (hidden in CgGallery.HIDDEN_FILES) {
            assertFalse(hidden in gallery.variationArchives, "hidden file should not be in gallery: $hidden")
        }

        // Verify fallback stem grouping when card table resource is omitted.
        val fallback = CgGallery.fromAssets(assets, cardTableResource = null)
        assertTrue(fallback.groups.size >= 100, "fallback should still group stems")
        for (hidden in CgGallery.HIDDEN_FILES) {
            assertFalse(hidden in fallback.variationArchives, "fallback should also hide unreferenced CG: $hidden")
        }
    }

    @Test
    fun `unlock state checks per-variant cgFlags and unlocks card when any variant is seen`() {
        val assets = TestAssets.require()
        val gallery = CgGallery.fromAssets(assets)
        val multiCard = gallery.groups.first { it.variations.size >= 2 } // e.g. rec 2: [v100300.tga, v100301.tga]

        val sys = SystemData()
        assertFalse(multiCard.isUnlocked(sys))
        assertFalse(CgGallery.isUnlocked(multiCard.variations[0], sys))
        assertFalse(CgGallery.isUnlocked(multiCard.variations[1], sys))

        // Seeing only the 2nd variant unlocks the card and that specific variant, while the 1st variant stays locked.
        val secondStem = multiCard.variations[1].removeSuffix(".tga")
        sys.cgFlags += secondStem
        assertTrue(multiCard.isUnlocked(sys), "card should unlock when any of its variants is seen")
        assertFalse(CgGallery.isUnlocked(multiCard.variations[0], sys), "unseen variant 0 should stay locked")
        assertTrue(CgGallery.isUnlocked(multiCard.variations[1], sys), "seen variant 1 should be unlocked")
        assertEquals(multiCard.variations[1], multiCard.coverFor(sys, showAll = false))
        assertEquals(multiCard.variations[0], multiCard.coverFor(sys, showAll = true))
    }
}
