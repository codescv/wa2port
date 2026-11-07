package com.wa2port.android.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Portrait [QuickBarFill] sizing and key sets / order ([QuickBarLayout]). */
class QuickBarLayoutTest {
    @Test
    fun sixKeysOnA360dpPhoneKeepTwelveDpAndShortenOnlyTheReplayExit() {
        val w = QuickBarLayout.buttonWidth(360f, 6)
        assertEquals(54.83f, w, 0.01f)
        assertEquals(12f, QuickBarLayout.fontDp(w))
        assertTrue(QuickBarLayout.useShortLabel(w))
        // A 340 dp split screen still gets ≥ 51 dp keys (11 dp font, short labels).
        val split = QuickBarLayout.buttonWidth(340f, 6)
        assertTrue(split >= 51f, "340 dp → $split")
        assertEquals(11f, QuickBarLayout.fontDp(split))
    }

    @Test
    fun pkx110AndTabletsUseTwelveDpFullLabels() {
        val b = QuickBarLayout.buttonWidth(412f, 6)
        assertEquals(63.5f, b, 0.01f)
        assertEquals(12f, QuickBarLayout.fontDp(b))
        assertFalse(QuickBarLayout.useShortLabel(b))
        assertFalse(QuickBarLayout.useShortLabel(QuickBarLayout.buttonWidth(393f, 6)))
        assertEquals(60.33f, QuickBarLayout.buttonWidth(393f, 6), 0.01f)
        assertEquals(94.83f, QuickBarLayout.buttonWidth(600f, 6), 0.01f)
        assertEquals(107f, QuickBarLayout.buttonWidth(673f, 6), 0.01f)
    }

    @Test
    fun portraitNeverAddsTheVoiceOrAiKey() {
        for (novel in listOf(false, true)) for (voice in listOf(false, true)) {
            assertFalse(QuickBarLayout.showsVoiceKey(novel, voice, portrait = true))
            assertFalse(QuickBarLayout.showsAiKey(novel, portrait = true))
            val (l, r, _) = halves(QuickBarLayout.showsVoiceKey(novel, voice, portrait = true), QuickBarLayout.showsAiKey(novel, portrait = true))
            assertEquals(6, l.size + r.size)
        }
        // Landscape NOVEL with voice keeps the extra 🔊 key.
        assertTrue(QuickBarLayout.showsVoiceKey(isNovelMode = true, hasVoice = true, portrait = false))
        assertFalse(QuickBarLayout.showsVoiceKey(isNovelMode = false, hasVoice = true, portrait = false))
        assertEquals(0f, QuickBarLayout.buttonWidth(360f, 0))
    }

    @Test
    fun aiKeyOnlyInLandscapeNovel() {
        // (novel, portrait) → shows ✨ AI
        assertTrue(QuickBarLayout.showsAiKey(isNovelMode = true, portrait = false))
        assertFalse(QuickBarLayout.showsAiKey(isNovelMode = false, portrait = false))
        assertFalse(QuickBarLayout.showsAiKey(isNovelMode = true, portrait = true))
        assertFalse(QuickBarLayout.showsAiKey(isNovelMode = false, portrait = true))
        // Landscape ADV: clean 6-key columns AUTO/SKIP/LOG | 日⇄双/Q.SAVE/MENU.
        val (left, right, _) = halves(voiceKey = false, aiKey = QuickBarLayout.showsAiKey(isNovelMode = false, portrait = false))
        assertEquals(listOf("AUTO", "SKIP", "LOG"), left.map { it.label })
        assertEquals(listOf("日⇄双", "Q.SAVE", "MENU"), right.map { it.label })
    }

    @Test
    fun shortLabelOnlyWhereGiven() {
        val exit = QuickAction("结束回想", false, shortLabel = "退出") {}
        val save = QuickAction("Q.SAVE", false) {}
        assertEquals(58f, QuickBarLayout.SHORT_LABEL_BELOW_DP)
        val narrow = QuickBarLayout.useShortLabel(QuickBarLayout.buttonWidth(360f, 6))
        assertTrue(narrow)
        assertEquals("退出", if (narrow) exit.shortLabel ?: exit.label else exit.label)
        assertEquals("Q.SAVE", if (narrow) save.shortLabel ?: save.label else save.label)
        val wide = QuickBarLayout.useShortLabel(QuickBarLayout.buttonWidth(393f, 6))
        assertFalse(wide)
        assertEquals("结束回想", if (wide) exit.shortLabel ?: exit.label else exit.label)
    }

    private data class Halves(val left: List<QuickAction>, val right: List<QuickAction>, val keys: Triple<QuickAction?, QuickAction?, QuickAction>)

    /** The left / right halves exactly as `GameScreen` builds them (keys = voice, ai, log). */
    private fun halves(voiceKey: Boolean, aiKey: Boolean, replay: Boolean = false): Halves {
        val voice = if (voiceKey) QuickAction("🔊") {} else null
        val ai = if (aiKey) QuickAction("✨ AI") {} else null
        val log = QuickAction("LOG") {}
        val left = listOfNotNull(QuickAction("AUTO") {}, QuickAction("SKIP") {}, log, voice)
        val save = if (replay) QuickAction("结束回想", shortLabel = "退出") {} else QuickAction("Q.SAVE") {}
        val right = listOfNotNull(ai, QuickAction("日⇄双") {}, save, QuickAction("MENU") {})
        return Halves(left, right, Triple(voice, ai, log))
    }

    @Test
    fun landscapeNovelRowPutsAiImmediatelyLeftOfVoice() {
        val voiced = QuickBarLayout.showsVoiceKey(isNovelMode = true, hasVoice = true, portrait = false)
        val ai = QuickBarLayout.showsAiKey(isNovelMode = true, portrait = false)
        val (left, right, keys) = halves(voiced, ai)
        val row = QuickBarLayout.rowOrder(left, right, voice = keys.first, ai = keys.second).map { it.label }
        assertEquals(listOf("AUTO", "SKIP", "LOG", "✨ AI", "🔊", "日⇄双", "Q.SAVE", "MENU"), row)
        assertEquals(row.indexOf("🔊") - 1, row.indexOf("✨ AI"))
        // Side columns keep their halves untouched (🔊 last on the left, ✨ AI first on the right).
        assertEquals("🔊", left.last().label)
        assertEquals("✨ AI", right.first().label)
    }

    @Test
    fun rowWithoutVoiceOrAiKeyIsUnchanged() {
        // Every configuration without a 🔊 key: ADV (both orientations, no ✨ AI → 6 keys), portrait
        // NOVEL (6 keys) and unvoiced landscape NOVEL (✨ AI kept → 7 keys).
        for (portrait in listOf(false, true)) for (novel in listOf(false, true)) {
            val ai = QuickBarLayout.showsAiKey(novel, portrait)
            val (left, right, keys) = halves(QuickBarLayout.showsVoiceKey(novel, hasVoice = false, portrait = portrait), ai)
            assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = keys.first, ai = keys.second))
            assertEquals(if (ai) 7 else 6, (left + right).size)
        }
        // A voice key not present in the left half is ignored (identity match only).
        val (left, right, keys) = halves(voiceKey = false, aiKey = true)
        assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = QuickAction("🔊") {}, ai = keys.second))
        assertEquals(listOf("AUTO", "SKIP", "LOG", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), QuickBarLayout.rowOrder(left, right, null, keys.second).map { it.label })
    }

    @Test
    fun portraitOrderPutsLogAndMenuInTheCorners() {
        val (left, right, keys) = halves(voiceKey = false, aiKey = false)
        val row = QuickBarLayout.portraitOrder(left, right, log = keys.third).map { it.label }
        assertEquals(listOf("LOG", "AUTO", "SKIP", "日⇄双", "Q.SAVE", "MENU"), row)
        // SKIP and Q.SAVE are ≥ 1 key away from either corner.
        for (k in listOf("SKIP", "Q.SAVE")) {
            val i = row.indexOf(k)
            assertTrue(i >= 1 && i <= row.lastIndex - 1, k)
        }
        // Replay mode: 结束回想 takes Q.SAVE's slot.
        val replay = halves(voiceKey = false, aiKey = false, replay = true)
        assertEquals(
            listOf("LOG", "AUTO", "SKIP", "日⇄双", "结束回想", "MENU"),
            QuickBarLayout.portraitOrder(replay.left, replay.right, log = replay.keys.third).map { it.label },
        )
        // A foreign LOG key (identity match only) leaves the halves untouched.
        assertEquals(left + right, QuickBarLayout.portraitOrder(left, right, log = QuickAction("LOG") {}))
    }
}
