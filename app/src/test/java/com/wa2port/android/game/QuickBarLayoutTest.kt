package com.wa2port.android.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Portrait [QuickBarFill] sizing, label fit and key sets / order ([QuickBarLayout]). */
class QuickBarLayoutTest {
    /**
     * Advance widths (em) of every glyph used by the bar labels: Roboto Medium (Android's default
     * Latin), CJK / `⇄` full-width fallback (1 em) and Noto Color Emoji `✨` (2550 / 2048 em).
     */
    private val advanceEm = mapOf(
        'L' to 0.542f, 'O' to 0.690f, 'G' to 0.681f, 'A' to 0.666f, 'U' to 0.652f, 'T' to 0.607f,
        'S' to 0.604f, 'K' to 0.630f, 'I' to 0.282f, 'P' to 0.640f, 'Q' to 0.690f, '.' to 0.279f,
        'V' to 0.647f, 'E' to 0.565f, 'M' to 0.875f, 'N' to 0.710f, ' ' to 0.249f, '✨' to 2550f / 2048f,
        '日' to 1f, '⇄' to 1f, '双' to 1f, '快' to 1f, '存' to 1f, '结' to 1f, '束' to 1f, '回' to 1f, '想' to 1f,
        '退' to 1f, '出' to 1f,
    )

    /**
     * Rendered label width (dp) at [QuickBarLayout.fontDp] for a button [buttonW] wide, letter spacing
     * included; [slack] inflates the glyph advances (e.g. 1.1 = an OEM font 10 % wider than Roboto).
     */
    private fun widthDp(label: String, buttonW: Float, slack: Float = 1f): Float {
        val font = QuickBarLayout.fontDp(buttonW)
        val em = label.sumOf { (advanceEm[it] ?: error("no advance for '$it' in \"$label\"")).toDouble() }.toFloat()
        return em * font * slack + QuickBarLayout.LETTER_SPACING_DP * label.length
    }

    private fun shown(a: QuickAction, w: Float, slack: Float = 1f): String = QuickBarLayout.displayLabel(a, w) { widthDp(it, w, slack) }

    private data class Halves(val left: List<QuickAction>, val right: List<QuickAction>, val keys: Triple<QuickAction?, QuickAction?, QuickAction>)

    /** The left / right halves exactly as `GameScreen` builds them (keys = voice, ai, log). */
    private fun halves(voiceKey: Boolean, aiKey: Boolean, replay: Boolean = false): Halves {
        val voice = if (voiceKey) QuickAction("🔊") {} else null
        val ai = if (aiKey) QuickAction("✨ AI", shortLabel = "✨AI") {} else null
        val log = QuickAction("LOG") {}
        val left = listOfNotNull(QuickAction("AUTO") {}, QuickAction("SKIP") {}, log, voice)
        val save = if (replay) QuickAction("结束回想", shortLabel = "退出") {} else QuickAction("Q.SAVE", shortLabel = "快存") {}
        val right = listOfNotNull(ai, QuickAction("日⇄双") {}, save, QuickAction("MENU") {})
        return Halves(left, right, Triple(voice, ai, log))
    }

    /** The portrait bottom bar exactly as `GameScreen` builds it ([novel] / [voiced] never change it). */
    private fun portraitBar(novel: Boolean, voiced: Boolean, replay: Boolean = false): List<QuickAction> {
        val h = halves(QuickBarLayout.showsVoiceKey(novel, voiced, portrait = true), QuickBarLayout.showsAiKey(novel, portrait = true), replay)
        return QuickBarLayout.portraitOrder(h.left, h.right, log = h.keys.third)
    }

    // ------------------------------------------------------------------ key sets

    @Test
    fun portraitAlwaysAddsTheAiKeyButNeverTheVoiceKey() {
        for (novel in listOf(false, true)) for (voice in listOf(false, true)) for (replay in listOf(false, true)) {
            assertTrue(QuickBarLayout.showsAiKey(novel, portrait = true), "novel=$novel")
            assertFalse(QuickBarLayout.showsVoiceKey(novel, voice, portrait = true))
            val bar = portraitBar(novel, voice, replay)
            assertEquals(7, bar.size, "novel=$novel voice=$voice replay=$replay")
            assertEquals(1, bar.count { it.label == "✨ AI" })
            assertEquals(0, bar.count { it.label == "🔊" })
        }
        assertEquals(0f, QuickBarLayout.buttonWidth(360f, 0))
        assertEquals(0f, QuickBarLayout.touchWidth(360f, 0))
    }

    @Test
    fun landscapeKeepsAiKeyOnlyInNovel() {
        // Landscape NOVEL (no rim pills) keeps ✨ AI (+ 🔊 on voiced lines).
        assertTrue(QuickBarLayout.showsAiKey(isNovelMode = true, portrait = false))
        assertTrue(QuickBarLayout.showsVoiceKey(isNovelMode = true, hasVoice = true, portrait = false))
        // Landscape ADV has the `[✨ AI解读] [🔊 重听]` rim pills: no duplicate keys.
        assertFalse(QuickBarLayout.showsAiKey(isNovelMode = false, portrait = false))
        assertFalse(QuickBarLayout.showsVoiceKey(isNovelMode = false, hasVoice = true, portrait = false))
        // Landscape ADV: clean 6-key columns AUTO/SKIP/LOG | 日⇄双/Q.SAVE/MENU.
        val (left, right, _) = halves(voiceKey = false, aiKey = QuickBarLayout.showsAiKey(isNovelMode = false, portrait = false))
        assertEquals(listOf("AUTO", "SKIP", "LOG"), left.map { it.label })
        assertEquals(listOf("日⇄双", "Q.SAVE", "MENU"), right.map { it.label })
    }

    @Test
    fun aiKeyIsShownExactlyWhereNoRimAiPillIs() {
        // Portrait deck → no ✨ rim pill → ✨ AI key; landscape ADV → rim pill → no key; landscape NOVEL
        // draws no pills at all → key. Every configuration offers exactly one ✨ entry point.
        for (portrait in listOf(false, true)) for (novel in listOf(false, true)) {
            val rimPill = !novel && AdvPills.showsRimAi(jpEmpty = false, portrait = portrait)
            assertEquals(!rimPill, QuickBarLayout.showsAiKey(novel, portrait), "portrait=$portrait novel=$novel")
        }
    }

    // ------------------------------------------------------------------ sizing

    @Test
    fun sevenKeysOnA360dpPhone() {
        val w = QuickBarLayout.buttonWidth(360f, 7)
        assertEquals((360f - 16f - 18f) / 7f, w, 1e-4f)
        assertEquals(46.57f, w, 0.01f)
        assertEquals(11f, QuickBarLayout.fontDp(w))
        assertEquals(38.57f, QuickBarLayout.labelRoom(w), 0.01f)
        // Touch cells absorb the 3 dp gaps: ≥ 48 dp wide on the narrowest supported phone.
        assertEquals(49.57f, QuickBarLayout.touchWidth(360f, 7), 0.01f)
        val bar = portraitBar(novel = false, voiced = true)
        assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "快存", "MENU"), bar.map { shown(it, w) })
        // Q.SAVE would need 37.96 + 3 dp of the 38.57 dp room: too tight, so 快存.
        assertEquals(40.96f, widthDp("Q.SAVE", w), 0.01f)
        val replay = portraitBar(novel = true, voiced = false, replay = true)
        assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "退出", "MENU"), replay.map { shown(it, w) })
    }

    @Test
    fun pkx110At412dpKeepsTwelveDpAndQSave() {
        val w = QuickBarLayout.buttonWidth(412f, 7)
        assertEquals(54f, w, 1e-4f)
        assertEquals(12f, QuickBarLayout.fontDp(w))
        assertEquals(46f, QuickBarLayout.labelRoom(w), 1e-4f)
        assertEquals(57f, QuickBarLayout.touchWidth(412f, 7), 1e-4f)
        assertEquals(44.41f, widthDp("Q.SAVE", w), 0.01f)
        val bar = portraitBar(novel = true, voiced = true)
        assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), bar.map { shown(it, w) })
        // 结束回想 (48 + 2 dp) never fits 46 dp → 退出.
        val replay = portraitBar(novel = false, voiced = false, replay = true)
        assertEquals("退出", shown(replay[5], w))
    }

    @Test
    fun widerPhonesAndTablets() {
        assertEquals(51.29f, QuickBarLayout.buttonWidth(393f, 7), 0.01f) // Pixel: 11 dp, Q.SAVE fits
        assertEquals(11f, QuickBarLayout.fontDp(QuickBarLayout.buttonWidth(393f, 7)))
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE", shortLabel = "快存") {}, QuickBarLayout.buttonWidth(393f, 7)))
        assertEquals(80.86f, QuickBarLayout.buttonWidth(600f, 7), 0.01f)
        assertEquals("结束回想", shown(QuickAction("结束回想", shortLabel = "退出") {}, QuickBarLayout.buttonWidth(600f, 7)))
    }

    @Test
    fun everyShownLabelFitsFrom360To600dp() {
        // Normal + replay bars, every whole dp width 360…600: the shown label (full or short) fits the
        // label room, even with glyphs 10 % wider than Roboto; touch cells stay ≥ 48 dp.
        var shortUses = 0
        for (wDp in 360..600) for (replay in listOf(false, true)) for (slack in listOf(1f, 1.1f)) {
            val bar = portraitBar(novel = wDp % 2 == 0, voiced = true, replay = replay)
            val w = QuickBarLayout.buttonWidth(wDp.toFloat(), bar.size)
            assertTrue(w >= 46.5f, "$wDp dp → $w")
            assertTrue(QuickBarLayout.touchWidth(wDp.toFloat(), bar.size) >= 48f)
            assertTrue(QuickBarLayout.fontDp(w) in listOf(11f, 12f))
            for (a in bar) {
                val label = shown(a, w, slack)
                if (label != a.label) shortUses++
                val tw = widthDp(label, w, slack)
                assertTrue(tw <= QuickBarLayout.labelRoom(w), "\"$label\" ${tw}dp > ${QuickBarLayout.labelRoom(w)}dp at $wDp dp (replay=$replay slack=$slack)")
            }
        }
        assertTrue(shortUses > 0)
    }

    @Test
    fun displayLabelFallsBackOnlyWhereAShortLabelExists() {
        val exit = QuickAction("结束回想", false, shortLabel = "退出") {}
        val menu = QuickAction("MENU") {}
        assertEquals("退出", QuickBarLayout.displayLabel(exit, 46.57f) { 44f })
        assertEquals("结束回想", QuickBarLayout.displayLabel(exit, 60f) { 44f })
        // No short label: the full label is kept even when the measurement says it is too wide.
        assertEquals("MENU", QuickBarLayout.displayLabel(menu, 30f) { 40f })
        // The boundary fits (≤ room).
        assertEquals("结束回想", QuickBarLayout.displayLabel(exit, 52f) { 44f })
    }

    // ------------------------------------------------------------------ order

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
    fun landscapeRowWithoutVoiceKeyIsUnchanged() {
        // Landscape ADV (6 keys) and unvoiced landscape NOVEL (✨ AI kept → 7 keys).
        for (novel in listOf(false, true)) {
            val ai = QuickBarLayout.showsAiKey(novel, portrait = false)
            val (left, right, keys) = halves(QuickBarLayout.showsVoiceKey(novel, hasVoice = false, portrait = false), ai)
            assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = keys.first, ai = keys.second))
            assertEquals(if (ai) 7 else 6, (left + right).size)
        }
        // A voice key not present in the left half is ignored (identity match only).
        val (left, right, keys) = halves(voiceKey = false, aiKey = true)
        assertEquals(left + right, QuickBarLayout.rowOrder(left, right, voice = QuickAction("🔊") {}, ai = keys.second))
        assertEquals(listOf("AUTO", "SKIP", "LOG", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), QuickBarLayout.rowOrder(left, right, null, keys.second).map { it.label })
    }

    @Test
    fun portraitOrderPutsLogAndMenuInTheCornersAndAiInTheCentre() {
        for (novel in listOf(false, true)) {
            val row = portraitBar(novel, voiced = true).map { it.label }
            assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), row)
            // SKIP and Q.SAVE are ≥ 1 key away from either corner; ✨ AI sits in the centre slot.
            for (k in listOf("SKIP", "Q.SAVE", "✨ AI")) {
                val i = row.indexOf(k)
                assertTrue(i >= 1 && i <= row.lastIndex - 1, k)
            }
            assertEquals(row.lastIndex / 2, row.indexOf("✨ AI"))
            // ✨ AI is never next to Q.SAVE (a slip never overwrites the quick save).
            assertTrue(kotlin.math.abs(row.indexOf("✨ AI") - row.indexOf("Q.SAVE")) > 1)
        }
        // Replay mode: 结束回想 takes Q.SAVE's slot.
        assertEquals(
            listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "结束回想", "MENU"),
            portraitBar(novel = false, voiced = false, replay = true).map { it.label },
        )
        // A foreign LOG key (identity match only) leaves the halves untouched.
        val (left, right, _) = halves(voiceKey = false, aiKey = true)
        assertEquals(left + right, QuickBarLayout.portraitOrder(left, right, log = QuickAction("LOG") {}))
    }
}
