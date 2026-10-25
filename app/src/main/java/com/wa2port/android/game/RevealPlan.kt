package com.wa2port.android.game

import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import kotlin.math.ceil
import kotlin.math.max

/**
 * Typewriter plan for one bilingual line. The text is split into segments at `\k` (wait-for-click
 * inside a message); each segment reveals JP and CN proportionally so both finish together.
 * Boundaries are cumulative glyph indices in the painters' unit (base codes; ruby readings excluded).
 */
class RevealPlan(jp: RichText, cn: RichText?) {
    private val jpBounds = bounds(jp)
    private val cnBounds = cn?.let { bounds(it) } ?: intArrayOf(0)

    /** Number of click-separated segments (≥ 1). */
    val segments: Int = max(1, max(jpBounds.size, cnBounds.size) - 1)

    /** True when the raw line ends with `\k` (novel mode starts a new page after it). */
    val endsWithPageBreak: Boolean = endsWithBreak(jp) || (cn != null && endsWithBreak(cn))

    val jpTotal: Int get() = jpBounds.last()
    val cnTotal: Int get() = cnBounds.last()

    private fun segLen(b: IntArray, s: Int): Int = if (s + 1 < b.size) b[s + 1] - b[s] else 0

    /** Glyphs of [lang] visible at segment [seg] with in-segment progress [frac] (0..1). */
    fun visible(lang: TextLang, seg: Int, frac: Float): Int {
        val b = if (lang == TextLang.JP) jpBounds else cnBounds
        if (seg >= b.size - 1) return b.last()
        return b[seg] + ceil(frac.coerceIn(0f, 1f) * segLen(b, seg)).toInt()
    }

    /** Reveal time of [seg] for the languages being shown at [glyphsPerSec]. */
    fun durationMs(seg: Int, showJp: Boolean, showCn: Boolean, glyphsPerSec: Int): Float {
        val n = max(if (showJp) segLen(jpBounds, seg) else 0, if (showCn) segLen(cnBounds, seg) else 0)
        return n * 1000f / glyphsPerSec.coerceAtLeast(1)
    }

    private companion object {
        fun bounds(r: RichText): IntArray {
            val out = arrayListOf(0)
            var n = 0
            for (item in r.items) when (item) {
                is TextItem.Run -> n += item.codes.size
                is TextItem.Ruby -> n += item.base.size
                TextItem.PageBreak -> out += n
                else -> {}
            }
            out += n
            // Drop empty trailing segments (a trailing `\k` must not cost an extra tap).
            while (out.size > 2 && out[out.size - 1] == out[out.size - 2]) out.removeAt(out.size - 1)
            return out.toIntArray()
        }

        fun endsWithBreak(r: RichText): Boolean =
            r.items.lastOrNull { it !is TextItem.Wait && !(it is TextItem.Run && it.codes.all { c -> c < 0x20 }) } == TextItem.PageBreak
    }
}
