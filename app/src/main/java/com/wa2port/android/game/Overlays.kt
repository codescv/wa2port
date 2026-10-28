package com.wa2port.android.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.bridge.TextPainter
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.Settings
import com.wa2port.android.ui.CloseButton
import com.wa2port.android.ui.MenuButton
import com.wa2port.android.ui.Segmented
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.android.ui.sceneLabel
import com.wa2port.core.engine.ChoiceOption
import com.wa2port.core.engine.DialogueLine
import com.wa2port.core.engine.LineRef
import com.wa2port.core.text.RichText
import com.wa2port.core.text.TextLang
import com.wa2port.core.text.TextScale

/** A language part of a bilingual caption. */
private data class Part(val rich: RichText, val lang: TextLang, val sizePx: Float, val color: Int)

private fun parts(jp: RichText, cn: RichText?, lang: DisplayLang, mainPx: Float, subPx: Float): List<Part> {
    val c = cn?.takeIf { !it.isEmpty }
    val main = Wa2Colors.Text.toArgb()
    val sub = Color(0xFFB8C6DD).toArgb()
    return when {
        lang == DisplayLang.JP || c == null -> listOf(Part(jp, TextLang.JP, mainPx, main))
        lang == DisplayLang.CN -> listOf(Part(c, TextLang.CN, mainPx, main))
        else -> listOf(Part(c, TextLang.CN, mainPx, main), Part(jp, TextLang.JP, subPx, sub))
    }
}

/** Canvas sized to the measured height of bilingual [parts]. */
@Composable
private fun BilingualText(painter: TextPainter, parts: List<Part>, widthPx: Float, gapPx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val h = remember(parts, widthPx) {
        parts.mapIndexed { i, p -> painter.measureHeight(p.rich, p.lang, widthPx, p.sizePx) + if (i > 0) gapPx else 0f }.sum()
    }
    Canvas(modifier.height(with(density) { h.toDp() }).fillMaxWidth()) {
        var y = 0f
        drawIntoCanvas { c ->
            parts.forEachIndexed { i, p ->
                if (i > 0) y += gapPx
                y += painter.draw(c.nativeCanvas, p.rich, p.lang, 0f, y, widthPx, p.sizePx, p.color, Int.MAX_VALUE)
            }
        }
    }
}

/** Full-width tappable choice cards (≥ 56 dp), both languages, centred on the stage. */
@Composable
fun ChoiceCards(painter: TextPainter, choices: List<ChoiceOption>, lang: DisplayLang, stageHeightPx: Float, textScale: Float, onChoose: (Int) -> Unit, modifier: Modifier) {
    val scale = stageHeightPx / 720f * textScale
    BoxWithConstraints(modifier.background(Color(0x55000000)), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val cardWidth = maxWidth * 0.78f
        val innerPx = with(density) { (cardWidth - 32.dp).toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            choices.forEachIndexed { i, ch ->
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier
                        .width(cardWidth)
                        .heightIn(min = 56.dp)
                        .clip(shape)
                        .background(Color(0xE0142240))
                        .border(1.5.dp, Wa2Colors.Accent.copy(alpha = 0.7f), shape)
                        .clickable { onChoose(i) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BilingualText(painter, parts(ch.jp, ch.cn, lang, 28f * scale, 21f * scale), innerPx, 4f * scale)
                }
            }
        }
    }
}

/**
 * Scrollable bilingual backlog with ▶ voice replay; opens scrolled to the newest line. Line and
 * speaker-name sizes follow the font size setting ([textScale], 1.0 = 100 %).
 */
@Composable
fun BacklogOverlay(
    painter: TextPainter,
    entries: List<LineRef>,
    resolve: (LineRef) -> DialogueLine,
    lang: DisplayLang,
    textScale: Float,
    onReplay: (LineRef) -> Unit,
    onClose: () -> Unit,
    onAi: ((LineRef) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (entries.size - 1).coerceAtLeast(0))
    Column(
        Modifier.fillMaxSize().background(Color(0xF0060B18))
            // Taps on empty margins must not fall through to the stage (no MOVE consumption, so the
            // list still scrolls).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("回想 Backlog  バックログ", color = Wa2Colors.Text, fontSize = 20.sp, modifier = Modifier.weight(1f))
            CloseButton(onClose)
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val textWidthPx = with(density) { (maxWidth - 72.dp).toPx() }
            val main = with(density) { 17.sp.toPx() } * textScale
            val sub = with(density) { 14.sp.toPx() } * textScale
            val nameSize = with(density) { 13.sp.toPx() } * textScale
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(entries) { _, ref ->
                    val line = remember(ref) { resolve(ref) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            val nameParts = parts(line.speakerJp ?: RichText.EMPTY, line.speakerCn, lang, nameSize, nameSize)
                                .filter { !it.rich.isEmpty }
                                .map { it.copy(color = Wa2Colors.Name.toArgb()) }
                            if (nameParts.isNotEmpty()) BilingualText(painter, nameParts.take(1), textWidthPx, 0f)
                            BilingualText(painter, parts(line.jp, line.cn, lang, main, sub), textWidthPx, 4f * textScale)
                        }
                        // ▶ voice replay and ✨ AI breakdown of this line.
                        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (ref.voice != null) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(Color(0x553060A0)).clickable { onReplay(ref) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("▶", color = Wa2Colors.Accent, fontSize = 18.sp) }
                            }
                            if (onAi != null && !line.jp.isEmpty) {
                                Box(
                                    Modifier.size(48.dp).clip(CircleShape).background(Color(0x55A06030)).clickable { onAi(ref) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("✨", fontSize = 16.sp) }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))
                }
            }
        }
    }
}

/**
 * In-game system menu (MENU button / Android Back).
 *
 * Hosts the secondary QuickBar controls (**快捷控制 Quick Controls**: 3-way language incl. single
 * 中文, ADV ⇄ NOVEL, font size presets, `✨ 点词` toggle, `👁 隐藏窗口`, and `Q.LOAD 快速读档`) at the top
 * of the card so the primary QuickBar stays at 7 high-frequency keys.
 */
@Composable
fun GameMenu(
    scene: String,
    inReplayMode: Boolean = false,
    settings: Settings = Settings(),
    onUpdateSettings: ((Settings) -> Settings) -> Unit = {},
    onQuickLoad: () -> Unit = {},
    onHideWindow: () -> Unit = {},
    onResume: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onLog: () -> Unit,
    onConfig: () -> Unit,
    onTitle: () -> Unit,
) {
    var confirmTitle by remember { mutableStateOf(false) }
    val exitLabel = if (inReplayMode) "结束回想 Exit Replay" else "返回标题 Title"
    Box(
        Modifier.fillMaxSize().background(Color(0xCC050A16)).clickable(onClick = onResume),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xE60D1628))
                .border(1.dp, Wa2Colors.AccentDim, RoundedCornerShape(14.dp))
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("快捷控制 · 系统菜单  Quick Controls & Menu", color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(sceneLabel(scene), color = Wa2Colors.TextDim, fontSize = 12.sp)
                }
                CloseButton(onResume)
            }

            // Secondary QuickBar controls: 3-way language (incl. single 中文), window mode, font size,
            // tap-to-analyze toggle, and hide window.
            Segmented(
                options = listOf(
                    DisplayLang.BOTH to "中/日 双",
                    DisplayLang.JP to "日本語",
                    DisplayLang.CN to "简体中文",
                ),
                selected = settings.lang,
                onSelect = { lang -> onUpdateSettings { it.copy(lang = lang) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickControlChip(
                    label = "窗口: ${settings.windowMode.label} ⇄",
                    active = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateSettings { it.copy(windowMode = it.windowMode.next()) } },
                )
                QuickControlChip(
                    label = "字号: ${TextScale.clamp(settings.textScale)}%",
                    active = settings.textScale != TextScale.DEFAULT,
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateSettings { it.withTextScale(it.nextTextScale()) } },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickControlChip(
                    label = if (settings.aiTapToAnalyze) "✨ 点词解析: 开 ON" else "✨ 点词解析: 关 OFF",
                    active = settings.aiTapToAnalyze,
                    modifier = Modifier.weight(1f),
                    onClick = { onUpdateSettings { it.copy(aiTapToAnalyze = !it.aiTapToAnalyze) } },
                )
                QuickControlChip(
                    label = "👁 隐藏窗口 HIDE",
                    active = false,
                    modifier = Modifier.weight(1f),
                    onClick = onHideWindow,
                )
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x22FFFFFF)))

            if (!inReplayMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuButton("保存 Save", modifier = Modifier.weight(1f), onClick = onSave)
                    MenuButton("读取 Load", modifier = Modifier.weight(1f), onClick = onLoad)
                    MenuButton("快读 Q.LOAD", modifier = Modifier.weight(1f), onClick = onQuickLoad)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuButton("回想 Log", modifier = Modifier.weight(1f), onClick = onLog)
                MenuButton("设置 Config", modifier = Modifier.weight(1f), onClick = onConfig)
                MenuButton(exitLabel, modifier = Modifier.weight(1f)) {
                    if (inReplayMode) onTitle() else confirmTitle = true
                }
            }
            MenuButton("返回游戏 Resume", modifier = Modifier.fillMaxWidth(), onClick = onResume)
        }
    }
    if (confirmTitle) {
        AlertDialog(
            onDismissRequest = { confirmTitle = false },
            confirmButton = { TextButton(onClick = { confirmTitle = false; onTitle() }) { Text(exitLabel) } },
            dismissButton = { TextButton(onClick = { confirmTitle = false }) { Text("取消 Cancel") } },
            text = { Text("返回标题画面？（当前进度将自动保存至「继续」）\nタイトルに戻りますか？") },
        )
    }
}

@Composable
private fun QuickControlChip(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (active) Wa2Colors.Accent.copy(alpha = 0.22f) else Color(0x66203050))
            .border(1.dp, if (active) Wa2Colors.Accent else Wa2Colors.AccentDim, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Wa2Colors.Accent else Wa2Colors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
