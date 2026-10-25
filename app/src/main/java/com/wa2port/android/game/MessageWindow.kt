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
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang

/** Text sizes in 1280×720 stage pixels (scaled to the device by the stage scale). */
private const val MAIN_PX = 30f
private const val SECONDARY_PX = 22f
private const val NAME_PX = 25f

/** 双语 two-column layout: CN left, JP right, each at a comfortable size with an 18-char line cap. */
private const val TWO_COL_PX = 28f
private const val TWO_COL_MAX_CHARS = 18f
private const val SINGLE_MAX_CHARS = 34f
private const val NOVEL_TWO_COL_PX = 26f

/** Size steps tried (largest first) when a message does not fit the window; last resort = clip. */
private val FIT_STEPS = floatArrayOf(1f, 0.93f, 0.86f, 0.8f, 0.75f, 0.71f)

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

/** Which blocks a line produces for the current [DisplayLang] (CN missing → JP shown instead). */
private fun blocksFor(line: DialogueLine, lang: DisplayLang, scale: Float, visJp: Int, visCn: Int): List<Block> {
    val cn = line.cn?.takeIf { !it.isEmpty }
    return when {
        lang == DisplayLang.JP || cn == null -> listOf(Block(line.jp, TextLang.JP, MAIN_PX * scale, TEXT_COLOR, visJp, SINGLE_MAX_CHARS))
        lang == DisplayLang.CN -> listOf(Block(cn, TextLang.CN, MAIN_PX * scale, TEXT_COLOR, visCn, SINGLE_MAX_CHARS))
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
        val left = size.width * 0.05f
        val right = size.width * 0.95f
        val top = size.height * 0.715f
        val bottom = size.height * 0.975f
        val pad = 22f * stageScale
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
            // (28 px/18 chars → … → 20 px/26 chars) until the taller column fits.
            val colGap = 30f * stageScale
            val colW = (width - colGap) / 2f
            var (cn, jp) = cols
            for (f in FIT_STEPS) {
                val size = cols.first.size * f
                val cap = if (f >= 1f) TWO_COL_MAX_CHARS else maxOf(TWO_COL_MAX_CHARS, kotlin.math.floor(colW / size))
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
            val base = blocksFor(line, lang, scale, visibleJp, visibleCn)
            var blocks = base
            for (f in FIT_STEPS) {
                blocks = base.map {
                    val size = it.size * f
                    it.copy(size = size, maxChars = if (f >= 1f) it.maxChars else maxOf(it.maxChars, kotlin.math.floor(width / size)))
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
    val left = widthPx * 0.08f
    val width = widthPx * 0.84f
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
                val colGap = 36f * scale
                val colW = (width - colGap) / 2f
                val hCn = drawBlock(painter, cols.first, left, y, colW)
                val hJp = drawBlock(painter, cols.second, left + colW + colGap, y, colW)
                maxOf(hCn, hJp) + paraGap
            } else {
                drawBlocks(painter, blocksFor(l, lang, scale, vJp, vCn), left, y, width, gap) + paraGap
            }
        }
        if (waiting) drawWaitMark(left + width, (y - paraGap * 0.5f).coerceAtMost(size.height - 20f * stageScale), 9f * stageScale, blink)
    }
}

/** 双语 columns for NOVEL mode (line cap derived from the column width). */
private fun novelColumns(l: DialogueLine, lang: DisplayLang, scale: Float, width: Float, visJp: Int, visCn: Int): Pair<Block, Block>? {
    val size = NOVEL_TWO_COL_PX * scale
    val colW = (width - 36f * (scale.coerceAtLeast(0.01f))) / 2f
    val maxChars = kotlin.math.floor(colW / size).coerceIn(8f, TWO_COL_MAX_CHARS + 4f)
    return twoColumns(l, lang, size, maxChars, visJp, visCn)
}

/** Height of one NOVEL paragraph (two columns side by side, or stacked single-language blocks). */
private fun paragraphHeight(painter: TextPainter, l: DialogueLine, lang: DisplayLang, scale: Float, visJp: Int, visCn: Int, width: Float, gap: Float): Float {
    val cols = novelColumns(l, lang, scale, width, visJp, visCn)
    if (cols != null) {
        val colW = (width - 36f * scale) / 2f
        return maxOf(measureBlock(painter, cols.first, colW), measureBlock(painter, cols.second, colW))
    }
    return measureBlocks(painter, blocksFor(l, lang, scale, visJp, visCn), width, gap)
}
