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
import com.wa2port.core.text.DeckLayout
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
 * Portrait reading-deck frame for [AdvWindow] / [NovelPage], in canvas (content-box) px. [scale] =
 * px per stage px (`DeckLayout.DECK_SCALE × density`): every stage-px constant (pad, glyph sizes,
 * name box, voice pill, stroke) renders at that scale. `null` frame = today's landscape geometry
 * (`w·ADV_LEFT … h·advTop`, `scale = h / 720`).
 */
data class AdvFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val scale: Float,
    val bothMode: DeckLayout.BothMode,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Pure portrait-deck text geometry shared by [AdvWindow] and [NovelPage] (canvas px). */
internal object DeckText {
    /** First text line top inside a frame starting at [frameTop] (pinned like the landscape window). */
    fun textTop(frameTop: Float, pad: Float): Float = frameTop + pad * 1.25f

    /** Visible text height between [textTop] and the frame [frameBottom]. */
    fun avail(frameBottom: Float, pad: Float, textTop: Float): Float = frameBottom - pad * 0.6f - textTop

    /** Top of the stacked JP block: CN block height [hCn] + [gapPx] below [textTop], scrolled by [scrollY]. */
    fun jpTop(textTop: Float, hCn: Float, gapPx: Float, scrollY: Float): Float = textTop + hCn + gapPx - scrollY

    /** Auto-follow height of a stacked CN + JP pair revealing in parallel. */
    fun stackedRevealedHeight(hCn: Float, gapPx: Float, revealedCn: Float, revealedJp: Float, visibleJp: Int): Float =
        if (visibleJp > 0) hCn + gapPx + revealedJp else revealedCn

    /** Portrait ▼ wait mark centre y: [markGapPx] below the text end, never below the frame padding. */
    fun waitMarkY(contentEnd: Float, markGapPx: Float, frameBottom: Float, pad: Float): Float =
        minOf(contentEnd + markGapPx, frameBottom - pad * 0.36f)
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

    /** True while the geometry belongs to a portrait deck frame ([AdvFrame]; content-box px, NOVEL scroll allowed). */
    @Volatile
    var frameMode: Boolean = false

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
            // Nothing to scroll (e.g. after a rotation gave the line more room): resume auto-follow.
            userScrolled = false
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
        frameMode = false
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
 * * Portrait ([frame] != null): the window is the reading deck below the stage at
 *   [AdvFrame.scale]; 双语 is STACKED (CN block above JP block, full width, cap
 *   `DeckLayout.lineCap`, [Reflow.SENTENCE] → [Reflow.JOIN_ALL]) unless the deck is wide enough for
 *   TWO_COL; single language starts at [Reflow.SENTENCE].
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
    frame: AdvFrame? = null,
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
        state.frameMode = frame != null
        state.minTolPx = 12.dp.toPx()
        val stageScale = frame?.scale ?: (size.height / 720f)
        val scale = stageScale * textScale
        val twoCol = lang == DisplayLang.BOTH && line.cn?.isEmpty == false
        val stacked = frame != null && twoCol && frame.bothMode == DeckLayout.BothMode.STACKED
        val left = frame?.left ?: (size.width * MessageLayout.ADV_LEFT)
        val right = frame?.right ?: (size.width * MessageLayout.ADV_RIGHT)
        val top = frame?.top ?: (size.height * MessageLayout.advTop(textScale, twoCol))
        val bottom = frame?.bottom ?: (size.height * MessageLayout.ADV_BOTTOM)
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
        val textTop = DeckText.textTop(top, pad)
        // 🔊 重听: pill at the right end of the nameplate row; 1 tap replays the voice (no advance).
        if (line.ref.voice != null) {
            val r = drawVoicePill(right - pad, top, textTop, stageScale, opacity)
            state.voiceRect = r
        }
        val avail = DeckText.avail(bottom, pad, textTop)
        state.availHeight = avail
        val width = right - left - pad * 2
        val gap = 8f * stageScale
        // Portrait: bottom of the drawn text (canvas px) for the ▼ wait mark.
        var contentEnd = textTop
        val cols = twoColumns(line, lang, TWO_COL_PX * scale, TWO_COL_MAX_CHARS, visibleJp, visibleCn)
        if (cols != null && stacked) {
            val em = cols.first.size
            val cap = DeckLayout.lineCap(width, em, stageScale)
            val gapPx = DeckLayout.STACKED_GAP_DP.dp.toPx()
            val cnSent = cols.first.copy(maxChars = cap, reflow = Reflow.SENTENCE)
            val jpSent = cols.second.copy(maxChars = cap, reflow = Reflow.SENTENCE)
            val hCnSent = measureBlock(painter, cnSent, width)
            val hJpSent = measureBlock(painter, jpSent, width)
            val join = hCnSent + gapPx + hJpSent > avail
            val cn = if (join) cnSent.copy(reflow = Reflow.JOIN_ALL) else cnSent
            val jp = if (join) jpSent.copy(reflow = Reflow.JOIN_ALL) else jpSent
            val hCn = if (join) measureBlock(painter, cn, width) else hCnSent
            val hJp = if (join) measureBlock(painter, jp, width) else hJpSent
            val contentH = hCn + gapPx + hJp
            state.contentHeight = contentH
            val maxScroll = if (contentH > avail + 0.5f) (contentH - avail).coerceAtLeast(0f) else 0f
            val revealedH = if (maxScroll > 0.5f && !state.userScrolled) {
                DeckText.stackedRevealedHeight(
                    hCn, gapPx,
                    painter.measureRevealedHeight(cn.rich, TextLang.CN, width, cn.size, cn.visible, cn.maxChars, cn.reflow),
                    painter.measureRevealedHeight(jp.rich, TextLang.JP, width, jp.size, jp.visible, jp.maxChars, jp.reflow),
                    visibleJp,
                )
            } else {
                0f
            }
            val curScroll = state.resolveScrollY(maxScroll, avail, revealedH, waiting, stageScale)
            val clipBottom = if (maxScroll > 0.5f) bottom - pad * 0.55f else bottom - pad * 0.3f
            val clipTop = if (curScroll > 0.5f) textTop else textTop - 3f * stageScale
            val cnY = textTop - curScroll
            val jpY = DeckText.jpTop(textTop, hCn, gapPx, curScroll)
            clipRect(left, clipTop, right, clipBottom) {
                drawBlock(painter, cn, left + pad, cnY, width)
                val divY = cnY + hCn + gapPx / 2f
                drawLine(
                    Wa2Colors.AccentDim.copy(alpha = 0.45f), Offset(left + pad, divY), Offset(right - pad, divY),
                    strokeWidth = 1.2f * stageScale,
                )
                drawBlock(painter, jp, left + pad, jpY, width)
            }
            state.jp = AdvHitBox.JpBox(jp.rich, left + pad, jpY, width, jp.size, jp.maxChars, jp.reflow, jp.visible, clipTop, clipBottom)
            if (maxScroll > 0.5f) {
                drawAdvScrollbar(right - pad * 0.42f, textTop + 2f * stageScale, clipBottom - 12f * stageScale, 3.5f * stageScale, avail, contentH, curScroll, maxScroll)
            }
            contentEnd = cnY + contentH
        } else if (cols != null) {
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
            contentEnd = textTop - curScroll + contentH
        } else {
            // Portrait: full-width deck cap (one hanging closer allowed) and no KEEP_ALL step (PC
            // 34-char breaks would leave orphans at caps 18–21).
            val base = if (frame == null) {
                blocksFor(line, lang, scale, width, visibleJp, visibleCn, defaultReflow = Reflow.KEEP_ALL)
            } else {
                blocksFor(line, lang, scale, width, visibleJp, visibleCn, defaultReflow = Reflow.SENTENCE)
                    .map { it.copy(maxChars = DeckLayout.lineCap(width, it.size, stageScale)) }
            }
            var blocks = base
            var contentH = measureBlocks(painter, blocks, width, gap)
            if (contentH > avail) {
                val ladder = if (frame == null) listOf(Reflow.SENTENCE, Reflow.JOIN_ALL) else listOf(Reflow.JOIN_ALL)
                for (policy in ladder) {
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
            contentEnd = textTop - blockScrollY + contentH
        }
        if (waiting) {
            val markY = if (frame == null) bottom - pad * 0.36f else DeckText.waitMarkY(contentEnd, 10.dp.toPx(), bottom, pad)
            drawWaitMark(right - pad * 0.45f, markY, 6.5f * stageScale, blink)
        }
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
    return voiceTouchRect(pillRight, top, textTop, stageScale, 48.dp.toPx(), size.width)
}

/**
 * Touch target of the `🔊 重听` pill drawn by [drawVoicePill]: ≥ [minPx] (48 dp) in both directions,
 * bottom-aligned 2 stage px above [textTop] (never reaching the first text line), growing upwards,
 * horizontally centred on the pill and kept inside `0 … canvasWidth`.
 */
internal fun voiceTouchRect(pillRight: Float, top: Float, textTop: Float, stageScale: Float, minPx: Float, canvasWidth: Float): Rect {
    val h = 36f * stageScale
    val w = 92f * stageScale
    val l = pillRight - w
    val t = top - h * 0.55f
    val tw = maxOf(w + 16f * stageScale, minPx)
    val bottom = textTop - 2f * stageScale
    val th = maxOf(bottom - (t - 8f * stageScale), minPx)
    val cx = l + w / 2f
    val rl = (cx - tw / 2f).coerceAtMost(canvasWidth - tw).coerceAtLeast(0f)
    return Rect(rl, bottom - th, rl + tw, bottom)
}

/**
 * NOVEL mode: full-stage translucent page accumulating [page] lines (last one is typing).
 * Calls [onOverflow] when the page no longer fits so the session starts a new page.
 *
 * Portrait ([frame] != null): the page lives in the reading deck below the stage — round-rect
 * panel inside [frame] only (the stage stays clean), text clipped to the frame, 双语 STACKED
 * paragraphs (CN block then JP block at `NOVEL_TWO_COL_PX`, deck cap, [Reflow.SENTENCE] →
 * [Reflow.JOIN_ALL]), a single paragraph taller than the frame scrolls through [hitBox], and the
 * `🔊 重听` pill sits on the frame top (sets [AdvHitBox.voiceRect]).
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
    frame: AdvFrame? = null,
    hitBox: AdvHitBox? = null,
) {
    if (hitBox != null) {
        DisposableEffect(hitBox) {
            onDispose { hitBox.clearGeometry() }
        }
    }
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
    val overflow = remember(page, lang, widthPx, heightPx, textScale, frame) {
        if (page.size <= 1) {
            false
        } else if (frame != null) {
            NovelDeck(painter, lang, textScale, frame).overflows(page)
        } else {
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
        if (frame != null) {
            // Until trimPage() lands, show only the newest line so the overflowing frame never flashes.
            val shown = if (overflow && page.size > 1) listOf(page.last()) else page
            drawNovelDeck(NovelDeck(painter, lang, textScale, frame), shown, visibleJp, visibleCn, waiting, opacity, hitBox, blink)
            return@Canvas
        }
        // Landscape NOVEL keeps no hit geometry (e.g. left over from a portrait deck before a rotation).
        if (hitBox != null && (hitBox.frameMode || hitBox.voiceRect != null)) hitBox.clearGeometry()
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

/** One portrait NOVEL paragraph: speaker name blocks + text blocks (STACKED / single) or 双语 TWO_COL columns. */
private class DeckParagraph(val names: List<Block>, val blocks: List<Block>, val cols: Pair<Block, Block>?)

/**
 * Portrait NOVEL page metrics inside the reading-deck [frame] (canvas px): 双语 + STACKED → CN block
 * then JP block at `NOVEL_TWO_COL_PX` with the deck cap; single language → one block at `MAIN_PX`
 * with the deck cap; 双语 + TWO_COL → [novelColumns]. Each paragraph uses [Reflow.SENTENCE], or
 * [Reflow.JOIN_ALL] when it alone is taller than the frame.
 */
private class NovelDeck(val painter: TextPainter, val lang: DisplayLang, val textScale: Float, val frame: AdvFrame) {
    val stageScale = frame.scale
    val scale = stageScale * textScale
    val pad = MessageLayout.ADV_PAD * stageScale
    val left = frame.left + pad
    val width = frame.width - 2f * pad
    val textTop = DeckText.textTop(frame.top, pad)
    val avail = DeckText.avail(frame.bottom, pad, textTop)
    val gap = 6f * stageScale
    val paraGap = 18f * stageScale
    val nameH = NAME_PX * scale * 0.8f * 1.3f
    val colW = MessageLayout.novelColumnWidth(width, scale)

    private fun build(l: DialogueLine, visJp: Int, visCn: Int, reflow: Reflow): DeckParagraph {
        val names = nameBlocks(l, lang, scale * 0.8f)
        val cn = l.cn?.takeIf { !it.isEmpty }
        if (lang == DisplayLang.BOTH && cn != null) {
            if (frame.bothMode == DeckLayout.BothMode.TWO_COL) return DeckParagraph(names, emptyList(), novelColumns(l, lang, scale, width, visJp, visCn))
            val em = MessageLayout.NOVEL_TWO_COL_PX * scale
            val cap = DeckLayout.lineCap(width, em, stageScale)
            return DeckParagraph(
                names,
                listOf(
                    Block(cn, TextLang.CN, em, TEXT_COLOR, visCn, cap, reflow),
                    Block(l.jp, TextLang.JP, em, TEXT_COLOR, visJp, cap, reflow),
                ),
                null,
            )
        }
        val em = MAIN_PX * scale
        val cap = DeckLayout.lineCap(width, em, stageScale)
        val block = if (lang == DisplayLang.CN && cn != null) {
            Block(cn, TextLang.CN, em, TEXT_COLOR, visCn, cap, reflow)
        } else {
            Block(l.jp, TextLang.JP, em, TEXT_COLOR, visJp, cap, reflow)
        }
        return DeckParagraph(names, listOf(block), null)
    }

    fun paragraph(l: DialogueLine, visJp: Int, visCn: Int): DeckParagraph {
        val sentence = build(l, visJp, visCn, Reflow.SENTENCE)
        if (sentence.cols != null) return sentence
        val full = measureBlocks(painter, sentence.blocks, width, gap)
        return if (full <= avail) sentence else build(l, visJp, visCn, Reflow.JOIN_ALL)
    }

    fun textHeight(p: DeckParagraph): Float = if (p.cols != null) {
        maxOf(measureBlock(painter, p.cols.first, colW), measureBlock(painter, p.cols.second, colW))
    } else {
        measureBlocks(painter, p.blocks, width, gap)
    }

    fun height(p: DeckParagraph): Float = (if (p.names.isNotEmpty()) nameH else 0f) + textHeight(p)

    /** Auto-follow height of the (last, typing) paragraph [p]: name + revealed text. */
    fun revealedHeight(p: DeckParagraph): Float {
        val nh = if (p.names.isNotEmpty()) nameH else 0f
        val rev = when {
            p.cols != null -> maxOf(
                painter.measureRevealedHeight(p.cols.first.rich, p.cols.first.lang, colW, p.cols.first.size, p.cols.first.visible, p.cols.first.maxChars, p.cols.first.reflow),
                painter.measureRevealedHeight(p.cols.second.rich, p.cols.second.lang, colW, p.cols.second.size, p.cols.second.visible, p.cols.second.maxChars, p.cols.second.reflow),
            )
            p.blocks.size == 2 -> {
                val (cn, jp) = p.blocks
                DeckText.stackedRevealedHeight(
                    measureBlock(painter, cn, width), gap,
                    painter.measureRevealedHeight(cn.rich, cn.lang, width, cn.size, cn.visible, cn.maxChars, cn.reflow),
                    painter.measureRevealedHeight(jp.rich, jp.lang, width, jp.size, jp.visible, jp.maxChars, jp.reflow),
                    jp.visible,
                )
            }
            else -> measureRevealedBlocks(painter, p.blocks, width, gap)
        }
        return nh + rev
    }

    /** True when the fully revealed [page] no longer fits the frame (session starts a new page). */
    fun overflows(page: List<DialogueLine>): Boolean {
        val total = page.sumOf { l -> height(paragraph(l, Int.MAX_VALUE, Int.MAX_VALUE)).toDouble() } + paraGap * (page.size - 1)
        return total > avail
    }
}

/** Draws the portrait NOVEL [shown] lines into [deck] (panel, clipped text, scroll, 🔊 pill, ▼). */
private fun DrawScope.drawNovelDeck(
    deck: NovelDeck,
    shown: List<DialogueLine>,
    visibleJp: Int,
    visibleCn: Int,
    waiting: Boolean,
    opacity: Float,
    hitBox: AdvHitBox?,
    blink: Float,
) {
    val frame = deck.frame
    val stageScale = deck.stageScale
    val pad = deck.pad
    val textTop = deck.textTop
    val avail = deck.avail
    val painter = deck.painter
    val lastLine = shown.last()
    hitBox?.let {
        it.syncLine(lastLine, deck.lang, deck.textScale)
        it.jp = null
        it.voiceRect = null
        it.frameMode = true
        it.minTolPx = 12.dp.toPx()
        it.windowRect = Rect(frame.left, frame.top, frame.right, frame.bottom)
        it.availHeight = avail
    }
    // Deck panel inside the frame only: the stage above stays clean.
    drawRoundRect(
        Color(0xFF0A1428).copy(alpha = opacity), Offset(frame.left, frame.top), Size(frame.width, frame.height),
        CornerRadius(14f * stageScale),
    )
    drawRoundRect(
        Wa2Colors.AccentDim.copy(alpha = 0.6f * opacity + 0.2f), Offset(frame.left, frame.top), Size(frame.width, frame.height),
        CornerRadius(14f * stageScale), style = Stroke(1.5f * stageScale),
    )
    val paragraphs = shown.mapIndexed { i, l ->
        val last = i == shown.lastIndex
        deck.paragraph(l, if (last) visibleJp else Int.MAX_VALUE, if (last) visibleCn else Int.MAX_VALUE)
    }
    val heights = paragraphs.map { deck.height(it) }
    val gaps = deck.paraGap * (heights.size - 1).coerceAtLeast(0)
    val contentH = heights.sum() + gaps
    val maxScroll = if (contentH > avail + 0.5f) (contentH - avail).coerceAtLeast(0f) else 0f
    val curScroll = if (hitBox != null) {
        hitBox.contentHeight = contentH
        val revealedH = if (maxScroll > 0.5f && !hitBox.userScrolled) {
            heights.dropLast(1).sum() + gaps + deck.revealedHeight(paragraphs.last())
        } else {
            0f
        }
        hitBox.resolveScrollY(maxScroll, avail, revealedH, waiting, stageScale)
    } else {
        0f
    }
    val clipBottom = if (maxScroll > 0.5f) frame.bottom - pad * 0.55f else frame.bottom - pad * 0.3f
    val clipTop = if (curScroll > 0.5f) textTop else textTop - 3f * stageScale
    var y = textTop - curScroll
    clipRect(frame.left, clipTop, frame.right, clipBottom) {
        paragraphs.forEachIndexed { i, p ->
            if (i > 0) y += deck.paraGap
            if (p.names.isNotEmpty()) {
                drawIntoCanvas { c ->
                    var nx = deck.left
                    p.names.forEach { b ->
                        painter.draw(c.nativeCanvas, b.rich, b.lang, nx, y, deck.width, b.size, b.color, Int.MAX_VALUE)
                        nx += painter.glyphCount(b.rich) * b.size + 10f * stageScale
                    }
                }
                y += deck.nameH
            }
            y += if (p.cols != null) {
                val colGap = deck.width - deck.colW * 2f
                maxOf(
                    drawBlock(painter, p.cols.first, deck.left, y, deck.colW),
                    drawBlock(painter, p.cols.second, deck.left + deck.colW + colGap, y, deck.colW),
                )
            } else {
                drawBlocks(painter, p.blocks, deck.left, y, deck.width, deck.gap)
            }
        }
    }
    if (maxScroll > 0.5f) {
        drawAdvScrollbar(frame.right - pad * 0.42f, textTop + 2f * stageScale, clipBottom - 12f * stageScale, 3.5f * stageScale, avail, contentH, curScroll, maxScroll)
    }
    // 🔊 重听 on the frame top (the quick bar never adds 🔊 in portrait).
    if (lastLine.ref.voice != null) {
        val r = drawVoicePill(frame.right - pad, frame.top, textTop, stageScale, opacity)
        hitBox?.voiceRect = r
    }
    if (waiting) drawWaitMark(frame.right - pad * 0.45f, DeckText.waitMarkY(y, 10.dp.toPx(), frame.bottom, pad), 6.5f * stageScale, blink)
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
