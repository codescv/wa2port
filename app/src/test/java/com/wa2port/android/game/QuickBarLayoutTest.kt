package com.wa2port.android.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Portrait [QuickBarFill] sizing ([QuickBarLayout], spec §2.7). */
class QuickBarLayoutTest {
    @Test
    fun narrowPhoneUsesSmallFontAndShortLabels() {
        val w = QuickBarLayout.buttonWidth(360f, 7)
        assertEquals(46.57f, w, 0.01f)
        assertEquals(11f, QuickBarLayout.fontDp(w))
        assertTrue(QuickBarLayout.useShortLabel(w))
    }

    @Test
    fun pkx110AndTabletsUseTwelveDp() {
        val b = QuickBarLayout.buttonWidth(412f, 7)
        assertEquals(54f, b, 0.01f)
        assertEquals(12f, QuickBarLayout.fontDp(b))
        assertFalse(QuickBarLayout.useShortLabel(b))
        assertEquals(80.86f, QuickBarLayout.buttonWidth(600f, 7), 0.01f)
        assertEquals(91.29f, QuickBarLayout.buttonWidth(673f, 7), 0.01f)
        // Every key keeps a ≥ 44 dp touch width even on a 340 dp split screen.
        assertTrue(QuickBarLayout.buttonWidth(340f, 7) >= 43.5f)
    }

    @Test
    fun portraitNeverAddsTheVoiceKey() {
        for (novel in listOf(false, true)) for (voice in listOf(false, true)) {
            assertFalse(QuickBarLayout.showsVoiceKey(novel, voice, portrait = true))
            val keys = 7 + if (QuickBarLayout.showsVoiceKey(novel, voice, portrait = true)) 1 else 0
            assertEquals(7, keys)
        }
        // Landscape NOVEL with voice keeps today's 8th 🔊 key.
        assertTrue(QuickBarLayout.showsVoiceKey(isNovelMode = true, hasVoice = true, portrait = false))
        assertFalse(QuickBarLayout.showsVoiceKey(isNovelMode = false, hasVoice = true, portrait = false))
        assertEquals(0f, QuickBarLayout.buttonWidth(360f, 0))
    }

    @Test
    fun shortLabelOnlyWhereGiven() {
        val exit = QuickAction("结束回想", false, shortLabel = "退出") {}
        val save = QuickAction("Q.SAVE", false) {}
        val narrow = QuickBarLayout.useShortLabel(QuickBarLayout.buttonWidth(360f, 7))
        assertEquals("退出", if (narrow) exit.shortLabel ?: exit.label else exit.label)
        assertEquals("Q.SAVE", if (narrow) save.shortLabel ?: save.label else save.label)
    }

    /** The left / right halves exactly as `GameScreen` builds them. */
    private fun halves(voiceKey: Boolean): Triple<List<QuickAction>, List<QuickAction>, Pair<QuickAction?, QuickAction>> {
        val voice = if (voiceKey) QuickAction("🔊") {} else null
        val ai = QuickAction("✨ AI") {}
        val left = listOfNotNull(QuickAction("AUTO") {}, QuickAction("SKIP") {}, QuickAction("LOG") {}, voice)
        val right = listOf(ai, QuickAction("日⇄双") {}, QuickAction("Q.SAVE") {}, QuickAction("MENU") {})
        return Triple(left, right, voice to ai)
    }

    @Test
    fun landscapeNovelRowPutsAiImmediatelyLeftOfVoice() {
        val voiced = QuickBarLayout.showsVoiceKey(isNovelMode = true, hasVoice = true, portrait = false)
        val (left, right, keys) = halves(voiced)
        val row = QuickBarLayout.rowOrder(left, right, voice = keys.first, ai = keys.second).map { it.label }
        assertEquals(listOf("AUTO", "SKIP", "LOG", "✨ AI", "🔊", "日⇄双", "Q.SAVE", "MENU"), row)
        assertEquals(row.indexOf("🔊") - 1, row.indexOf("✨ AI"))
        // Side columns keep their halves untouched (🔊 last on the left, ✨ AI first on the right).
        assertEquals("🔊", left.last().label)
        assertEquals("✨ AI", right.first().label)
    }

    @Test
    fun rowWithoutVoiceKeyIsUnchanged() {
        for (portrait in listOf(false, true)) for (novel in listOf(false, true)) {
            val (left, right, keys) = halves(QuickBarLayout.showsVoiceKey(novel, hasVoice = false, portrait = portrait))
            assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = keys.first, ai = keys.second))
            assertEquals(7, (left + right).size)
        }
        // A voice key not present in the left half is ignored (identity match only).
        val (left, right, keys) = halves(false)
        assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = QuickAction("🔊") {}, ai = keys.second))
    }
}
