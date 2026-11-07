package com.wa2port.android.game

import androidx.compose.ui.geometry.Rect

/**
 * Pure geometry of the message-window rim pills `[✨ AI解读] [🔊 重听]` drawn by the landscape
 * [AdvWindow], and of the lone `🔊 重听` pill on the portrait reading deck ([AdvWindow] / [NovelPage]
 * with a frame; see [showsRimAi]), plus the speaker-nameplate fit that keeps the nameplate clear of
 * them. All values are canvas px; `s` = px per stage px.
 *
 * * Both pills straddle the window top edge like the nameplate (`t = top − 0.55·h`); the right-most
 *   pill ends at the anchor `right − pad`. `🔊 重听` (voiced lines) is the right-most; `✨ AI解读`
 *   (any line with Japanese text) sits [PILL_GAP] to its left, or at the anchor on unvoiced lines.
 * * Touch targets are ≥ `minPx` (48 dp) in both directions, bottom-aligned 2 stage px above the
 *   first text line (never stealing a glyph tap) and growing upwards into the stage, kept inside
 *   `0 … canvasWidth`. Side by side, they split at the midpoint of the pill gap (shared edge, zero
 *   overlap) and only extend outwards to reach `minPx`.
 */
internal object AdvPills {
    /** Pill height (stage px). */
    const val PILL_H = 36f

    /** `🔊 重听` pill width (stage px). */
    const val VOICE_W = 92f

    /** `✨ AI解读` pill width (stage px). */
    const val AI_W = 108f

    /** `✨ 解读` pill width (stage px), used only when the nameplate needs the room. */
    const val AI_SHORT_W = 86f

    /** Visual gap between `✨ AI解读` and `🔊 重听` (stage px). */
    const val PILL_GAP = 10f

    /** Minimum clearance between the nameplate right edge and the left-most pill (stage px). */
    const val NAME_GAP = 10f

    /** Gap between the CN and JP parts of a 双语 nameplate (stage px; see [AdvWindow]). */
    const val NAME_PART_GAP = 12f

    /** Outward touch slop beyond a pill edge before the 48 dp minimum kicks in (stage px). */
    const val TOUCH_EXT = 8f

    const val AI_LABEL = "AI解读"
    const val AI_SHORT_LABEL = "解读"

    /**
     * Geometry of one draw pass. [aiPill] / [voicePill] are the drawn pill bounds, [aiTouch] /
     * [voiceTouch] the touch targets (null when that pill is not shown), [aiLabel] the label text.
     */
    data class Layout(
        val aiPill: Rect?,
        val voicePill: Rect?,
        val aiTouch: Rect?,
        val voiceTouch: Rect?,
        val aiLabel: String,
    ) {
        /** Left edge of the left-most drawn pill (or `Float.POSITIVE_INFINITY` when none is shown). */
        val pillsLeft: Float get() = aiPill?.left ?: voicePill?.left ?: Float.POSITIVE_INFINITY
    }

    /**
     * Nameplate fit: [shortAi] → `解读` label; [parts] = leading name parts to draw (the 双语 JP
     * secondary part is dropped only as a last resort); [glyphScale] multiplies every name glyph size (≤ 1).
     */
    data class NameFit(val shortAi: Boolean, val glyphScale: Float, val parts: Int)

    /** `✨ AI解读` is shown for every line with Japanese text; `🔊 重听` for every voiced line. */
    fun showsAi(jpEmpty: Boolean): Boolean = !jpEmpty

    /**
     * Whether a window draws the `✨ AI解读` rim pill: only the landscape [AdvWindow] (`frame == null`).
     * The portrait reading deck ([AdvWindow] / [NovelPage] with a frame) keeps only `🔊 重听` on its
     * rim; its `✨ AI` key lives in the bottom thumb bar ([QuickBarLayout.showsAiKey]).
     */
    fun showsRimAi(jpEmpty: Boolean, portrait: Boolean): Boolean = showsAi(jpEmpty) && !portrait

    /** Total width of the shown pills, gap included (canvas px). */
    fun pillsWidth(showAi: Boolean, showVoice: Boolean, shortAi: Boolean, s: Float): Float {
        val ai = if (showAi) (if (shortAi) AI_SHORT_W else AI_W) else 0f
        val voice = if (showVoice) VOICE_W else 0f
        val gap = if (showAi && showVoice) PILL_GAP else 0f
        return (ai + voice + gap) * s
    }

    /** Nameplate box width for name glyphs [glyphWidth] wide in [parts] parts ([AdvWindow] formula). */
    fun nameplateWidth(glyphWidth: Float, parts: Int, pad: Float, s: Float): Float =
        glyphWidth + pad * 2f + (parts - 1).coerceAtLeast(0) * NAME_PART_GAP * s

    /** Smallest name glyph scale tried before dropping the 双语 JP secondary name part. */
    const val NAME_MIN_SCALE = 0.75f

    /**
     * Picks the AI label, name parts and glyph scale so a nameplate starting at [nameLeft] (natural
     * per-part glyph widths [partWidths], CN first in 双语) ends at least [NAME_GAP] before the
     * left-most pill anchored at [anchorRight]. Ladder: full `AI解读` label at full size → short `解读`
     * label → name glyphs scaled down to [NAME_MIN_SCALE] → (双语) primary name part only, scaled as
     * needed → whichever keeps the larger glyphs. The result never overlaps the pills.
     */
    fun fitNameplate(
        nameLeft: Float,
        partWidths: List<Float>,
        pad: Float,
        s: Float,
        anchorRight: Float,
        showAi: Boolean,
        showVoice: Boolean,
    ): NameFit {
        val all = partWidths.size
        val glyphAll = partWidths.sum()
        fun limit(short: Boolean) = anchorRight - pillsWidth(showAi, showVoice, short, s) - NAME_GAP * s - nameLeft
        if (nameplateWidth(glyphAll, all, pad, s) <= limit(false)) return NameFit(false, 1f, all)
        val short = showAi
        val lim = limit(short)
        val kAll = scaleToFit(lim, glyphAll, all, pad, s)
        if (kAll >= NAME_MIN_SCALE || all <= 1) return NameFit(short, kAll, all)
        val kFirst = scaleToFit(lim, partWidths.first(), 1, pad, s)
        return if (kFirst >= kAll) NameFit(short, kFirst, 1) else NameFit(short, kAll, all)
    }

    private fun scaleToFit(limit: Float, glyphWidth: Float, parts: Int, pad: Float, s: Float): Float {
        if (glyphWidth <= 0f) return 1f
        val room = limit - nameplateWidth(0f, parts, pad, s)
        return (room / glyphWidth).coerceIn(0f, 1f)
    }

    /** Top of both pills: straddling the window top edge [top] like the nameplate. */
    fun pillTop(top: Float, s: Float): Float = top - PILL_H * s * 0.55f

    /**
     * Pill bounds and touch targets for a window whose rim anchor is [anchorRight] (`right − pad`),
     * top edge [top] and first text line [textTop].
     */
    fun layout(
        anchorRight: Float,
        top: Float,
        textTop: Float,
        s: Float,
        showAi: Boolean,
        showVoice: Boolean,
        shortAi: Boolean,
        minPx: Float,
        canvasWidth: Float,
    ): Layout {
        val label = if (shortAi) AI_SHORT_LABEL else AI_LABEL
        val t = pillTop(top, s)
        val b = t + PILL_H * s
        val voicePill = if (showVoice) Rect(anchorRight - VOICE_W * s, t, anchorRight, b) else null
        val aiRight = voicePill?.let { it.left - PILL_GAP * s } ?: anchorRight
        val aiPill = if (showAi) Rect(aiRight - (if (shortAi) AI_SHORT_W else AI_W) * s, t, aiRight, b) else null
        val touchBottom = textTop - 2f * s
        val touchTop = minOf(t - TOUCH_EXT * s, touchBottom - minPx)
        val ext = TOUCH_EXT * s
        if (aiPill != null && voicePill != null) {
            val mid = (aiPill.right + voicePill.left) / 2f
            val vRight = maxOf(voicePill.right + ext, mid + minPx).coerceAtMost(canvasWidth)
            val split = minOf(mid, vRight - minPx)
            val aLeft = minOf(aiPill.left - ext, split - minPx).coerceAtLeast(0f)
            return Layout(
                aiPill, voicePill,
                Rect(aLeft, touchTop, split, touchBottom),
                Rect(split, touchTop, vRight, touchBottom),
                label,
            )
        }
        fun single(p: Rect): Rect {
            val tw = maxOf(p.width + 2f * ext, minPx)
            val l = (p.center.x - tw / 2f).coerceAtMost(canvasWidth - tw).coerceAtLeast(0f)
            return Rect(l, touchTop, l + tw, touchBottom)
        }
        return Layout(aiPill, voicePill, aiPill?.let(::single), voicePill?.let(::single), label)
    }
}
