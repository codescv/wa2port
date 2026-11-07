package com.wa2port.android.game

import com.wa2port.core.ai.RubyToken

/**
 * Pure flow model of the AI breakdown sheet's furigana lines (chunk card header surface, skeleton
 * sentence, interactive sentence header), unit-tested without Compose.
 *
 * A line is a list of unbreakable [Cell]s laid out by a `FlowRow`; every cell reserves the full
 * ruby headroom above its base row, so a wrapped row's furigana can never overlap the row above,
 * and a cell is `max(base width, ruby width)` wide, so a reading wider than its kanji (`やなぎはら`
 * over `柳原`, `うけたまわ` over `承`) widens its own cell instead of colliding with a neighbour's.
 */
internal object RubyFlow {
    /** Characters that must not begin a line (kinsoku shori). */
    const val NO_LINE_START = "、。，．,.!！?？…‥」』）)】〕〉》ー～ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ々"

    /** Horizontal padding of a ruby cell, in base ems (keeps adjacent readings apart). */
    const val RUBY_CELL_PAD_EM = 0.1f

    /**
     * One unbreakable flow item: [base] text with optional furigana [ruby]. [echo] marks the
     * dimmed `（reading）` glyphs a too-wide ruby cell is degraded into (see [cells]).
     */
    data class Cell(val base: String, val ruby: String? = null, val echo: Boolean = false) {
        val hasRuby: Boolean get() = !ruby.isNullOrBlank()
    }

    /** Width of [s] in ems: half-width (Latin, half-width kana) counts 0.5, everything else 1. */
    fun emUnits(s: String): Float {
        var n = 0f
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += if (cp < 0x0100 || cp in 0xFF61..0xFF9F) 0.5f else 1f
            i += Character.charCount(cp)
        }
        return n
    }

    /** Estimated cell width in base ems; [rubyScale] = ruby font size / base font size. */
    fun cellEms(c: Cell, rubyScale: Float): Float =
        if (c.hasRuby) maxOf(emUnits(c.base), emUnits(c.ruby!!) * rubyScale) + 2f * RUBY_CELL_PAD_EM else emUnits(c.base)

    /** Estimated single-line width (base ems) of [cells]. */
    fun lineEms(cells: List<Cell>, rubyScale: Float): Float = cells.sumOf { cellEms(it, rubyScale).toDouble() }.toFloat()

    private fun codePoints(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out += s.substring(i, i + n)
            i += n
        }
        return out
    }

    /**
     * Flow cells of [tokens] ([fallback] split per character when there are none): ruby-bearing
     * tokens stay one cell (reading centred over the base); ruby-less runs break per character so
     * long kana runs wrap; a closing mark ([NO_LINE_START]) is glued to the preceding ruby-less
     * cell so it never starts a line. A ruby cell wider than [maxEms] (base ems the line holds)
     * degrades into per-character base cells followed by dimmed `（reading）` echo cells, so even an
     * ultra-long word never overflows or clips inside its card.
     */
    fun cells(tokens: List<RubyToken>, fallback: String, maxEms: Float = Float.MAX_VALUE, rubyScale: Float = 0.5f): List<Cell> {
        val raw = ArrayList<Cell>()
        if (tokens.isEmpty()) {
            codePoints(fallback).forEach { raw += Cell(it) }
        } else {
            for (t in tokens) {
                val r = t.ruby?.trim()
                if (!r.isNullOrEmpty()) {
                    val cell = Cell(t.base, r)
                    if (cellEms(cell, rubyScale) <= maxEms) {
                        raw += cell
                    } else {
                        codePoints(t.base).forEach { raw += Cell(it) }
                        codePoints("（$r）").forEach { raw += Cell(it, echo = true) }
                    }
                } else {
                    codePoints(t.base).forEach { raw += Cell(it) }
                }
            }
        }
        val out = ArrayList<Cell>(raw.size)
        for (c in raw) {
            val prev = out.lastOrNull()
            val closer = !c.hasRuby && c.base in noLineStartSet
            if (closer && prev != null && !prev.hasRuby && prev.echo == c.echo) {
                out[out.size - 1] = prev.copy(base = prev.base + c.base)
            } else {
                out += c
            }
        }
        return out
    }

    private val noLineStartSet: Set<String> by lazy { codePoints(NO_LINE_START).toSet() }

    /**
     * One interactive sentence-header group: [ruby] over the characters starting at [starts]
     * (each its own tappable box), or — when [echo] is set — the non-interactive dimmed
     * `（reading）` of a ruby word too wide for the line (its characters then follow as plain groups).
     */
    data class SentenceGroup(val ruby: String?, val starts: List<Int>, val echo: String? = null)

    /**
     * Interactive sentence-header groups: ruby tokens stay grouped, every other character is its
     * own group, and a closing mark is glued to a preceding ruby-less group (kinsoku). A ruby group
     * wider than [maxEms] (each character [charPadEm] padded; reading at [rubyScale]) degrades
     * into per-character groups followed by a wrapping `（reading）` echo, so nothing clips.
     */
    fun sentenceGroups(
        tokens: List<RubyToken>,
        text: String,
        maxEms: Float = Float.MAX_VALUE,
        rubyScale: Float = 9f / 24f,
        charPadEm: Float = 2f / 24f,
    ): List<SentenceGroup> {
        val out = ArrayList<SentenceGroup>()
        fun add(g: SentenceGroup) {
            val prev = out.lastOrNull()
            val single = g.ruby == null && g.echo == null && g.starts.size == 1 && text[g.starts[0]] in NO_LINE_START
            if (single && prev != null && prev.ruby == null && prev.echo == null) {
                out[out.size - 1] = prev.copy(starts = prev.starts + g.starts)
            } else {
                out += g
            }
        }
        for (t in tokens) {
            val starts = ArrayList<Int>()
            var i = t.span.start
            while (i < t.span.end && i < text.length) {
                starts += i
                i += Character.charCount(text.codePointAt(i))
            }
            val ruby = t.ruby
            if (ruby == null) {
                starts.forEach { add(SentenceGroup(null, listOf(it))) }
                continue
            }
            val baseEms = starts.sumOf { st ->
                (emUnits(String(Character.toChars(text.codePointAt(st)))) + charPadEm).toDouble()
            }.toFloat()
            val ems = maxOf(baseEms, emUnits(ruby) * rubyScale) + 2f * RUBY_CELL_PAD_EM
            if (ems <= maxEms || ruby.isBlank()) {
                add(SentenceGroup(ruby, starts))
            } else {
                starts.forEach { add(SentenceGroup(null, listOf(it))) }
                out += SentenceGroup(null, emptyList(), echo = "（${ruby.trim()}）")
            }
        }
        return out
    }

    /**
     * Chunk card header: whether the kana [readingWidth] fits beside the surface line
     * ([surfaceWidth]) with [gap] in [available] width (any unit). When it does not, the reading
     * flows onto its own line(s) below the surface instead of squeezing / clipping either.
     */
    fun readingBeside(surfaceWidth: Float, readingWidth: Float, available: Float, gap: Float): Boolean =
        surfaceWidth + gap + readingWidth <= available
}
