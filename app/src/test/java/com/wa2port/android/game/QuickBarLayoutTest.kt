package com.wa2port.android.game

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Portrait [QuickBarFill] sizing, pixel-exact label fit (short labels for `✨ AI` / `结束回想` only;
 * `Q.SAVE` always English, shrunk by [QuickBarLayout.fitScale]) and key sets / order ([QuickBarLayout]).
 */
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
     * Rendered label width (dp) at [fontDp], letter spacing included; [slack] inflates the glyph
     * advances (e.g. 1.1 = an OEM font 10 % wider than Roboto).
     */
    private fun widthDp(label: String, fontDp: Float, slack: Float = 1f): Float {
        val em = label.sumOf { (advanceEm[it] ?: error("no advance for '$it' in \"$label\"")).toDouble() }.toFloat()
        return em * fontDp * slack + QuickBarLayout.LETTER_SPACING_DP * label.length
    }

    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)

    /** A [count]-key portrait bar on a [wDp]-wide window at [density], as `QuickBarFill` sees it. */
    private class Bar(wDp: Float, val density: Float, count: Int = 7) {
        val barPx = floor(wDp * density + 1e-3f).toInt()
        val buttonW = QuickBarLayout.buttonWidth(barPx / density, count)
        val font = QuickBarLayout.fontDp(buttonW)
        val roomPx = QuickBarLayout.labelRoomPx(barPx, count, density)
        val touchPx = QuickBarLayout.touchCellMinPx(barPx, count, density)
    }

    /** `TextMeasurer` width (px, rounded up) of [label] on [bar]. */
    private fun textPx(label: String, bar: Bar, slack: Float = 1f): Int = ceil(widthDp(label, bar.font, slack) * bar.density - 1e-3f).toInt()

    private fun shown(a: QuickAction, bar: Bar, slack: Float = 1f): String = QuickBarLayout.displayLabel(a, bar.roomPx) { textPx(it, bar, slack) }

    /** Width (px, rounded up) of [label] on [bar] with `fontSize` and `letterSpacing` both scaled by [k]. */
    private fun scaledPx(label: String, bar: Bar, k: Float, slack: Float = 1f): Int =
        ceil(widthDp(label, bar.font, slack) * k * bar.density - 1e-3f).toInt()

    /** The verified glyph scale `QuickBarFill` applies to [label] on [bar] ([QuickBarLayout.fitScale]). */
    private fun scaleOf(label: String, bar: Bar, slack: Float = 1f): Float =
        QuickBarLayout.fitScale(textPx(label, bar, slack), bar.roomPx) { k -> scaledPx(label, bar, k, slack) }

    /** The Chinese quick-save short label retired in favour of an always-English `Q.SAVE` (escaped on purpose). */
    private val RETIRED_SAVE_SHORT_LABEL = "\u5feb\u5b58"

    /** Roboto, and OEM fonts 10 % / 15 % wider (e.g. ColorOS OPlusSans). */
    private val slacks = listOf(1f, 1.1f, 1.15f)

    private data class Halves(val left: List<QuickAction>, val right: List<QuickAction>, val keys: Triple<QuickAction?, QuickAction?, QuickAction>)

    /** The left / right halves exactly as `GameScreen` builds them (keys = voice, ai, log). */
    private fun halves(voiceKey: Boolean, aiKey: Boolean, replay: Boolean = false): Halves {
        val voice = if (voiceKey) QuickAction("🔊") {} else null
        val ai = if (aiKey) QuickAction("✨ AI", shortLabel = "✨AI") {} else null
        val log = QuickAction("LOG") {}
        val left = listOfNotNull(QuickAction("AUTO") {}, QuickAction("SKIP") {}, log, voice)
        val save = if (replay) QuickAction("结束回想", shortLabel = "退出") {} else QuickAction("Q.SAVE") {}
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
        // Touch cells absorb the 3 dp gaps: ≥ 48 dp wide on the narrowest supported phone.
        assertEquals(49.57f, QuickBarLayout.touchWidth(360f, 7), 0.01f)
        // 1080 px @ 3×: row inset round(19.5) = 20 px, cells ⌊1040 / 7⌋ = 148 px, label room
        // 148 − 2·round(4.5) − 2·12 = 114 px (38 dp).
        val xxh = Bar(360f, 3f)
        assertEquals(148, xxh.touchPx)
        assertEquals(114, xxh.roomPx)
        // Q.SAVE = 37.96 + 3 dp letter spacing = 40.96 dp = 123 px > 114 → stays Q.SAVE, shrunk to
        // 113 / 123 ≈ 0.919 (≈ 10.1 dp glyphs), re-measured at 113 px ≤ 114.
        assertEquals(40.96f, widthDp("Q.SAVE", 11f), 0.01f)
        assertEquals(123, textPx("Q.SAVE", xxh))
        val k = scaleOf("Q.SAVE", xxh)
        assertEquals(113f / 123f, k, 1e-6f)
        assertEquals(10.1f, xxh.font * k, 0.01f)
        assertTrue(scaledPx("Q.SAVE", xxh, k) <= xxh.roomPx)
        for (d in densities) {
            val bar = Bar(360f, d)
            assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), portraitBar(novel = false, voiced = true).map { shown(it, bar) }, "d=$d")
            assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "退出", "MENU"), portraitBar(novel = true, voiced = false, replay = true).map { shown(it, bar) }, "d=$d")
        }
    }

    @Test
    fun qSaveHasNoChineseShortLabelAndIsAlwaysEnglish() {
        // The bar GameScreen builds: Q.SAVE carries no short label at all (only ✨ AI and 结束回想 do).
        for (novel in listOf(false, true)) for (voiced in listOf(false, true)) {
            val save = portraitBar(novel, voiced).single { it.label == "Q.SAVE" }
            assertNull(save.shortLabel)
            val shorts = portraitBar(novel, voiced, replay = true).mapNotNull { it.shortLabel }
            assertEquals(listOf("✨AI", "退出"), shorts)
        }
        // Whatever the room — even 0 px or a measurer reporting absurd widths — it stays "Q.SAVE".
        val save = portraitBar(novel = false, voiced = false).single { it.label == "Q.SAVE" }
        for (room in listOf(0, 1, 37, 114, 132, 161, 400)) for (tw in listOf(1, 123, 134, 170, 10_000)) {
            assertEquals("Q.SAVE", QuickBarLayout.displayLabel(save, room) { tw })
        }
        for (d in densities) for (wDp in 360..600) for (slack in slacks) {
            val bar = Bar(wDp.toFloat(), d)
            assertTrue(portraitBar(novel = wDp % 2 == 0, voiced = true).none { shown(it, bar, slack) == RETIRED_SAVE_SHORT_LABEL })
            assertEquals("Q.SAVE", shown(save, bar, slack), "$wDp dp d=$d slack=$slack")
        }
    }

    @Test
    fun subPixelRoundingShrinksQSaveInsteadOfClipping() {
        // 402 dp @ 3× (1206 px): the float room would be 44.57 dp ≥ Q.SAVE's 44.41 dp, but Compose gives
        // the Text ⌊(1206 − 40) / 7⌋ − 10 − 24 = 132 px < 134 px → Q.SAVE shrunk to 131 / 134.
        val bar = Bar(402f, 3f)
        assertEquals(12f, bar.font)
        assertEquals(132, bar.roomPx)
        assertEquals(134, textPx("Q.SAVE", bar))
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE") {}, bar))
        assertEquals(131f / 134f, scaleOf("Q.SAVE", bar), 1e-6f)
        assertTrue(scaledPx("Q.SAVE", bar, scaleOf("Q.SAVE", bar)) <= bar.roomPx)
        // Same at 402 px @ 1×: 43 px room vs 45 px text.
        val mdpi = Bar(402f, 1f)
        assertEquals(43, mdpi.roomPx)
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE") {}, mdpi))
        assertEquals(42f / 45f, scaleOf("Q.SAVE", mdpi), 1e-6f)
    }

    @Test
    fun pkx110At412dpKeepsTwelveDpAndQSave() {
        val w = QuickBarLayout.buttonWidth(412f, 7)
        assertEquals(54f, w, 1e-4f)
        assertEquals(12f, QuickBarLayout.fontDp(w))
        assertEquals(57f, QuickBarLayout.touchWidth(412f, 7), 1e-4f)
        assertEquals(44.41f, widthDp("Q.SAVE", 12f), 0.01f)
        // PKX110 @ 3.5× (1442 px): cells ⌊(1442 − 46) / 7⌋ = 199 px, room 199 − 10 − 28 = 161 px ≥ 156 px.
        val pkx = Bar(412f, 3.5f)
        assertEquals(199, pkx.touchPx)
        assertEquals(161, pkx.roomPx)
        assertEquals(156, textPx("Q.SAVE", pkx))
        assertEquals(1f, scaleOf("Q.SAVE", pkx))
        // ColorOS OPlusSans (~10 % wider than Roboto): 170 px > 161 → crisp single-line Q.SAVE at
        // 160 / 170 ≈ 0.94 (≈ 11.3 dp), never the retired Chinese short label, never clipped.
        assertEquals(170, textPx("Q.SAVE", pkx, slack = 1.1f))
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE") {}, pkx, slack = 1.1f))
        val k = scaleOf("Q.SAVE", pkx, slack = 1.1f)
        assertEquals(160f / 170f, k, 1e-6f)
        assertEquals(11.3f, pkx.font * k, 0.01f)
        assertTrue(scaledPx("Q.SAVE", pkx, k, slack = 1.1f) <= pkx.roomPx)
        for (d in densities.filter { it >= 1.5f }) {
            val bar = Bar(412f, d)
            assertEquals(listOf("LOG", "AUTO", "SKIP", "✨ AI", "日⇄双", "Q.SAVE", "MENU"), portraitBar(novel = true, voiced = true).map { shown(it, bar) }, "d=$d")
            // 结束回想 (48 + 2 dp) never fits → 退出.
            assertEquals("退出", shown(portraitBar(novel = false, voiced = false, replay = true)[5], bar), "d=$d")
        }
        // 1× rounding eats the margin (44 px room < 45 px): Q.SAVE, shrunk to 43 / 45.
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE") {}, Bar(412f, 1f)))
        assertEquals(43f / 45f, scaleOf("Q.SAVE", Bar(412f, 1f)), 1e-6f)
    }

    @Test
    fun widerPhonesAndTablets() {
        assertEquals(51.29f, QuickBarLayout.buttonWidth(393f, 7), 0.01f) // Pixel @ 2.75×: 11 dp, Q.SAVE fits
        val pixel = Bar(393f, 2.75f)
        assertEquals(11f, pixel.font)
        assertEquals("Q.SAVE", shown(QuickAction("Q.SAVE") {}, pixel))
        assertEquals(1f, scaleOf("Q.SAVE", pixel))
        assertEquals(80.86f, QuickBarLayout.buttonWidth(600f, 7), 0.01f)
        assertEquals("结束回想", shown(QuickAction("结束回想", shortLabel = "退出") {}, Bar(600f, 2f)))
    }

    @Test
    fun everyShownLabelFitsFrom360To600dpAtEveryDensity() {
        // Normal + replay bars, every whole dp width 360…600 × 7 densities with Roboto metrics: every
        // label other than Q.SAVE fits unscaled (full or short); Q.SAVE fits after fitScale (≥ 0.87×);
        // touch cells stay ≥ 48 dp.
        var shortUses = 0
        var saveShrunk = 0
        var checks = 0
        for (d in densities) for (wDp in 360..600) for (replay in listOf(false, true)) {
            val bar = Bar(wDp.toFloat(), d)
            assertTrue(bar.touchPx >= (48f * d).roundToInt(), "touch ${bar.touchPx}px at $wDp dp d=$d")
            assertTrue(bar.font in listOf(11f, 12f))
            for (a in portraitBar(novel = wDp % 2 == 0, voiced = true, replay = replay)) {
                val label = shown(a, bar)
                if (label != a.label) shortUses++
                val k = scaleOf(label, bar)
                if (a.label == "Q.SAVE") {
                    assertEquals("Q.SAVE", label)
                    if (k < 1f) saveShrunk++
                    // Worst case: 360 px @ 1× (whole-px rounding), 41 px in 37 px → 36 / 41 ≈ 0.88.
                    assertTrue(k >= 0.87f, "Q.SAVE k=$k at $wDp dp d=$d")
                } else {
                    assertEquals(1f, k, "\"$label\" at $wDp dp d=$d replay=$replay")
                }
                val tw = scaledPx(label, bar, k)
                assertTrue(tw <= bar.roomPx, "\"$label\" ${tw}px > ${bar.roomPx}px at $wDp dp d=$d replay=$replay")
                checks++
            }
        }
        assertEquals(densities.size * 241 * 2 * 7, checks)
        assertTrue(shortUses > 0)
        assertTrue(saveShrunk > 0)
    }

    @Test
    fun wideOemFontsShrinkInsteadOfClipping() {
        // Glyphs 10 % / 15 % wider than Roboto (e.g. ColorOS OPlusSans, One UI): ✨ AI / 结束回想 still
        // switch to their short labels first; Q.SAVE (and any other label that no longer fits, e.g.
        // 日⇄双 at 1×) is shrunk by fitScale into the pixel-exact room — never clipped, never Chinese.
        var shrunk = 0
        for (slack in slacks) for (d in densities) for (wDp in 360..600) for (replay in listOf(false, true)) {
            val bar = Bar(wDp.toFloat(), d)
            for (a in portraitBar(novel = false, voiced = true, replay = replay)) {
                val label = shown(a, bar, slack)
                assertTrue(label == a.label || label == a.shortLabel)
                val k = scaleOf(label, bar, slack)
                if (k < 1f) shrunk++
                // Worst case: Q.SAVE on a 360 px-wide 1× screen with a 15 % wider font (47 px in 37 px).
                assertTrue(k >= 0.76f, "k=$k for $label at $wDp dp d=$d slack=$slack")
                assertTrue(scaledPx(label, bar, k, slack) <= bar.roomPx, "$label at $wDp dp d=$d slack=$slack")
            }
        }
        assertTrue(shrunk > 0)
        println("10 % / 15 % wider fonts: $shrunk shrunk labels")
    }

    @Test
    fun fitScaleReMeasuresWhenHintingOvershoots() {
        // A measurer whose scaled width rounds 1–2 px above the linear prediction (font hinting).
        val linear = { w: Int -> { k: Float -> ceil(w * k - 1e-3f).toInt() } }
        assertEquals(1f, QuickBarLayout.fitScale(114, 114) { error("no re-measure when it fits") })
        // Linear: the first guess (room − 1) / width already fits.
        assertEquals(113f / 123f, QuickBarLayout.fitScale(123, 114, linear(123)), 1e-6f)
        // +2 px overshoot at every scale: shrinks again until the measured width fits.
        val hinted = { k: Float -> linear(123)(k) + 2 }
        val k = QuickBarLayout.fitScale(123, 114, hinted)
        assertTrue(k < 113f / 123f)
        assertTrue(hinted(k) <= 114, "k=$k → ${hinted(k)} px")
        // Every room 30..170 px vs widths up to 15 % over, with a 1 px overshoot: always fits.
        for (room in 30..170) for (w in room + 1..(room * 1.15f).toInt() + 2) {
            val m = { s: Float -> linear(w)(s) + 1 }
            val s = QuickBarLayout.fitScale(w, room, m)
            assertTrue(m(s) <= room, "w=$w room=$room k=$s")
            assertTrue(s >= QuickBarLayout.MIN_LABEL_SCALE)
        }
        // A label that never fits stops at the floor instead of vanishing.
        assertEquals(QuickBarLayout.MIN_LABEL_SCALE, QuickBarLayout.fitScale(500, 10) { 500 })
    }

    @Test
    fun displayLabelAndScaleEdgeCases() {
        val exit = QuickAction("结束回想", false, shortLabel = "退出") {}
        val menu = QuickAction("MENU") {}
        assertEquals("退出", QuickBarLayout.displayLabel(exit, 114) { 130 })
        assertEquals("结束回想", QuickBarLayout.displayLabel(exit, 160) { 130 })
        // The boundary fits (≤ room).
        assertEquals("结束回想", QuickBarLayout.displayLabel(exit, 130) { 130 })
        // No short label: the full label is kept (fitScale shrinks it instead).
        assertEquals("MENU", QuickBarLayout.displayLabel(menu, 30) { 40 })
        val ai = QuickAction("✨ AI", shortLabel = "✨AI") {}
        assertEquals("✨AI", QuickBarLayout.displayLabel(ai, 30) { 40 })
        assertEquals("✨ AI", QuickBarLayout.displayLabel(ai, 40) { 40 })
        assertEquals(1f, QuickBarLayout.labelScale(100, 100))
        assertEquals(99f / 110f, QuickBarLayout.labelScale(110, 100), 1e-6f)
        assertEquals(1f, QuickBarLayout.labelScale(0, 0))
        assertEquals(0, QuickBarLayout.labelRoomPx(1080, 0, 3f))
        assertEquals(0, QuickBarLayout.touchCellMinPx(1080, 0, 3f))
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
