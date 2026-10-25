package com.wa2port.android.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.image.IndexedImage
import com.wa2port.core.image.TgaDecoder
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextItem
import com.wa2port.core.text.TextLang
import com.wa2port.core.text.Wa2FontMap
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bitmap-font text renderer for WA2 [RichText], drawing glyphs straight from the game's 40-px font
 * atlases: CN from `fon.pak` (CK-GAL glyphs, incl. the `0x??7F` codes no SJIS decoder accepts) and JP
 * from `fnt.pak`. Each glyph is drawn twice — the `袋影80` outline atlas in [outlineColor], then the
 * `本体80` body atlas in the text colour — as tinted `ALPHA_8` bitmaps, so any scale/colour works.
 *
 * Layout model (all values in the caller's pixel units):
 * * `sizePx` is the em: full-width (double-byte) codes advance `sizePx·(1+letterSpacing)`,
 *   single-byte codes half that; the glyph ink fills ≈ 0.87 em (the 40-px atlas cell is drawn at
 *   4/3 em, offset so its 30-px em box lands on the layout box);
 * * each line box is `sizePx·lineSpacing` tall; glyphs sit at its bottom, the band above holds ruby
 *   (furigana, [TextItem.Ruby] readings drawn at `sizePx·rubyScale`, centred over the base);
 * * lines wrap at `maxWidth`; ruby groups and ASCII words are kept together; closing punctuation
 *   (`、。，．！？」』）…`) may hang past the edge instead of starting a new line;
 * * `\n` ([TextItem.NewLine]) breaks the line; [TextItem.PageBreak] (`\k`, a click-wait marker that
 *   is almost always followed by `\n`) does not, unless [pageBreakIsNewLine]; its glyph positions are
 *   exposed as [TextLayout.pageBreaks] so the typewriter can pause there; waits are ignored;
 * * `<F14>`/`<F16>` spans ([TextItem.Run.size] != 0) are whisper text drawn at [smallScale];
 * * CN glyphs are drawn from `CnCellTable.atlasCell(cell)` (fixes CK-GAL's off-by-one cells
 *   4320..4350); codes without a cell (stray bytes in ck-gal) advance but draw nothing.
 *
 * Glyph counting for typewriter reveal ([glyphCount], `visibleGlyphs`) counts base glyphs in order
 * (spaces included, ruby readings excluded; a reading appears with its first base glyph).
 *
 * When an atlas is missing (not merely a cell), glyphs fall back to the system font via [BilingualTextDecoder].
 * Atlases (~12 MB each, two per language) load lazily on first use; call [preload] off the UI thread
 * to avoid a hitch. Drawing must happen on one thread at a time (the UI thread).
 */
class GlyphAtlasText(
    private val assets: Wa2Assets,
    private val decoder: BilingualTextDecoder = BilingualTextDecoder(),
) {
    /** Line pitch as a multiple of the glyph size. */
    var lineSpacing: Float = 1.5f

    /** Extra advance per full-width glyph, as a multiple of the glyph size. */
    var letterSpacing: Float = 0f

    /** Ruby glyph size relative to the base glyph size. */
    var rubyScale: Float = 0.45f

    /** Default outline colour (ARGB); 0 disables the outline pass. */
    var outlineColor: Int = 0xE0000000.toInt()

    /** Whether [TextItem.PageBreak] starts a new line (engine behaviour: no). */
    var pageBreakIsNewLine: Boolean = false

    /** Glyph scale for `<Fnn>` (whisper) spans. */
    var smallScale: Float = 0.8f

    private class Atlas(val body: Bitmap, val outline: Bitmap?, val cell: Int)

    private val atlases = arrayOfNulls<Atlas>(2)
    private val tried = BooleanArray(2)
    private val glyphPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val src = Rect()
    private val dst = RectF()

    /** One positioned base glyph (box-relative; [top] is the glyph box top, [size] its edge length). */
    class PlacedGlyph(val code: Int, val x: Float, val top: Float, val size: Float, val advance: Float, val index: Int)

    /** One positioned ruby reading glyph; visible together with base glyph [baseIndex]. */
    class PlacedRuby(val code: Int, val x: Float, val top: Float, val baseIndex: Int)

    /**
     * Result of [layout]: positions relative to the text box origin.
     * [endX]/[endY] = right edge / glyph-box top of the last glyph (place a click-wait cursor there).
     * [pageBreaks] = for each `\k`, the number of glyphs before it (pause the typewriter there).
     */
    class TextLayout(
        val glyphs: List<PlacedGlyph>,
        val rubies: List<PlacedRuby>,
        val sizePx: Float,
        val height: Float,
        val lineCount: Int,
        val glyphCount: Int,
        val endX: Float,
        val endY: Float,
        val pageBreaks: List<Int>,
    ) {
        /** Right edge and glyph-box top after the first [visibleGlyphs] glyphs (typewriter cursor). */
        fun cursorAfter(visibleGlyphs: Int): Pair<Float, Float> {
            if (visibleGlyphs >= glyphs.size) return endX to endY
            if (visibleGlyphs <= 0) return (glyphs.firstOrNull()?.x ?: 0f) to (glyphs.firstOrNull()?.top ?: endY)
            val g = glyphs[visibleGlyphs - 1]
            return (g.x + g.advance) to g.top
        }
    }

    /** True when the atlas for [lang] is present (loads it if needed; blocking). */
    fun isAtlasAvailable(lang: TextLang): Boolean = atlas(lang) != null

    /** Loads the atlases for [langs] on [Dispatchers.IO]. */
    suspend fun preload(vararg langs: TextLang) = withContext(Dispatchers.IO) {
        langs.forEach { atlas(it) }
    }

    /** Number of base glyphs in [rich] (the unit of `visibleGlyphs`). */
    fun glyphCount(rich: RichText): Int {
        var n = 0
        for (it in rich.items) when (it) {
            is TextItem.Run -> n += it.codes.size
            is TextItem.Ruby -> n += it.base.size
            else -> {}
        }
        return n
    }

    /** Height [drawRich] would use for [rich] wrapped at [maxWidth] (no canvas needed). */
    fun measureHeight(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float = 0f, reflow: Boolean = false): Float =
        layout(rich, lang, maxWidth, sizePx, maxChars, reflow).height

    /**
     * Draws [rich] with the box top-left at ([x], [y]), wrapped at [maxWidth], glyph size [sizePx],
     * body [color]; only the first [visibleGlyphs] glyphs are drawn. Returns the laid-out height.
     */
    fun drawRich(
        canvas: Canvas,
        rich: RichText,
        lang: TextLang,
        x: Float,
        y: Float,
        maxWidth: Float,
        sizePx: Float,
        color: Int,
        visibleGlyphs: Int = Int.MAX_VALUE,
        outlineColor: Int = this.outlineColor,
        maxChars: Float = 0f,
        reflow: Boolean = false,
    ): Float {
        val l = layout(rich, lang, maxWidth, sizePx, maxChars, reflow)
        drawLayout(canvas, l, lang, x, y, color, visibleGlyphs, outlineColor)
        return l.height
    }

    /** Draws a precomputed [layout] (cache it across frames for long texts). */
    fun drawLayout(
        canvas: Canvas,
        layout: TextLayout,
        lang: TextLang,
        x: Float,
        y: Float,
        color: Int,
        visibleGlyphs: Int = Int.MAX_VALUE,
        outlineColor: Int = this.outlineColor,
    ) {
        val atlas = atlas(lang)
        val rubySize = layout.sizePx * rubyScale
        val passes = if (outlineColor != 0) 2 else 1
        // Outline pass for every visible glyph first, so outlines never cover neighbouring bodies.
        for (pass in 0 until passes) {
            val outline = passes == 2 && pass == 0
            val tint = if (outline) outlineColor else color
            for (g in layout.glyphs) {
                if (g.index >= visibleGlyphs) break
                drawGlyph(canvas, atlas, lang, g.code, x + g.x, y + g.top, g.size, tint, outline)
            }
            for (r in layout.rubies) {
                if (r.baseIndex >= visibleGlyphs) continue
                drawGlyph(canvas, atlas, lang, r.code, x + r.x, y + r.top, rubySize, tint, outline)
            }
        }
    }

    /**
     * Lays out [rich] (see class docs). Positions are relative to the box origin.
     *
     * [maxChars] (> 0) additionally limits a line to that many full-width characters (half-width
     * ASCII/kana count 0.5; one trailing closing punctuation may still hang). With [reflow], the
     * script's hard `\n` breaks (placed for the PC's single wide column) are kept only after
     * sentence-ending punctuation (。！？…」』） etc.) or before an opening quote; other `\n` join the
     * lines so a narrower column wraps naturally without orphan stubs.
     */
    fun layout(rich: RichText, lang: TextLang, maxWidth: Float, sizePx: Float, maxChars: Float = 0f, reflow: Boolean = false): TextLayout {
        val full = sizePx * (1f + letterSpacing)
        val pitch = sizePx * lineSpacing
        val band = (pitch - sizePx).coerceAtLeast(0f)
        val rubySize = sizePx * rubyScale

        // 1) Build unbreakable units.
        class Chunk(val codes: IntArray, val reading: IntArray?, val scale: Float = 1f, val hardBreak: Boolean = false, val pageBreak: Boolean = false) {
            val width: Float = codes.fold(0f) { acc, c -> acc + adv(c, full) * scale }
        }
        val units = ArrayList<Chunk>()
        for (item in rich.items) when (item) {
            is TextItem.Run -> {
                var i = 0
                val c = item.codes
                val sc = if (item.size != 0) smallScale else 1f
                while (i < c.size) {
                    if (isWordChar(c[i])) {
                        var j = i
                        while (j < c.size && isWordChar(c[j])) j++
                        units += Chunk(c.copyOfRange(i, j), null, sc)
                        i = j
                    } else {
                        units += Chunk(intArrayOf(c[i]), null, sc)
                        i++
                    }
                }
            }
            is TextItem.Ruby -> units += Chunk(item.base, item.reading, if (item.size != 0) smallScale else 1f)
            TextItem.NewLine -> units += Chunk(IntArray(0), null, hardBreak = true)
            TextItem.PageBreak -> units += Chunk(IntArray(0), null, hardBreak = pageBreakIsNewLine, pageBreak = true)
            is TextItem.Wait -> {}
        }

        // 2) Place them.
        val glyphs = ArrayList<PlacedGlyph>()
        val rubies = ArrayList<PlacedRuby>()
        var line = 0
        var penX = 0f
        var index = 0
        val pageBreaks = ArrayList<Int>()
        // 2a) Decide which hard breaks survive (reflow) and balance each paragraph segment so a
        // long line does not leave a 1–3 character orphan: cap = ceil(N / ceil(N / maxChars)).
        fun charsOf(u: Chunk) = u.codes.fold(0f) { acc, c -> acc + if (c < 0x100) 0.5f else 1f }
        val keptBreak = BooleanArray(units.size)
        run {
            var prevCode = -1
            for ((ui, u) in units.withIndex()) {
                if (u.hardBreak) {
                    keptBreak[ui] = if (!reflow || u.pageBreak) true else {
                        val next = units.subList(ui + 1, units.size).firstOrNull { it.codes.isNotEmpty() || it.hardBreak }
                        prevCode in SENTENCE_END || (next != null && next.codes.isNotEmpty() && next.codes[0] in OPENERS)
                    }
                } else if (u.codes.isNotEmpty()) prevCode = u.codes.last()
            }
        }
        val segCap = FloatArray(units.size) { maxChars }
        if (maxChars > 0f) {
            var start = 0
            while (start < units.size) {
                var end = start
                var n = 0f
                var lastChars = 0f
                var lastHangs = false
                while (end < units.size && !(units[end].hardBreak && keptBreak[end])) {
                    val u = units[end]
                    if (u.codes.isNotEmpty()) {
                        lastChars = charsOf(u)
                        lastHangs = u.codes.size == 1 && u.codes[0] in HANGING
                    }
                    n += charsOf(u)
                    end++
                }
                // A single trailing closer may hang past the cap, so it does not force an extra line.
                if (lastHangs) n -= lastChars
                if (n > maxChars) {
                    val lines = kotlin.math.ceil(n / maxChars)
                    val cap = minOf(maxChars, kotlin.math.ceil(n / lines) + 1f)
                    for (k in start until end) segCap[k] = cap
                }
                start = end + 1
            }
        }

        var lineChars = 0f
        var hungOnLine = false
        for ((ui, u) in units.withIndex()) {
            if (u.pageBreak) pageBreaks += index
            if (u.hardBreak) {
                if (!keptBreak[ui]) continue // soft join: let the column width / maxChars wrap instead
                line++; penX = 0f; lineChars = 0f; hungOnLine = false; continue
            }
            val uChars = charsOf(u)
            val overWidth = penX + u.width > maxWidth
            val cap = segCap[ui]
            val overChars = cap > 0f && lineChars + uChars > cap + 0.01f
            // At most one closing punctuation glyph may hang past the edge per line.
            val hang = u.codes.size == 1 && u.codes[0] in HANGING && !hungOnLine
            if (penX > 0f && (overWidth || overChars)) {
                if (hang) hungOnLine = true else { line++; penX = 0f; lineChars = 0f; hungOnLine = false }
            }
            lineChars += uChars
            val top = line * pitch + band
            val startX = penX
            val firstIndex = index
            val gs = sizePx * u.scale
            val gTop = top + (sizePx - gs) // smaller glyphs sit on the baseline
            for (c in u.codes) {
                val a = adv(c, full) * u.scale
                glyphs += PlacedGlyph(c, penX, gTop, gs, a, index++)
                penX += a
            }
            val reading = u.reading
            if (reading != null && reading.isNotEmpty()) {
                val rw = reading.fold(0f) { acc, c -> acc + if (c < 0x100) rubySize / 2 else rubySize }
                var rx = startX + (penX - startX - rw) / 2f
                val rTop = top - rubySize - band * 0.05f
                for (c in reading) {
                    rubies += PlacedRuby(c, rx, rTop, firstIndex)
                    rx += if (c < 0x100) rubySize / 2 else rubySize
                }
            }
        }
        val lineCount = if (glyphs.isEmpty() && line == 0) 0 else line + 1
        val last = glyphs.lastOrNull()
        return TextLayout(
            glyphs = glyphs,
            rubies = rubies,
            sizePx = sizePx,
            height = lineCount * pitch,
            lineCount = lineCount,
            glyphCount = index,
            endX = penX,
            endY = last?.top ?: (line * pitch + band),
            pageBreaks = pageBreaks,
        )
    }

    private fun drawGlyph(c: Canvas, atlas: Atlas?, lang: TextLang, code: Int, x: Float, top: Float, size: Float, tint: Int, outline: Boolean) {
        if (code == 0x20 || code == 0x8140) return
        val raw = Wa2FontMap.cellOf(code)
        if (raw < 0) return // stray byte without a glyph: engine draws nothing
        val cell = if (lang == TextLang.CN) decoder.cnTable.atlasCell(raw) else raw
        if (atlas != null) {
            val bmp = if (outline) atlas.outline ?: return else atlas.body
            val cs = atlas.cell
            val sx = Wa2FontMap.cellX(cell, cs)
            val sy = Wa2FontMap.cellY(cell, cs)
            src.set(sx, sy, sx + cs, sy + cs)
            // The 40-px cell holds a ~26-px glyph (ink x/y ≈ 6..31, outline 4..33): map a 30-px em
            // starting at cell offset 4 onto the [size] box so glyph spacing matches a normal font.
            val k = size / (cs * CELL_EM)
            val ox = x - cs * CELL_ORIGIN * k
            val oy = top - cs * CELL_ORIGIN * k
            dst.set(ox, oy, ox + cs * k, oy + cs * k)
            glyphPaint.color = tint
            c.drawBitmap(bmp, src, dst, glyphPaint)
            return
        }
        // System-font fallback.
        val s = decoder.char(code, lang)
        fallbackPaint.color = tint
        fallbackPaint.textSize = size * 0.92f
        if (outline) {
            fallbackPaint.style = Paint.Style.STROKE
            fallbackPaint.strokeWidth = size * 0.12f
        } else {
            fallbackPaint.style = Paint.Style.FILL
        }
        c.drawText(s, x, top + size * 0.86f, fallbackPaint)
    }

    @Synchronized
    private fun atlas(lang: TextLang): Atlas? {
        val i = lang.ordinal
        if (!tried[i]) {
            atlases[i] = loadAtlas(if (lang == TextLang.CN) "fon" else "fnt")
            tried[i] = true
        }
        return atlases[i]
    }

    private fun loadAtlas(dir: String): Atlas? = try {
        val bodyBytes = assets.read(dir, BODY_ATLAS)
        if (bodyBytes == null) {
            Log.w(TAG, "$dir/$BODY_ATLAS missing; using system font")
            null
        } else {
            val body = alphaBitmap(TgaDecoder.decodeIndexed(bodyBytes))
            val outline = assets.read(dir, OUTLINE_ATLAS)?.let { alphaBitmap(TgaDecoder.decodeIndexed(it)) }
            Atlas(body, outline, body.width / Wa2FontMap.CELLS_PER_ROW)
        }
    } catch (e: Exception) {
        Log.w(TAG, "failed to load $dir atlas", e)
        null
    }

    companion object {
        private const val TAG = "GlyphAtlasText"
        const val BODY_ATLAS = "本体80.tga"
        const val OUTLINE_ATLAS = "袋影80.tga"

        /** Fraction of an atlas cell that forms one em (advance of a full-width glyph): 30/40. */
        private const val CELL_EM = 0.75f

        /** Cell-relative offset (fraction of the cell) of the em box's top-left corner: 4/40. */
        private const val CELL_ORIGIN = 0.1f

        /** Closing punctuation allowed to hang past the right edge (SJIS codes shared by JP/CN atlases). */
        private val HANGING: Set<Int> = setOf(
            0x8141, 0x8142, 0x8143, 0x8144, 0x8145, 0x8146, 0x8147, 0x8148, 0x8149, // 、。，．・：；？！
            0x8163, 0x8164, // … ‥
            0x8166, 0x8168, 0x816A, 0x816C, 0x816E, 0x8170, 0x8172, 0x8174, 0x8176, 0x8178, 0x817A, // closers
            0x815B, 0x8160, // ー ～
            0x2C, 0x2E, 0x21, 0x3F,
        )

        /** Sentence-ending codes after which a script `\n` is kept when reflowing. */
        private val SENTENCE_END: Set<Int> = setOf(
            0x8142, 0x8148, 0x8149, 0x8163, 0x8164, // 。？！…‥
            0x8176, 0x8178, 0x816A, 0x816E, 0x8172, 0x8174, 0x817A, 0x8168, 0x8166, // 」』）］〉》】” ’
            0x2E, 0x21, 0x3F,
        )

        /** Opening quotes/brackets before which a script `\n` is kept when reflowing. */
        private val OPENERS: Set<Int> = setOf(0x8175, 0x8177, 0x8169, 0x816D, 0x8171, 0x8173, 0x8179, 0x8167, 0x8165)

        private fun adv(code: Int, full: Float) = if (code < 0x100) full / 2 else full

        private fun isWordChar(c: Int) = c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A

        /** Converts an 8-bpp atlas to `ALPHA_8` using the palette alpha as coverage (atlas colours are white). */
        private fun alphaBitmap(img: IndexedImage): Bitmap {
            val lut = ByteArray(256) { (img.palette[it] ushr 24).toByte() }
            val src = img.indices
            val a = ByteArray(src.size)
            for (i in src.indices) a[i] = lut[src[i].toInt() and 0xFF]
            val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ALPHA_8)
            if (bmp.rowBytes == img.width) {
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(a))
            } else {
                // Padded rows: copy row by row into a stride-matched buffer.
                val stride = bmp.rowBytes
                val padded = ByteArray(stride * img.height)
                for (y in 0 until img.height) System.arraycopy(a, y * img.width, padded, y * stride, img.width)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
            }
            return bmp
        }
    }
}
