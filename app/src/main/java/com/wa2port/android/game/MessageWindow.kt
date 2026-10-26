package com.wa2port.android.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.wa2port.android.bridge.TextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.MessageLayout.MAIN_PX
import com.wa2port.core.text.MessageLayout.NAME_PX
import com.wa2port.core.text.MessageLayout.SECONDARY_PX
import com.wa2port.core.text.MessageLayout.SINGLE_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_PX
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang

// Text sizes / line caps (1280×720 stage px, scaled by the stage scale × text scale) and the
// window geometry live in [MessageLayout] so the font-size math is unit-tested in :core.

/** Size steps tried (largest first) when a message does not fit the window; last resort = clip. */
private val FIT_STEPS = MessageLayout.FIT_STEPS

private val TEXT_COLOR = Wa2Colors.Text.toArgb()
private val SECONDARY_COLOR = Color(0xFFC9D6EA).toArgb()
private val NAME_COLOR = Wa2Colors.Name.toArgb()

/** One language block to draw: text, language, size, colour, visible glyph limit. */
private data class Block(
    val rich: RichText,
    val lang: TextLang,
    val size: Float,
    val color: Int,
    val visible: Int,
    val maxChars: Float = 0f,
    val reflow: Boolean = false,
)

/** CN (left) and JP (right) columns of a 双语 line, or null when the line is single-language. */
private fun twoColumns(line: DialogueLine, lang: DisplayLang, sizePx: Float, maxChars: Float, visJp: Int, visCn: Int): Pair<Block, Block>? {
    if (lang != DisplayLang.BOTH) return null
    val cn = line.cn?.takeIf { !it.isEmpty } ?: return null
    return Block(cn, TextLang.CN, sizePx, TEXT_COLOR, visCn, maxChars, reflow = true) to
        Block(line.jp, TextLang.JP, sizePx, TEXT_COLOR, visJp, maxChars, reflow = true)
}

private fun DrawScope.drawBlock(painter: TextPainter, b: Block, x: Float, y: Float, width: Float): Float {
    var h = 0f
    drawIntoCanvas { c -> h = painter.draw(c.nativeCanvas, b.rich, b.lang, x, y, width, b.size, b.color, b.visible, b.maxChars, b.reflow) }
    return h
}

private fun measureBlock(painter: TextPainter, b: Block, width: Float): Float =
    painter.measureHeight(b.rich, b.lang, width, b.size, b.maxChars, b.reflow)

/**
 * Which blocks a line produces for the current [DisplayLang] (CN missing → JP shown instead).
 * Single-language lines are capped at [SINGLE_MAX_CHARS] or what [width] holds, whichever is less.
 */
private fun blocksFor(line: DialogueLine, lang: DisplayLang, scale: Float, width: Float, visJp: Int, visCn: Int): List<Block> {
    val cn = line.cn?.takeIf { !it.isEmpty }
    val cap = MessageLayout.lineCap(width, MAIN_PX * scale, SINGLE_MAX_CHARS, stepped = false)
    return when {
        lang == DisplayLang.JP || cn == null -> listOf(Block(line.jp, TextLang.JP, MAIN_PX * scale, TEXT_COLOR, visJp, cap))
        lang == DisplayLang.CN -> listOf(Block(cn, TextLang.CN, MAIN_PX * scale, TEXT_COLOR, visCn, cap))
        else -> listOf(
            Block(cn, TextLang.CN, MAIN_PX * scale, TEXT_COLOR, visCn),
            Block(line.jp, TextLang.JP, SECONDARY_PX * scale, SECONDARY_COLOR, visJp),
        )
    }
}

/** Speaker name parts (CN first in 双 mode). */
private fun nameBlocks(line: DialogueLine, lang: DisplayLang, scale: Float): List<Block> {
    val jp = line.speakerJp?.takeIf { !it.isEmpty }
    val cn = line.speakerCn?.takeIf { !it.isEmpty }
    return when (lang) {
        DisplayLang.JP -> listOfNotNull(jp?.let { Block(it, TextLang.JP, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) })
        DisplayLang.CN -> listOfNotNull((cn ?: jp)?.let { Block(it, if (cn != null) TextLang.CN else TextLang.JP, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) })
        DisplayLang.BOTH -> listOfNotNull(
            cn?.let { Block(it, TextLang.CN, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) },
            jp?.let { Block(it, TextLang.JP, NAME_PX * 0.75f * scale, SECONDARY_COLOR, Int.MAX_VALUE) },
        )
    }
}

private fun DrawScope.drawBlocks(painter: TextPainter, blocks: List<Block>, x: Float, y0: Float, width: Float, gap: Float): Float {
    var y = y0
    drawIntoCanvas { c ->
        blocks.forEachIndexed { i, b ->
            if (i > 0) y += gap
            y += painter.draw(c.nativeCanvas, b.rich, b.lang, x, y, width, b.size, b.color, b.visible, b.maxChars, b.reflow)
        }
    }
    return y - y0
}

private fun measureBlocks(painter: TextPainter, blocks: List<Block>, width: Float, gap: Float): Float =
    blocks.mapIndexed { i, b -> painter.measureHeight(b.rich, b.lang, width, b.size, b.maxChars, b.reflow) + if (i > 0) gap else 0f }.sum()

private fun scaled(blocks: List<Block>, f: Float) = blocks.map { it.copy(size = it.size * f) }

/** Blinking ▼ shown when the line is complete and the engine waits for a tap. */
@Composable
private fun rememberBlink(): Float {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blinkAlpha")
    return a
}

private fun DrawScope.drawWaitMark(x: Float, y: Float, s: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(x - s, y - s * 0.6f); lineTo(x + s, y - s * 0.6f); lineTo(x, y + s * 0.6f); close()
    }
    drawPath(p, Wa2Colors.Accent.copy(alpha = alpha))
}

/**
 * ADV message window drawn over the stage (fills the stage box). 双语: two side-by-side columns
 * (CN left, JP right, 28 px, ≤18 full-width chars per line, script `\n` reflowed at sentence ends);
 * single language: one 30 px column (≤34 chars). Steps the size down (widening the line cap) until it fits, clipping as a last resort; speaker name
 * box above the window.
 *
 * [textScale] (font size setting): the line cap never exceeds what a column holds at the scaled
 * size ([MessageLayout.lineCap]), and the window grows upwards ([MessageLayout.advTop]) so three
 * lines fit at 115 %–150 % without the fit loop shrinking them back.
 */
@Composable
fun AdvWindow(
    painter: TextPainter,
    line: DialogueLine,
    lang: DisplayLang,
    visibleJp: Int,
    visibleCn: Int,
    waiting: Boolean,
    opacity: Float,
    textScale: Float,
    modifier: Modifier,
) {
    val blink = rememberBlink()
    Canvas(modifier) {
        val scale = size.height / 720f * textScale
        val stageScale = size.height / 720f
        val left = size.width * MessageLayout.ADV_LEFT
        val right = size.width * MessageLayout.ADV_RIGHT
        val top = size.height * MessageLayout.advTop(textScale)
        val bottom = size.height * MessageLayout.ADV_BOTTOM
        val pad = MessageLayout.ADV_PAD * stageScale
        drawRoundRect(
            Color(0xFF0A1428).copy(alpha = opacity), Offset(left, top), Size(right - left, bottom - top),
            CornerRadius(14f * stageScale),
        )
        drawRoundRect(
            Wa2Colors.AccentDim.copy(alpha = 0.6f * opacity + 0.2f), Offset(left, top), Size(right - left, bottom - top),
            CornerRadius(14f * stageScale), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * stageScale),
        )
        // Speaker name box.
        val names = nameBlocks(line, lang, scale)
        if (names.isNotEmpty()) {
            val nh = NAME_PX * scale * 1.5f
            val nw = names.sumOf { (painter.glyphCount(it.rich) * it.size).toDouble() }.toFloat() + pad * 2 + (names.size - 1) * 12f * stageScale
            val ny = top - nh * 0.55f
            drawRoundRect(Color(0xFF16284A).copy(alpha = 0.35f + 0.6f * opacity), Offset(left + pad, ny), Size(nw, nh), CornerRadius(8f * stageScale))
            var nx = left + pad * 2
            drawIntoCanvas { c ->
                names.forEach { b ->
                    val ty = ny + (nh - b.size * 1.2f) / 2f
                    painter.draw(c.nativeCanvas, b.rich, b.lang, nx, ty, size.width, b.size, b.color, Int.MAX_VALUE)
                    nx += painter.glyphCount(b.rich) * b.size + 12f * stageScale
                }
            }
        }
        // Text, stepped down in size (FIT_STEPS) until it fits the window; clipped as a last resort.
        val textTop = top + pad * (if (names.isNotEmpty()) 1.25f else 1f)
        val avail = bottom - pad * 0.6f - textTop
        val width = right - left - pad * 2
        val gap = 8f * stageScale
        val cols = twoColumns(line, lang, TWO_COL_PX * scale, TWO_COL_MAX_CHARS, visibleJp, visibleCn)
        val clipBottom = bottom - pad * 0.3f
        if (cols != null) {
            // 双语: CN left column | JP right column, separated by a subtle divider. Long messages
            // step the size down and widen the per-line cap to what the column holds at that size
            // (28 px/18 chars → … → 20 px/26 chars) until the taller column fits. At the nominal size
            // the cap is min(18, what the column holds) so large text never double-wraps.
            val colGap = MessageLayout.advColumnGap(textScale) * stageScale
            val colW = (width - colGap) / 2f
            var (cn, jp) = cols
            for (f in FIT_STEPS) {
                val size = cols.first.size * f
                val cap = MessageLayout.lineCap(colW, size, TWO_COL_MAX_CHARS, stepped = f < 1f)
                cn = cols.first.copy(size = size, maxChars = cap)
                jp = cols.second.copy(size = size, maxChars = cap)
                if (maxOf(measureBlock(painter, cn, colW), measureBlock(painter, jp, colW)) <= avail) break
            }
            val divX = left + pad + colW + colGap / 2f
            drawLine(
                Wa2Colors.AccentDim.copy(alpha = 0.45f), Offset(divX, textTop + 4f * stageScale),
                Offset(divX, bottom - pad * 0.8f), strokeWidth = 1.2f * stageScale,
            )
            clipRect(left, top, right, clipBottom) {
                drawBlock(painter, cn, left + pad, textTop, colW)
                drawBlock(painter, jp, divX + colGap / 2f, textTop, colW)
            }
        } else {
            val base = blocksFor(line, lang, scale, width, visibleJp, visibleCn)
            var blocks = base
            for (f in FIT_STEPS) {
                blocks = base.map {
                    val size = it.size * f
                    it.copy(size = size, maxChars = if (f >= 1f || it.maxChars <= 0f) it.maxChars else MessageLayout.lineCap(width, size, it.maxChars, stepped = true))
                }
                if (measureBlocks(painter, blocks, width, gap) <= avail) break
            }
            clipRect(left, top, right, clipBottom) { drawBlocks(painter, blocks, left + pad, textTop, width, gap) }
        }
        if (waiting) drawWaitMark(right - pad, bottom - pad * 0.9f, 9f * stageScale, blink)
    }
}

/**
 * NOVEL mode: full-stage translucent page accumulating [page] lines (last one is typing).
 * Calls [onOverflow] when the page no longer fits so the session starts a new page.
 */
@Composable
fun NovelPage(
    painter: TextPainter,
    page: List<DialogueLine>,
    lang: DisplayLang,
    visibleJp: Int,
    visibleCn: Int,
    waiting: Boolean,
    opacity: Float,
    textScale: Float,
    widthPx: Float,
    heightPx: Float,
    onOverflow: () -> Unit,
    modifier: Modifier,
) {
    val blink = rememberBlink()
    val stageScale = heightPx / 720f
    val scale = stageScale * textScale
    val left = widthPx * MessageLayout.NOVEL_LEFT
    val width = widthPx * MessageLayout.NOVEL_WIDTH
    val top = heightPx * 0.07f
    val avail = heightPx * 0.86f
    val gap = 6f * stageScale
    val paraGap = 18f * stageScale
    // Measure the full page (all lines fully revealed) to detect overflow.
    val overflow = remember(page, lang, widthPx, heightPx, textScale) {
        if (page.size <= 1) false else {
            val total = page.sumOf { l ->
                val names = nameBlocks(l, lang, scale * 0.8f)
                val nameH = if (names.isNotEmpty()) NAME_PX * scale * 0.8f * 1.3f else 0f
                (paragraphHeight(painter, l, lang, scale, Int.MAX_VALUE, Int.MAX_VALUE, width, gap) + nameH + paraGap).toDouble()
            }
            total > avail
        }
    }
    LaunchedEffect(overflow, page) { if (overflow) onOverflow() }
    Canvas(modifier) {
        drawRect(Color(0xFF050A16).copy(alpha = 0.35f + 0.55f * opacity))
        var y = top
        page.forEachIndexed { i, l ->
            val last = i == page.lastIndex
            val names = nameBlocks(l, lang, scale * 0.8f)
            if (names.isNotEmpty()) {
                drawIntoCanvas { c ->
                    var nx = left
                    names.forEach { b ->
                        painter.draw(c.nativeCanvas, b.rich, b.lang, nx, y, width, b.size, b.color, Int.MAX_VALUE)
                        nx += painter.glyphCount(b.rich) * b.size + 10f * stageScale
                    }
                }
                y += NAME_PX * scale * 0.8f * 1.3f
            }
            val vJp = if (last) visibleJp else Int.MAX_VALUE
            val vCn = if (last) visibleCn else Int.MAX_VALUE
            val cols = novelColumns(l, lang, scale, width, vJp, vCn)
            y += if (cols != null) {
                val colW = MessageLayout.novelColumnWidth(width, scale)
                val colGap = width - colW * 2f
                val hCn = drawBlock(painter, cols.first, left, y, colW)
                val hJp = drawBlock(painter, cols.second, left + colW + colGap, y, colW)
                maxOf(hCn, hJp) + paraGap
            } else {
                drawBlocks(painter, blocksFor(l, lang, scale, width, vJp, vCn), left, y, width, gap) + paraGap
            }
        }
        if (waiting) drawWaitMark(left + width, (y - paraGap * 0.5f).coerceAtMost(size.height - 20f * stageScale), 9f * stageScale, blink)
    }
}

/**
 * 双语 columns for NOVEL mode. The line cap is whatever the column holds at the scaled size (no
 * upper cap, so 70 %–150 % all fill the column cleanly; see [MessageLayout.novelLineCap]).
 */
private fun novelColumns(l: DialogueLine, lang: DisplayLang, scale: Float, width: Float, visJp: Int, visCn: Int): Pair<Block, Block>? {
    val size = MessageLayout.NOVEL_TWO_COL_PX * scale
    return twoColumns(l, lang, size, MessageLayout.novelLineCap(width, scale), visJp, visCn)
}

/** Height of one NOVEL paragraph (two columns side by side, or stacked single-language blocks). */
private fun paragraphHeight(painter: TextPainter, l: DialogueLine, lang: DisplayLang, scale: Float, visJp: Int, visCn: Int, width: Float, gap: Float): Float {
    val cols = novelColumns(l, lang, scale, width, visJp, visCn)
    if (cols != null) {
        val colW = MessageLayout.novelColumnWidth(width, scale)
        return maxOf(measureBlock(painter, cols.first, colW), measureBlock(painter, cols.second, colW))
    }
    return measureBlocks(painter, blocksFor(l, lang, scale, width, visJp, visCn), width, gap)
}
