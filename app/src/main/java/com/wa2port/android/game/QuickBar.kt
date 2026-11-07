package com.wa2port.android.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ui.Wa2Colors

/**
 * One quick-bar action. [active] highlights toggles (AUTO/SKIP). [shortLabel] replaces [label] in
 * the portrait [QuickBarFill] when its buttons are narrower than [QuickBarLayout.SHORT_LABEL_BELOW_DP].
 */
data class QuickAction(val label: String, val active: Boolean = false, val shortLabel: String? = null, val onClick: () -> Unit)

/** Pure sizing rules of the portrait [QuickBarFill] (dp). */
object QuickBarLayout {
    /** Horizontal padding of the bar on each side. */
    const val SIDE_PAD_DP = 8f

    /** Gap between buttons. */
    const val GAP_DP = 3f

    /** Below this button width the font drops from 12 to 11 dp. */
    const val SMALL_FONT_BELOW_DP = 52f

    /** Below this button width [QuickAction.shortLabel] is used. */
    const val SHORT_LABEL_BELOW_DP = 50f

    /** Width of each of [count] equal-weight buttons in a bar [barWidthDp] wide. */
    fun buttonWidth(barWidthDp: Float, count: Int): Float {
        if (count <= 0) return 0f
        return (barWidthDp - 2f * SIDE_PAD_DP - GAP_DP * (count - 1)) / count
    }

    /** Label font size (dp, never compounded with the system font scale). */
    fun fontDp(buttonWidthDp: Float): Float = if (buttonWidthDp < SMALL_FONT_BELOW_DP) 11f else 12f

    fun useShortLabel(buttonWidthDp: Float): Boolean = buttonWidthDp < SHORT_LABEL_BELOW_DP

    /**
     * The NOVEL-mode `🔊` key is a landscape-only extra (portrait NOVEL has the deck `🔊 重听` pill),
     * so the portrait bar always holds the same 7 keys.
     */
    fun showsVoiceKey(isNovelMode: Boolean, hasVoice: Boolean, portrait: Boolean): Boolean = isNovelMode && hasVoice && !portrait

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
 * Portrait HUD quick bar: every action as an equal-weight ≥ 48 dp button filling the width
 * (`spacedBy(3dp)`, 8 dp side padding, one line, 11/12 dp labels, [QuickAction.shortLabel] when narrow).
 */
@Composable
fun QuickBarFill(actions: List<QuickAction>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val buttonW = QuickBarLayout.buttonWidth(maxWidth.value, actions.size)
        val fontSize = with(LocalDensity.current) { QuickBarLayout.fontDp(buttonW).dp.toSp() }
        val short = QuickBarLayout.useShortLabel(buttonW)
        Row(
            Modifier.fillMaxSize().padding(horizontal = QuickBarLayout.SIDE_PAD_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(QuickBarLayout.GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { a ->
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(shape)
                        .background(if (a.active) Wa2Colors.Accent.copy(alpha = 0.85f) else Color(0x99101A30))
                        .border(1.dp, Wa2Colors.AccentDim, shape)
                        .clickable(onClick = a.onClick)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (short) a.shortLabel ?: a.label else a.label,
                        color = if (a.active) Wa2Colors.Night else Wa2Colors.Text,
                        fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false,
                    )
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
