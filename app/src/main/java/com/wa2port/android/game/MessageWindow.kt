package com.wa2port.android.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.wa2port.android.bridge.TextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.text.BilingualTextDecoder
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.MessageLayout.MAIN_PX
import com.wa2port.core.text.MessageLayout.NAME_PX
import com.wa2port.core.text.MessageLayout.SECONDARY_PX
import com.wa2port.core.text.MessageLayout.SINGLE_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_MAX_CHARS
import com.wa2port.core.text.MessageLayout.TWO_COL_PX
import com.wa2port.core.text.Reflow
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang
import kotlin.math.abs

// Text sizes / line caps (1280×720 stage px, scaled by the stage scale × text scale) and the
// window geometry live in [MessageLayout] so the font-size math is unit-tested in :core.

private val TEXT_COLOR = Wa2Colors.Text.toArgb()
private val SECONDARY_COLOR = Color(0xFFC9D6EA).toArgb()
private val NAME_COLOR = Wa2Colors.Name.toArgb()

private val NAME_DECODER by lazy { BilingualTextDecoder() }

/** One language block to draw: text, language, size, colour, visible glyph limit. */
internal data class Block(
    val rich: RichText,
    val lang: TextLang,
    val size: Float,
    val color: Int,
    val visible: Int,
    val maxChars: Float = 0f,
    val reflow: Reflow = Reflow.KEEP_ALL,
)

/** CN (left) and JP (right) columns of a 双语 line, or null when the line is single-language. */
private fun twoColumns(
    line: DialogueLine,
    lang: DisplayLang,
    sizePx: Float,
    maxChars: Float,
    visJp: Int,
    visCn: Int,
    reflow: Reflow = Reflow.SENTENCE,
): Pair<Block, Block>? {
    if (lang != DisplayLang.BOTH) return null
    val cn = line.cn?.takeIf { !it.isEmpty } ?: return null
    return Block(cn, TextLang.CN, sizePx, TEXT_COLOR, visCn, maxChars, reflow = reflow) to
        Block(line.jp, TextLang.JP, sizePx, TEXT_COLOR, visJp, maxChars, reflow = reflow)
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
 * When [defaultReflow] is omitted (e.g. [NovelPage]), narrow columns (`cap < SINGLE_MAX_CHARS` at
 * 115 %–150 %) use [Reflow.SENTENCE] so PC 34-char mid-sentence `\n` breaks do not leave orphans.
 */
private fun blocksFor(
    line: DialogueLine,
    lang: DisplayLang,
    scale: Float,
    width: Float,
    visJp: Int,
    visCn: Int,
    defaultReflow: Reflow? = null,
): List<Block> {
    val cn = line.cn?.takeIf { !it.isEmpty }
    val cap = MessageLayout.lineCap(width, MAIN_PX * scale, SINGLE_MAX_CHARS)
    val reflow = defaultReflow ?: if (cap < SINGLE_MAX_CHARS - 0.01f) Reflow.SENTENCE else Reflow.KEEP_ALL
    return when {
        lang == DisplayLang.JP || cn == null -> listOf(Block(line.jp, TextLang.JP, MAIN_PX * scale, TEXT_COLOR, visJp, cap, reflow))
        lang == DisplayLang.CN -> listOf(Block(cn, TextLang.CN, MAIN_PX * scale, TEXT_COLOR, visCn, cap, reflow))
        else -> listOf(
            Block(cn, TextLang.CN, MAIN_PX * scale, TEXT_COLOR, visCn, reflow = reflow),
            Block(line.jp, TextLang.JP, SECONDARY_PX * scale, SECONDARY_COLOR, visJp, reflow = reflow),
        )
    }
}

/** Speaker name parts (CN first in 双 mode; deduplicated when CN and JP plain strings are identical, e.g. `武也`). */
internal fun nameBlocks(line: DialogueLine, lang: DisplayLang, scale: Float, decoder: BilingualTextDecoder = NAME_DECODER): List<Block> {
    val jp = line.speakerJp?.takeIf { !it.isEmpty }
    val cn = line.speakerCn?.takeIf { !it.isEmpty }
    return when (lang) {
        DisplayLang.JP -> listOfNotNull(jp?.let { Block(it, TextLang.JP, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) })
        DisplayLang.CN -> listOfNotNull((cn ?: jp)?.let { Block(it, if (cn != null) TextLang.CN else TextLang.JP, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) })
        DisplayLang.BOTH -> {
            val sameName = cn != null && jp != null &&
                (cn.baseCodes().contentEquals(jp.baseCodes()) ||
                    decoder.toPlain(cn, TextLang.CN).trim() == decoder.toPlain(jp, TextLang.JP).trim())
            listOfNotNull(
                cn?.let { Block(it, TextLang.CN, NAME_PX * scale, NAME_COLOR, Int.MAX_VALUE) },
                jp?.takeUnless { sameName }?.let { Block(it, TextLang.JP, NAME_PX * 0.75f * scale, SECONDARY_COLOR, Int.MAX_VALUE) },
            )
        }
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

private fun measureRevealedBlocks(painter: TextPainter, blocks: List<Block>, width: Float, gap: Float): Float {
    var total = 0f
    for ((i, b) in blocks.withIndex()) {
        if (b.visible <= 0) break
        val rev = painter.measureRevealedHeight(b.rich, b.lang, width, b.size, b.visible, b.maxChars, b.reflow)
        if (rev <= 0f) break
        if (i > 0) total += gap
        total += rev
        if (b.visible < painter.glyphCount(b.rich)) break
    }
    return total
}

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

/** Subtle vertical scrollbar track + thumb near the right inner edge of [AdvWindow]. */
private fun DrawScope.drawAdvScrollbar(
    centerX: Float,
    trackTop: Float,
    trackBottom: Float,
    barWidth: Float,
    avail: Float,
    contentHeight: Float,
    scrollY: Float,
    maxScrollPx: Float,
) {
    val trackH = trackBottom - trackTop
    if (trackH <= 8f || contentHeight <= 0f || maxScrollPx <= 0f) return
    val x = centerX - barWidth * 0.5f
    val radius = CornerRadius(barWidth * 0.5f)
    drawRoundRect(
        Wa2Colors.AccentDim.copy(alpha = 0.28f),
        Offset(x, trackTop),
        Size(barWidth, trackH),
        radius,
    )
    val thumbH = ((avail / contentHeight).coerceIn(0.2f, 1f) * trackH).coerceAtMost(trackH)
    val frac = (scrollY / maxScrollPx).coerceIn(0f, 1f)
    val thumbTop = trackTop + (trackH - thumbH) * frac
    drawRoundRect(
        Wa2Colors.Accent.copy(alpha = 0.65f),
        Offset(x, thumbTop),
        Size(barWidth, thumbH),
        radius,
    )
}

/**
 * Geometry of the Japanese text block last drawn by [AdvWindow] (stage-box px), captured during draw
 * for tap-to-analyze, plus the `🔊 重听` button's touch target ([voiceRect], null when the line has no
 * voice) and direct in-window vertical scroll state for 5+ line monologues at large font scales.
 */
class AdvHitBox {
    class JpBox(
        val rich: RichText,
        val x: Float,
        val y: Float,
        val width: Float,
        val sizePx: Float,
        val maxChars: Float,
        val reflow: Reflow,
        val visibleGlyphs: Int,
        val clipTop: Float,
        val clipBottom: Float,
    )

    @Volatile
    var jp: JpBox? = null

    /**
     * Touch target of the `🔊 重听` pill (stage-box px, ≥ 48 dp square, entirely above the first text
     * line so it never steals a glyph tap), or null when the current line has no voice.
     */
    @Volatile
    var voiceRect: Rect? = null

    /** Minimum horizontal glyph hit tolerance (px; 12 dp, set during draw). */
    @Volatile
    var minTolPx: Float = 0f

    /** Bounds of the ADV window box in stage-box px (`left, top, right, bottom`). */
    @Volatile
    var windowRect: Rect = Rect.Zero

    /** Visible text area height inside [AdvWindow] (stage-box px). */
    @Volatile
    var availHeight: Float = 0f

    /** Full laid-out content height of the current line (stage-box px). */
    @Volatile
    var contentHeight: Float = 0f

    /** Maximum vertical scroll offset (`(contentHeight - availHeight).coerceAtLeast(0f)`). */
    @Volatile
    var maxScrollPx: Float = 0f

    /** Current vertical scroll offset (stage-box px; 0 = top). Backed by FloatState to redraw Canvas on drag. */
    var scrollY by mutableFloatStateOf(0f)

    /** True once the player manually drags the current line inside [AdvWindow] (pauses auto-follow). */
    @Volatile
    var userScrolled: Boolean = false

    private var scrollLine: DialogueLine? = null
    private var scrollLang: DisplayLang? = null
    private var scrollScale: Float = -1f
    private var nearBottomWhileTyping: Boolean = false

    internal fun syncLine(line: DialogueLine, lang: DisplayLang, textScale: Float) {
        if (line !== scrollLine || lang != scrollLang || textScale != scrollScale) {
            scrollLine = line
            scrollLang = lang
            scrollScale = textScale
            scrollY = 0f
            userScrolled = false
            nearBottomWhileTyping = false
            maxScrollPx = 0f
            contentHeight = 0f
        }
    }

    internal fun resolveScrollY(
        maxScroll: Float,
        avail: Float,
        revealedHeight: Float,
        waiting: Boolean,
        stageScale: Float,
    ): Float {
        maxScrollPx = maxScroll
        if (maxScroll <= 0.5f) {
            nearBottomWhileTyping = false
            if (scrollY != 0f) scrollY = 0f
            return 0f
        }
        var cur = scrollY.coerceIn(0f, maxScroll)
        if (!userScrolled) {
            if (!waiting && revealedHeight >= avail * 0.7f) {
                nearBottomWhileTyping = true
            }
            if (!waiting || nearBottomWhileTyping) {
                val target = (revealedHeight - avail).coerceIn(0f, maxScroll)
                if (target > cur + 0.5f) {
                    val step = maxOf((target - cur) * 0.25f, 3f * stageScale)
                    cur = (cur + step).coerceAtMost(target)
                } else if (!waiting && target < cur - 0.5f) {
                    cur = target
                }
                if (waiting && cur >= target - 0.5f) {
                    cur = target
                    nearBottomWhileTyping = false
                }
            }
        } else {
            nearBottomWhileTyping = false
        }
        if (abs(cur - scrollY) > 0.05f) {
            scrollY = cur
        }
        return cur
    }

    /** Scrolls by finger vertical delta [dyFinger] (positive = finger moved down → scroll towards top). */
    fun scrollBy(dyFinger: Float) {
        val max = maxScrollPx
        if (max <= 0.5f) return
        userScrolled = true
        nearBottomWhileTyping = false
        scrollY = (scrollY - dyFinger).coerceIn(0f, max)
    }

    fun clearGeometry() {
        jp = null
        voiceRect = null
        windowRect = Rect.Zero
        maxScrollPx = 0f
    }

    /** Glyph index ([RichText.baseCodes] order) of the JP glyph under [p] (stage-box px), or −1. */
    fun glyphAt(painter: TextPainter, p: Offset): Int {
        val b = jp ?: return -1
        val tol = maxOf(b.sizePx * 0.35f, minTolPx)
        if (p.x < b.x - tol || p.x > b.x + b.width + tol || p.y < b.clipTop || p.y > b.clipBottom) return -1
        return painter.glyphIndexAt(b.rich, TextLang.JP, b.width, b.sizePx, b.maxChars, b.reflow, p.x - b.x, p.y - b.y, b.visibleGlyphs)
    }
}

/**
 * ADV message window drawn over the stage (fills the stage box).
 *
 * Font size is strictly constant at the player's chosen [textScale]:
 * * 双语 (`BOTH`): two side-by-side columns (CN left, JP right, `28 px × textScale`, ≤19 chars/line
 *   at 100 %, 4-line window capacity); tries [Reflow.SENTENCE] first and falls back per column to
 *   [Reflow.JOIN_ALL] before scrolling.
 * * 单语 (`JP` / `CN`): one column (`30 px × textScale`, ≤34 chars/line at 100 %, 3-line window
 *   capacity); tries [Reflow.KEEP_ALL] → [Reflow.SENTENCE] → [Reflow.JOIN_ALL].
 * * When a long monologue at 115 %–150 % still exceeds the window height, [AdvHitBox] enables
 *   direct vertical touch scrolling inside the window with typewriter auto-follow and a scrollbar.
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
    hitBox: AdvHitBox? = null,
) {
    val state = hitBox ?: remember { AdvHitBox() }
    DisposableEffect(state) {
        onDispose { state.clearGeometry() }
    }
    val blink = rememberBlink()
    Canvas(modifier) {
        state.syncLine(line, lang, textScale)
        state.jp = null
        state.voiceRect = null
        state.minTolPx = 12.dp.toPx()
        val scale = size.height / 720f * textScale
        val stageScale = size.height / 720f
        val twoCol = lang == DisplayLang.BOTH && line.cn?.isEmpty == false
        val left = size.width * MessageLayout.ADV_LEFT
        val right = size.width * MessageLayout.ADV_RIGHT
        val top = size.height * MessageLayout.advTop(textScale, twoCol)
        val bottom = size.height * MessageLayout.ADV_BOTTOM
        val pad = MessageLayout.ADV_PAD * stageScale
        state.windowRect = Rect(left, top, right, bottom)
        drawRoundRect(
            Color(0xFF0A1428).copy(alpha = opacity), Offset(left, top), Size(right - left, bottom - top),
            CornerRadius(14f * stageScale),
        )
        drawRoundRect(
            Wa2Colors.AccentDim.copy(alpha = 0.6f * opacity + 0.2f), Offset(left, top), Size(right - left, bottom - top),
            CornerRadius(14f * stageScale), style = Stroke(1.5f * stageScale),
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
        // Pin Line 0 origin unconditionally so spoken dialogue and narration never jump vertically.
        val textTop = top + pad * 1.25f
        // 🔊 重听: pill at the right end of the nameplate row; 1 tap replays the voice (no advance).
        if (line.ref.voice != null) {
            val r = drawVoicePill(right - pad, top, textTop, stageScale, opacity)
            state.voiceRect = r
        }
        val avail = bottom - pad * 0.6f - textTop
        state.availHeight = avail
        val width = right - left - pad * 2
        val gap = 8f * stageScale
        val cols = twoColumns(line, lang, TWO_COL_PX * scale, TWO_COL_MAX_CHARS, visibleJp, visibleCn)
        if (cols != null) {
            val colGap = MessageLayout.advColumnGap(textScale) * stageScale
            val colW = (width - colGap) / 2f
            val cap = MessageLayout.lineCap(colW, cols.first.size, TWO_COL_MAX_CHARS)
            val cnSent = cols.first.copy(maxChars = cap, reflow = Reflow.SENTENCE)
            val jpSent = cols.second.copy(maxChars = cap, reflow = Reflow.SENTENCE)
            val hCnSent = measureBlock(painter, cnSent, colW)
            val hJpSent = measureBlock(painter, jpSent, colW)
            val cn = if (hCnSent <= avail) cnSent else cnSent.copy(reflow = Reflow.JOIN_ALL)
            val jp = if (hJpSent <= avail) jpSent else jpSent.copy(reflow = Reflow.JOIN_ALL)
            val hCn = if (cn === cnSent) hCnSent else measureBlock(painter, cn, colW)
            val hJp = if (jp === jpSent) hJpSent else measureBlock(painter, jp, colW)
            val contentH = maxOf(hCn, hJp)
            state.contentHeight = contentH
            val maxScroll = if (contentH > avail + 0.5f) (contentH - avail).coerceAtLeast(0f) else 0f
            val revealedH = if (maxScroll > 0.5f && !state.userScrolled) {
                maxOf(
                    painter.measureRevealedHeight(cn.rich, TextLang.CN, colW, cn.size, cn.visible, cn.maxChars, cn.reflow),
                    painter.measureRevealedHeight(jp.rich, TextLang.JP, colW, jp.size, jp.visible, jp.maxChars, jp.reflow),
                )
            } else {
                0f
            }
            val curScroll = state.resolveScrollY(maxScroll, avail, revealedH, waiting, stageScale)
            val cnScrollY = curScroll.coerceAtMost((hCn - avail).coerceAtLeast(0f))
            val jpScrollY = curScroll.coerceAtMost((hJp - avail).coerceAtLeast(0f))
            val clipBottom = if (maxScroll > 0.5f) bottom - pad * 0.55f else bottom - pad * 0.3f
            val cnClipTop = if (cnScrollY > 0.5f) textTop else textTop - 3f * stageScale
            val jpClipTop = if (jpScrollY > 0.5f) textTop else textTop - 3f * stageScale
            val divX = left + pad + colW + colGap / 2f
            drawLine(
                Wa2Colors.AccentDim.copy(alpha = 0.45f), Offset(divX, textTop + 4f * stageScale),
                Offset(divX, bottom - pad * 0.8f), strokeWidth = 1.2f * stageScale,
            )
            clipRect(left, cnClipTop, right, clipBottom) {
                drawBlock(painter, cn, left + pad, textTop - cnScrollY, colW)
            }
            clipRect(left, jpClipTop, right, clipBottom) {
                drawBlock(painter, jp, divX + colGap / 2f, textTop - jpScrollY, colW)
            }
            state.jp = AdvHitBox.JpBox(jp.rich, divX + colGap / 2f, textTop - jpScrollY, colW, jp.size, jp.maxChars, jp.reflow, jp.visible, jpClipTop, clipBottom)
            if (maxScroll > 0.5f) {
                drawAdvScrollbar(right - pad * 0.42f, textTop + 2f * stageScale, clipBottom - 12f * stageScale, 3.5f * stageScale, avail, contentH, curScroll, maxScroll)
            }
        } else {
            val base = blocksFor(line, lang, scale, width, visibleJp, visibleCn, defaultReflow = Reflow.KEEP_ALL)
            var blocks = base
            var contentH = measureBlocks(painter, blocks, width, gap)
            if (contentH > avail) {
                for (policy in listOf(Reflow.SENTENCE, Reflow.JOIN_ALL)) {
                    blocks = base.map { it.copy(reflow = policy) }
                    contentH = measureBlocks(painter, blocks, width, gap)
                    if (contentH <= avail) break
                }
            }
            state.contentHeight = contentH
            val maxScroll = if (contentH > avail + 0.5f) (contentH - avail).coerceAtLeast(0f) else 0f
            val revealedH = if (maxScroll > 0.5f && !state.userScrolled) {
                measureRevealedBlocks(painter, blocks, width, gap)
            } else {
                0f
            }
            val curScroll = state.resolveScrollY(maxScroll, avail, revealedH, waiting, stageScale)
            val blockScrollY = curScroll.coerceAtMost((contentH - avail).coerceAtLeast(0f))
            val clipBottom = if (maxScroll > 0.5f) bottom - pad * 0.55f else bottom - pad * 0.3f
            val clipTop = if (blockScrollY > 0.5f) textTop else textTop - 3f * stageScale
            clipRect(left, clipTop, right, clipBottom) {
                drawBlocks(painter, blocks, left + pad, textTop - blockScrollY, width, gap)
            }
            var y = textTop - blockScrollY
            blocks.forEachIndexed { i, b ->
                if (i > 0) y += gap
                if (b.lang == TextLang.JP) {
                    state.jp = AdvHitBox.JpBox(b.rich, left + pad, y, width, b.size, b.maxChars, b.reflow, b.visible, clipTop, clipBottom)
                }
                y += measureBlock(painter, b, width)
            }
            if (maxScroll > 0.5f) {
                drawAdvScrollbar(right - pad * 0.42f, textTop + 2f * stageScale, clipBottom - 12f * stageScale, 3.5f * stageScale, avail, contentH, curScroll, maxScroll)
            }
        }
        if (waiting) drawWaitMark(right - pad * 0.45f, bottom - pad * 0.36f, 6.5f * stageScale, blink)
    }
}

private val VOICE_LABEL_PAINT by lazy {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Wa2Colors.Text.toArgb()
        textAlign = android.graphics.Paint.Align.LEFT
    }
}

/**
 * Draws the `🔊 重听` pill whose right edge is [pillRight], straddling the window top edge [top]
 * like the name box, and returns its touch target: at least 48 dp × 48 dp, bottom-aligned just
 * above [textTop] (the first text line) and growing upwards into the stage, so it never covers a
 * glyph.
 */
private fun DrawScope.drawVoicePill(pillRight: Float, top: Float, textTop: Float, stageScale: Float, opacity: Float): Rect {
    val h = 36f * stageScale
    val w = 92f * stageScale
    val l = pillRight - w
    val t = top - h * 0.55f
    val shape = CornerRadius(h / 2f)
    drawRoundRect(Color(0xFF16284A).copy(alpha = 0.45f + 0.5f * opacity), Offset(l, t), Size(w, h), shape)
    drawRoundRect(Wa2Colors.Accent.copy(alpha = 0.85f), Offset(l, t), Size(w, h), shape, style = Stroke(1.5f * stageScale))
    // Speaker icon: body + cone + two sound waves.
    val cy = t + h / 2f
    val ix = l + 14f * stageScale
    val u = 5f * stageScale
    val accent = Wa2Colors.Accent
    val speaker = Path().apply {
        moveTo(ix, cy - u * 0.8f); lineTo(ix + u, cy - u * 0.8f); lineTo(ix + u * 2.2f, cy - u * 1.8f)
        lineTo(ix + u * 2.2f, cy + u * 1.8f); lineTo(ix + u, cy + u * 0.8f); lineTo(ix, cy + u * 0.8f); close()
    }
    drawPath(speaker, accent)
    val waveCx = ix + u * 1.2f
    for (k in 1..2) {
        val rr = u * (1.6f + 0.9f * k)
        drawArc(
            accent, -45f, 90f, useCenter = false, topLeft = Offset(waveCx - rr, cy - rr), size = Size(rr * 2f, rr * 2f),
            style = Stroke(1.6f * stageScale),
        )
    }
    // Label (system font: the game glyph atlas has no UI glyphs).
    val label = "重听"
    drawIntoCanvas { c ->
        VOICE_LABEL_PAINT.textSize = 17f * stageScale
        val fm = VOICE_LABEL_PAINT.fontMetrics
        c.nativeCanvas.drawText(label, l + 44f * stageScale, cy - (fm.ascent + fm.descent) / 2f, VOICE_LABEL_PAINT)
    }
    // Touch target ≥ 48 dp in both directions, never reaching the first text line.
    val minPx = 48.dp.toPx()
    val tw = maxOf(w + 16f * stageScale, minPx)
    val bottom = textTop - 2f * stageScale
    val th = maxOf(bottom - (t - 8f * stageScale), minPx)
    val cx = l + w / 2f
    val rl = (cx - tw / 2f).coerceAtMost(size.width - tw).coerceAtLeast(0f)
    return Rect(rl, bottom - th, rl + tw, bottom)
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
