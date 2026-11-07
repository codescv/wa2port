package com.wa2port.android.game

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ui.Wa2Colors
import kotlin.math.roundToInt

/**
 * One quick-bar action. [active] highlights toggles (AUTO/SKIP). [shortLabel] replaces [label] in
 * the portrait [QuickBarFill] only when [label] does not fit its button ([QuickBarLayout.displayLabel]).
 */
data class QuickAction(val label: String, val active: Boolean = false, val shortLabel: String? = null, val onClick: () -> Unit)

/** Pure sizing rules of the portrait [QuickBarFill] (dp). */
object QuickBarLayout {
    /** Horizontal padding of the bar on each side (to the outer edge of the first / last button). */
    const val SIDE_PAD_DP = 8f

    /** Visual gap between buttons (part of both neighbours' touch cells, see [touchWidth]). */
    const val GAP_DP = 3f

    /** Horizontal padding between a button's edge and its label. */
    const val INNER_PAD_DP = 4f

    /** Below this button width the font drops from 12 to 11 dp. */
    const val SMALL_FONT_BELOW_DP = 52f

    /** Label letter spacing (dp per glyph, the Material `bodyLarge` 0.5 sp pinned to dp). */
    const val LETTER_SPACING_DP = 0.5f

    /** Label line height (dp; one line, centred in the 48 dp key). */
    const val LINE_HEIGHT_DP = 16f

    /** Width of each of [count] equal-weight buttons in a bar [barWidthDp] wide. */
    fun buttonWidth(barWidthDp: Float, count: Int): Float {
        if (count <= 0) return 0f
        return (barWidthDp - 2f * SIDE_PAD_DP - GAP_DP * (count - 1)) / count
    }

    /**
     * Touch width of each button: the cell absorbs half a [GAP_DP] on either side (the bar's inner
     * row is inset `SIDE_PAD_DP − GAP_DP / 2`), so neighbouring targets share an edge with no dead
     * gap. 7 keys on a 360 dp phone: 46.57 dp visual, 49.57 dp touch (≥ 48 dp).
     */
    fun touchWidth(barWidthDp: Float, count: Int): Float = if (count <= 0) 0f else buttonWidth(barWidthDp, count) + GAP_DP

    /** Label font size (dp, never compounded with the system font scale). */
    fun fontDp(buttonWidthDp: Float): Float = if (buttonWidthDp < SMALL_FONT_BELOW_DP) 11f else 12f

    /** `Dp.roundToPx()`: Compose rounds every padding side to whole px (half up). */
    private fun px(dp: Float, density: Float): Int = (dp * density).roundToInt()

    /**
     * Narrowest touch cell (px) of [count] `weight(1f)` cells in a bar [barWidthPx] wide at [density]:
     * the row is inset `round(SIDE_PAD_DP − GAP_DP / 2)` px per side and Compose hands every weighted
     * child `floor` or `ceil` of the remaining px / [count].
     */
    fun touchCellMinPx(barWidthPx: Int, count: Int, density: Float): Int {
        if (count <= 0) return 0
        val inner = barWidthPx - 2 * px(SIDE_PAD_DP - GAP_DP / 2f, density)
        return Math.floorDiv(inner.coerceAtLeast(0), count)
    }

    /**
     * Worst-case label constraint (px) exactly as Compose lays the key out: [touchCellMinPx] minus the
     * per-side rounded half gap and [INNER_PAD_DP]. A label whose measured width is ≤ this never clips.
     */
    fun labelRoomPx(barWidthPx: Int, count: Int, density: Float): Int {
        if (count <= 0) return 0
        return touchCellMinPx(barWidthPx, count, density) - 2 * px(GAP_DP / 2f, density) - 2 * px(INNER_PAD_DP, density)
    }

    /**
     * The label drawn on [a]'s button: [QuickAction.label] when its measured width ([textWidthPx], px
     * at [fontDp]) fits [roomPx] ([labelRoomPx]), otherwise [QuickAction.shortLabel] (if any). Decided
     * per key, so e.g. PKX110 (412 dp @ 3.5×, 7 keys) keeps `Q.SAVE` while a 360 dp phone shows `快存`.
     */
    fun displayLabel(a: QuickAction, roomPx: Int, textWidthPx: (String) -> Int): String {
        val short = a.shortLabel ?: return a.label
        return if (textWidthPx(a.label) <= roomPx) a.label else short
    }

    /**
     * Last-resort glyph scale for a shown label [widthPx] wide in [roomPx] (e.g. an OEM font much wider
     * than Roboto): 1 when it fits, else shrinks it to `roomPx − 1` px (1 px for `ceil` rounding).
     */
    fun labelScale(widthPx: Int, roomPx: Int): Float =
        if (widthPx <= roomPx || widthPx <= 0) 1f else (roomPx - 1).coerceAtLeast(0).toFloat() / widthPx

    /**
     * The NOVEL-mode `🔊` key is a landscape-only extra: the portrait deck (ADV + NOVEL) carries the
     * `🔊 重听` rim pill, so the portrait bar never adds it.
     */
    fun showsVoiceKey(isNovelMode: Boolean, hasVoice: Boolean, portrait: Boolean): Boolean = isNovelMode && hasVoice && !portrait

    /**
     * The `✨ AI` key: always in the portrait bottom thumb bar (ADV + NOVEL; the portrait deck rim
     * keeps only `🔊 重听`, see [AdvPills.showsRimAi]) and in landscape NOVEL (no rim pills). Landscape
     * ADV windows carry the `[✨ AI解读]` rim pill instead.
     */
    fun showsAiKey(isNovelMode: Boolean, portrait: Boolean): Boolean = isNovelMode || portrait

    /**
     * Portrait bottom-bar order: [log] moves to the left corner (`LOG · AUTO · SKIP · ✨ AI · 日⇄双 ·
     * Q.SAVE · MENU`) so both corners hold harmless overlay openers, SKIP / Q.SAVE sit ≥ 1 key away
     * from either corner and `✨ AI` (first of [right]) lands in the centre slot within either thumb's
     * reach. A [log] not present in [left] is ignored (identity match only).
     */
    fun <T : Any> portraitOrder(left: List<T>, right: List<T>, log: T): List<T> {
        if (left.none { it === log }) return left + right
        return listOf(log) + left.filterNot { it === log } + right
    }

    /**
     * Order of a horizontal quick row built from the side-column halves [left] + [right]: when the
     * [voice] key (`🔊`, end of [left]) and the [ai] key (`✨ AI`, in [right]) are both present, `✨ AI`
     * moves immediately to the left of `🔊` (`… LOG, ✨ AI, 🔊, 日⇄双 …`), like the message-window
     * `[✨ AI解读] [🔊 重听]` pills. Otherwise `left + right` unchanged. Keys are matched by identity.
     */
    fun <T : Any> rowOrder(left: List<T>, right: List<T>, voice: T?, ai: T?): List<T> {
        if (voice == null || ai == null || left.none { it === voice } || right.none { it === ai }) return left + right
        return left.filterNot { it === voice } + ai + voice + right.filterNot { it === ai }
    }
}

@Composable
private fun QuickButton(a: QuickAction, width: Dp) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .sizeIn(minWidth = width, minHeight = 48.dp)
            .clip(shape)
            .background(if (a.active) Wa2Colors.Accent.copy(alpha = 0.85f) else Color(0x99101A30))
            .border(1.dp, Wa2Colors.AccentDim, shape)
            .clickable(onClick = a.onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            a.label,
            color = if (a.active) Wa2Colors.Night else Wa2Colors.Text,
            fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

/** Vertical column of quick buttons for a side letterbox. */
@Composable
fun QuickColumn(actions: List<QuickAction>, width: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        actions.forEach { QuickButton(it, width) }
    }
}

/**
 * Portrait bottom thumb bar: every action as an equal-weight ≥ 48 dp button filling the width
 * (3 dp visual gaps, 8 dp side padding, one line, 11/12 dp labels). Each touch cell absorbs half a gap
 * on either side ([QuickBarLayout.touchWidth] ≥ 48 dp on a 360 dp phone with 7 keys). Labels are
 * measured with the real font against the pixel-exact worst-case room ([QuickBarLayout.labelRoomPx]):
 * a key whose label does not fit shows its [QuickAction.shortLabel], and a label that still does not
 * fit (unusually wide OEM font) is shrunk ([QuickBarLayout.labelScale]) rather than clipped.
 */
@Composable
fun QuickBarFill(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val buttonW = QuickBarLayout.buttonWidth(maxWidth.value, actions.size)
        // Every text metric in dp → sp, so the label never grows with the system font scale.
        val style = with(density) {
            LocalTextStyle.current.merge(
                TextStyle(
                    fontSize = QuickBarLayout.fontDp(buttonW).dp.toSp(),
                    letterSpacing = QuickBarLayout.LETTER_SPACING_DP.dp.toSp(),
                    lineHeight = QuickBarLayout.LINE_HEIGHT_DP.dp.toSp(),
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        val measurer = rememberTextMeasurer(cacheSize = 16)
        val roomPx = QuickBarLayout.labelRoomPx(constraints.maxWidth, actions.size, density.density)
        val textWidthPx: (String) -> Int = { s -> measurer.measure(s, style, softWrap = false, maxLines = 1).size.width }
        val halfGap = QuickBarLayout.GAP_DP / 2f
        Row(
            Modifier.fillMaxSize().padding(horizontal = (QuickBarLayout.SIDE_PAD_DP - halfGap).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { a ->
                val shape = RoundedCornerShape(8.dp)
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = a.onClick)
                        .padding(horizontal = halfGap.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(shape)
                            .background(if (a.active) Wa2Colors.Accent.copy(alpha = 0.85f) else Color(0x99101A30))
                            .border(1.dp, Wa2Colors.AccentDim, shape)
                            .indication(interaction, LocalIndication.current)
                            .padding(horizontal = QuickBarLayout.INNER_PAD_DP.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val label = QuickBarLayout.displayLabel(a, roomPx, textWidthPx)
                        val k = QuickBarLayout.labelScale(textWidthPx(label), roomPx)
                        Text(
                            label,
                            color = if (a.active) Wa2Colors.Night else Wa2Colors.Text,
                            style = if (k < 1f) style.copy(fontSize = style.fontSize * k, letterSpacing = style.letterSpacing * k) else style,
                            textAlign = TextAlign.Center,
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** Horizontal row of quick buttons (top letterbox or overlay on the stage); scrolls when it overflows. */
@Composable
fun QuickRow(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { QuickButton(it, 52.dp) }
    }
}
