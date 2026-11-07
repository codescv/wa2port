package com.wa2port.android.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wa2port.android.data.DisplayLang
import com.wa2port.core.ai.AiPackCorpus
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.script.ScenarioStore
import com.wa2port.core.text.DeckLayout
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextScale
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Message-window rim pills `[✨ AI解读] [🔊 重听]` ([AdvPills]): placement, ≥ 48 dp non-overlapping touch
 * targets above Line 0, and speaker-nameplate clearance over the real WA2 corpus.
 */
class AdvPillsTest {
    private val eps = 1e-3f
    private val scales = (TextScale.MIN..TextScale.MAX step TextScale.STEP).map { it / 100f }
    private val densities = listOf(1f, 2f, 2.625f, 3.5f)

    /** One window geometry exactly as [AdvWindow] / [NovelPage] compute it (canvas px). */
    private data class Window(
        val name: String,
        val left: Float,
        val right: Float,
        val top: Float,
        val s: Float,
        val canvasWidth: Float,
        val minPx: Float,
    ) {
        val pad: Float get() = MessageLayout.ADV_PAD * s
        val anchor: Float get() = right - pad
        val textTop: Float get() = DeckText.textTop(top, pad)
    }

    /** Landscape ADV window on a stage box [w]×[h] dp at [density] (`scale = h / 720`). */
    private fun landscape(w: Float, h: Float, density: Float, textScale: Float, twoCol: Boolean): Window {
        val cw = w * density
        val ch = h * density
        return Window(
            "landscape ${w}x$h d=$density t=$textScale twoCol=$twoCol",
            cw * MessageLayout.ADV_LEFT, cw * MessageLayout.ADV_RIGHT, ch * MessageLayout.advTop(textScale, twoCol),
            ch / 720f, cw, 48f * density,
        )
    }

    /** Portrait reading-deck frame (ADV and NOVEL share it) on a [w]×[h] dp phone at [density]. */
    private fun portrait(w: Float, h: Float, density: Float, textScale: Float, lang: DisplayLang): Window {
        val g = GameLayout.compute(w, h, 24f, 24f, textScale, lang, hasChoices = false, hasLine = true)
        val f = g.advFrame!!
        return Window(
            "portrait ${w}x$h d=$density t=$textScale $lang",
            f.left * density, f.right * density, f.top * density, DeckLayout.DECK_SCALE * density, w * density, 48f * density,
        )
    }

    private fun allWindows(): List<Window> = buildList {
        for (d in densities) for (t in scales) {
            for ((w, h) in listOf(640f to 360f, 732.44f to 412f, 1024f to 576f, 1280f to 720f)) for (two in listOf(false, true)) add(landscape(w, h, d, t, two))
            for ((w, h) in listOf(360f to 780f, 393f to 852f, 412f to 915f, 600f to 960f)) for (lang in DisplayLang.entries) add(portrait(w, h, d, t, lang))
        }
    }

    private fun assertTouchTarget(win: Window, r: Rect, what: String) {
        assertTrue(r.width >= win.minPx - eps, "$what width ${r.width} < 48dp (${win.name})")
        assertTrue(r.height >= win.minPx - eps, "$what height ${r.height} < 48dp (${win.name})")
        assertTrue(r.bottom <= win.textTop - 2f * win.s + eps, "$what reaches Line 0 (${win.name})")
        assertTrue(r.left >= -eps && r.right <= win.canvasWidth + eps, "$what outside canvas (${win.name})")
    }

    @Test
    fun voicedJpLinePutsAiImmediatelyLeftOfVoiceWithoutOverlap() {
        for (win in allWindows()) for (short in listOf(false, true)) {
            val p = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, showAi = true, showVoice = true, shortAi = short, win.minPx, win.canvasWidth)
            val ai = p.aiPill!!
            val voice = p.voicePill!!
            // 🔊 重听 anchored at right − pad; ✨ AI解读 to its left with a clear visual gap.
            assertEquals(win.anchor, voice.right, eps, win.name)
            assertEquals(AdvPills.PILL_GAP * win.s, voice.left - ai.right, eps, win.name)
            // Both straddle the window top border exactly like the original 🔊 pill.
            assertEquals(win.top - AdvPills.PILL_H * win.s * 0.55f, ai.top, eps)
            assertEquals(ai.top, voice.top, eps)
            assertEquals(AdvPills.PILL_H * win.s, ai.height, eps)
            val aiT = p.aiTouch!!
            val vT = p.voiceTouch!!
            assertTrue(aiT.right <= vT.left + eps, "aiRect strictly left of voiceRect (${win.name})")
            assertFalse(aiT.overlaps(vT), "touch targets overlap (${win.name})")
            assertTouchTarget(win, aiT, "aiRect")
            assertTouchTarget(win, vT, "voiceRect")
            // Each touch target covers its own pill's centre (a tap on the pill hits that pill).
            assertTrue(aiT.contains(Offset(ai.center.x, aiT.bottom - 1f)), win.name)
            assertTrue(vT.contains(Offset(voice.center.x, vT.bottom - 1f)), win.name)
            assertEquals(if (short) AdvPills.AI_SHORT_LABEL else AdvPills.AI_LABEL, p.aiLabel)
        }
    }

    @Test
    fun unvoicedJpLineAnchorsAiAtTheRimEnd() {
        for (win in allWindows()) {
            val p = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, AdvPills.showsAi(jpEmpty = false), showVoice = false, shortAi = false, win.minPx, win.canvasWidth)
            assertNull(p.voicePill)
            assertNull(p.voiceTouch)
            assertEquals(win.anchor, p.aiPill!!.right, eps, win.name)
            assertEquals(AdvPills.AI_W * win.s, p.aiPill!!.width, eps)
            assertTouchTarget(win, p.aiTouch!!, "aiRect")
        }
    }

    @Test
    fun emptyJpLineHasNoAiPill() {
        assertFalse(AdvPills.showsAi(RichText.EMPTY.isEmpty))
        for (win in allWindows()) {
            val voiced = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, AdvPills.showsAi(jpEmpty = true), showVoice = true, shortAi = false, win.minPx, win.canvasWidth)
            assertNull(voiced.aiPill)
            assertNull(voiced.aiTouch)
            // 🔊 重听 keeps today's place and touch target.
            assertEquals(win.anchor, voiced.voicePill!!.right, eps)
            assertTouchTarget(win, voiced.voiceTouch!!, "voiceRect")
            val none = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, showAi = false, showVoice = false, shortAi = false, win.minPx, win.canvasWidth)
            assertNull(none.aiTouch)
            assertNull(none.voiceTouch)
            assertEquals(Float.POSITIVE_INFINITY, none.pillsLeft)
        }
    }

    @Test
    fun portraitPkx110WorkedExample() {
        // 412 dp PKX110 @100 %, density 1: deck 16…396, s = 0.572, 48 dp = 48 px.
        val win = portrait(412f, 915f, 1f, 1f, DisplayLang.BOTH)
        val p = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, showAi = true, showVoice = true, shortAi = false, win.minPx, win.canvasWidth)
        assertEquals(396f - 12.589f, p.voicePill!!.right, 0.01f)
        assertEquals(52.64f, p.voicePill!!.width, 0.01f)
        assertEquals(61.8f, p.aiPill!!.width, 0.01f)
        assertEquals(5.72f, p.voicePill!!.left - p.aiPill!!.right, 0.01f)
        val split = (p.aiPill!!.right + p.voicePill!!.left) / 2f
        assertEquals(split, p.aiTouch!!.right, eps)
        assertEquals(split, p.voiceTouch!!.left, eps)
        assertEquals(48f, p.aiTouch!!.height, 0.01f)
        // The shared edge belongs to 🔊 only (Rect.contains is right-open).
        assertFalse(p.aiTouch!!.contains(Offset(split, p.aiTouch!!.bottom - 1f)))
        assertTrue(p.voiceTouch!!.contains(Offset(split, p.voiceTouch!!.bottom - 1f)))
    }

    @Test
    fun clearGeometryResetsBothPills() {
        val hit = AdvHitBox()
        hit.aiRect = Rect(0f, 0f, 50f, 50f)
        hit.voiceRect = Rect(60f, 0f, 110f, 50f)
        hit.frameMode = true
        hit.clearGeometry()
        assertNull(hit.aiRect)
        assertNull(hit.voiceRect)
        assertFalse(hit.frameMode)
    }

    @Test
    fun fitNameplateLadder() {
        val s = DeckLayout.DECK_SCALE
        val pad = MessageLayout.ADV_PAD * s
        val nameLeft = 16f + pad
        val anchor = 344f - pad
        val gap2 = AdvPills.NAME_PART_GAP * s
        val fullLimit = anchor - AdvPills.pillsWidth(true, true, false, s) - AdvPills.NAME_GAP * s - nameLeft
        val shortLimit = anchor - AdvPills.pillsWidth(true, true, true, s) - AdvPills.NAME_GAP * s - nameLeft
        // 1. A short name fits next to the full label.
        assertEquals(AdvPills.NameFit(false, 1f, 1), AdvPills.fitNameplate(nameLeft, listOf(40f), pad, s, anchor, showAi = true, showVoice = true))
        // 2. Wider: the short `解读` label buys the room, glyphs stay full size.
        val mid = (fullLimit + shortLimit) / 2f - 2f * pad
        assertEquals(AdvPills.NameFit(true, 1f, 1), AdvPills.fitNameplate(nameLeft, listOf(mid), pad, s, anchor, true, true))
        // 3. Wider still: short label + glyphs scaled (≥ NAME_MIN_SCALE), ending exactly NAME_GAP before the pills.
        val room2 = shortLimit - 2f * pad - gap2
        val parts = listOf(room2 * 0.5f, room2 * 0.4f) // 0.9·room / 0.9 → k ≈ 0.9 … keep the JP part
        val k3 = AdvPills.fitNameplate(nameLeft, parts.map { it / 0.85f }, pad, s, anchor, true, true)
        assertEquals(true, k3.shortAi)
        assertEquals(2, k3.parts)
        assertEquals(0.85f / 0.9f, k3.glyphScale, 1e-4f)
        assertEquals(shortLimit, AdvPills.nameplateWidth(parts.sum() / 0.85f * k3.glyphScale, 2, pad, s), 0.01f)
        // 4. Below NAME_MIN_SCALE with both parts: the 双语 JP secondary part is dropped first.
        val huge = AdvPills.fitNameplate(nameLeft, listOf(room2 * 0.9f, room2 * 0.8f), pad, s, anchor, true, true)
        assertEquals(AdvPills.NameFit(true, 1f, 1), huge)
        // Single part never drops; scales as far as needed (no overlap guarantee).
        val single = AdvPills.fitNameplate(nameLeft, listOf(400f), pad, s, anchor, true, true)
        assertEquals(1, single.parts)
        assertEquals(shortLimit, AdvPills.nameplateWidth(400f * single.glyphScale, 1, pad, s), 0.01f)
        // Voice only (empty JP): never switches the (absent) AI label.
        assertFalse(AdvPills.fitNameplate(nameLeft, listOf(400f), pad, s, anchor, showAi = false, showVoice = true).shortAi)
    }

    // ------------------------------------------------------------------ real WA2 speaker names

    private object Corpus {
        val root = File(
            System.getProperty("wa2.root") ?: System.getenv("WA2_ROOT") ?: "../resource/WA2",
        )
        val available: Boolean get() = File(root, "script.pak").isFile || File(root, "ck-gal.pak").isFile

        /** Every distinct (JP, CN) speaker-name pair of every message line (script.pak + ck-gal.pak). */
        val speakers: List<Pair<RichText?, RichText?>> by lazy {
            val assets = Wa2Assets(root)
            val store = ScenarioStore(assets)
            val seen = HashSet<Pair<String, Int>>()
            val byText = LinkedHashMap<Pair<List<Int>, List<Int>>, Pair<RichText?, RichText?>>()
            for (o in AiPackCorpus.extract(assets).occurrences) {
                if (o.speakerToken < 0 || !seen.add(o.scene to o.speakerToken)) continue
                val sc = store.scene(o.scene) ?: continue
                val jp = sc.jp.rich(o.speakerToken)
                val cn = sc.cn?.rich(o.speakerToken)
                byText.getOrPut(jp.baseCodes().toList() to (cn?.baseCodes()?.toList() ?: emptyList())) { jp to cn }
            }
            byText.values.toList()
        }
    }

    private val sampleJp = RichText(listOf(TextItem.Run(IntArray(8) { 0x82A0 + it })))

    @Test
    fun nameplateNeverOverlapsPillsOverRealWa2Corpus() {
        assumeTrue(Corpus.available, "WA2 resources not found at ${Corpus.root}")
        val speakers = Corpus.speakers
        assertTrue(speakers.size > 50, "speakers=${speakers.size}")
        var checks = 0
        var minScale = 1f
        var shortUses = 0
        var dropped = 0
        var worst = ""
        val windows = buildList {
            for (d in listOf(1f, 3f)) for (t in scales) {
                for (lang in DisplayLang.entries) for (w in listOf(360f, 393f, 412f)) add(portrait(w, 780f, d, t, lang) to lang)
                for (lang in DisplayLang.entries) for (two in listOf(false, true)) add(landscape(640f, 360f, d, t, two) to lang)
            }
        }
        for ((win, lang) in windows) {
            val textScale = win.name.substringAfter("t=").substringBefore(' ').toFloat()
            for ((spJp, spCn) in speakers) {
                val line = DialogueLine(LineRef("test", 0, 0), sampleJp, sampleJp, spJp, spCn, read = false)
                val allNames = nameBlocks(line, lang, win.s * textScale)
                if (allNames.isEmpty()) continue
                for (voiced in listOf(false, true)) {
                    val fit = AdvPills.fitNameplate(
                        win.left + win.pad, allNames.map { it.rich.baseCodes().size * it.size }, win.pad, win.s, win.anchor, showAi = true, showVoice = voiced,
                    )
                    val names = allNames.take(fit.parts)
                    val glyphW = names.sumOf { (it.rich.baseCodes().size * it.size).toDouble() }.toFloat()
                    val p = AdvPills.layout(win.anchor, win.top, win.textTop, win.s, true, voiced, fit.shortAi, win.minPx, win.canvasWidth)
                    val nameRight = win.left + win.pad + AdvPills.nameplateWidth(glyphW * fit.glyphScale, names.size, win.pad, win.s)
                    assertTrue(
                        nameRight + AdvPills.NAME_GAP * win.s <= p.pillsLeft + 0.01f,
                        "nameplate ${names.map { it.rich.baseCodes().size }} ends at $nameRight, pills at ${p.pillsLeft} (${win.name})",
                    )
                    if (fit.shortAi) shortUses++
                    if (fit.parts < allNames.size) dropped++
                    if (fit.glyphScale < minScale) {
                        minScale = fit.glyphScale
                        worst = "${names.map { it.rich.baseCodes().size }} ${win.name} voiced=$voiced"
                    }
                    checks++
                }
            }
        }
        println("nameplate checks=$checks speakers=${speakers.size} shortLabel=$shortUses droppedJpPart=$dropped minGlyphScale=$minScale worst=$worst")
        // Names stay legible: never shrunk below 70 % even at 150 % on a 360 dp phone (≈ 105 % size).
        assertTrue(minScale >= 0.7f, "minGlyphScale=$minScale ($worst)")
    }

    @Test
    fun pkx110At100PercentKeepsFullLabelForEveryRealSpeaker() {
        assumeTrue(Corpus.available, "WA2 resources not found at ${Corpus.root}")
        val win = portrait(412f, 915f, 1f, 1f, DisplayLang.BOTH)
        for ((spJp, spCn) in Corpus.speakers) {
            val line = DialogueLine(LineRef("test", 0, 0), sampleJp, sampleJp, spJp, spCn, read = false)
            val names = nameBlocks(line, DisplayLang.BOTH, win.s)
            if (names.isEmpty()) continue
            val fit = AdvPills.fitNameplate(win.left + win.pad, names.map { it.rich.baseCodes().size * it.size }, win.pad, win.s, win.anchor, true, true)
            assertNotNull(fit)
            assertEquals(1f, fit.glyphScale, "glyphs never shrink on PKX110 @100 %: ${names.map { it.rich.baseCodes().size }}")
            assertEquals(names.size, fit.parts, "both name parts kept on PKX110 @100 %")
        }
    }
}
