package com.wa2port.android.game

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ai.AiBreakdownState
import com.wa2port.android.ai.AiLearningController
import com.wa2port.android.ai.AiStatus
import com.wa2port.android.ai.NotebookOverlay
import com.wa2port.android.ai.StarButton
import com.wa2port.android.ui.CloseButton
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.core.ai.AiErrorCode
import com.wa2port.core.ai.AiPersonaSettings
import com.wa2port.core.ai.FollowUpMessage
import com.wa2port.core.ai.GrammarPoint
import com.wa2port.core.ai.OfflineExplainer
import com.wa2port.core.ai.RubyBuilder
import com.wa2port.core.ai.RubyToken
import com.wa2port.core.ai.SemanticChunk
import com.wa2port.core.ai.SentenceExplanation
import com.wa2port.core.ai.SpanAlign
import com.wa2port.core.ai.SpanAlignment
import com.wa2port.core.ai.VocabEntry
import com.wa2port.core.ai.partOfSpeechLabel
import kotlinx.coroutines.launch

private val SCRIM = Color(0xFF060B18) // opaque: bright text underneath must not bleed through
private val CARD = Color(0xFF111A2E)
private val CARD_ACTIVE = Color(0xFF17264A)
private val TEXTBOOK = Color(0xFF8FD3A8)
private val EVOLVED = Color(0xFFFFA36B)
private val ERROR = Color(0xFFFF8A80)
private val OK = Color(0xFF8FD3A8)

private val TIGHT_TEXT_STYLE = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/** Characters that must not begin a line (kinsoku shori). */
private const val NO_LINE_START = "、。，．,.!！?？…‥」』）)】〕〉》ー～ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ々"

private val QUICK_QUESTIONS = listOf("这句为什么用这个说法？", "换成敬体怎么说？", "再举一个例句")

/**
 * Kotori-style AI breakdown sheet (✨ AI 解说), drawn above the game and any overlay.
 *
 * Layout: header (persona chips 冬马和纱 / 小木曾雪菜 / 自定义, ↻ regenerate, ✕); then, on wide screens,
 * a left pane (interactive furigana sentence, ▶ 原声, badges, AI / literal / CK-GAL translations,
 * chunk strip) and a right pane (📘 课本标准 vs 🔥 口语演变 chunk cards with formality bar, vocab &
 * grammar, follow-up Q&A). Narrow screens stack everything with the sentence pinned on top.
 * Character ↔ chunk selection is local state (0 ms, no network). The root is an indication-less
 * click target so taps never leak to the stage / backlog underneath, while drags still reach the
 * scrollable panes.
 */
@Composable
fun AiBreakdownOverlay(
    state: AiBreakdownState,
    ai: AiLearningController,
    persona: AiPersonaSettings,
    onPersona: (String) -> Unit,
    onReplayVoice: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SCRIM)
            // Block taps from reaching the stage / backlog underneath (B2) without consuming MOVE
            // events: a root that consumed every change on the Main pass made the children's
            // scrollables abort during touch-slop detection, so scrolling only worked on fast flicks.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 1100.dp).fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            HeaderRow(state, ai, persona, onPersona, onClose)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 640.dp) {
                    val leftListState = rememberLazyListState()
                    LaunchedEffect(state) { leftListState.scrollToItem(0) }
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(
                            Modifier.weight(0.42f).fillMaxHeight(),
                            state = leftListState,
                            contentPadding = PaddingValues(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "sentence") { SentenceHeader(state, ai, onReplayVoice) }
                            item(key = "summary") { SummaryBlock(state, ai, onOpenSettings) }
                        }
                        DetailPane(state, ai, persona, onOpenSettings, Modifier.weight(0.58f).fillMaxHeight(), header = null)
                    }
                } else {
                    DetailPane(state, ai, persona, onOpenSettings, Modifier.fillMaxSize()) {
                        SentenceHeader(state, ai, onReplayVoice)
                        SummaryBlock(state, ai, onOpenSettings)
                    }
                }
            }
        }
        if (ai.notebookOpen) {
            NotebookOverlay(
                ai = ai,
                onReplayVoice = { v ->
                    if (!ai.replayVoice(v) && state.voice == v) onReplayVoice?.invoke()
                },
                onClose = { ai.closeNotebook() },
            )
        }
    }
}

// ------------------------------------------------------------------------------------- header

@Composable
private fun HeaderRow(state: AiBreakdownState, ai: AiLearningController, persona: AiPersonaSettings, onPersona: (String) -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✨ AI 解说 · ${persona.shortName}", color = Wa2Colors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.isSample) {
                Chip("◀ 上一句", enabled = ai.canPrev()) { ai.openPrev() }
                Chip("下一句 ▶", enabled = ai.canNext()) { ai.openNext() }
                Spacer(Modifier.width(4.dp))
            }
            ai.personas.forEach { p ->
                val label = if (p.id == AiPersonaSettings.ID_CUSTOM) "自定义 (${p.shortName})" else p.shortName
                Chip(label, selected = p.id == persona.id) { onPersona(p.id) }
            }
            Spacer(Modifier.width(6.dp))
            val loading = state.status is AiStatus.Loading
            Chip(if (state.isSample) "↻ 示例" else "↻ 重新生成", enabled = !loading) { ai.regenerate() }
            Chip("📒 笔记本") { ai.openNotebook() }
        }
        CloseButton(onClose)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, enabled: Boolean = true, color: Color = Wa2Colors.Accent, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .background(if (selected) color else Color(0x33203050))
            .border(1.dp, if (selected) color else Wa2Colors.AccentDim, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Wa2Colors.Night else if (enabled) Wa2Colors.Text else Wa2Colors.TextDim, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Tag(label: String, color: Color = Wa2Colors.AccentDim, textColor: Color = Wa2Colors.Text) {
    Text(
        label, color = textColor, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ------------------------------------------------------------------------------------- sentence (RubyText)

/** Speaker + interactive furigana sentence (tap a character → its chunk, 0 ms) + ▶ 原声. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceHeader(state: AiBreakdownState, ai: AiLearningController, onReplayVoice: (() -> Unit)?) {
    val expl = state.explanation
    val text = state.sentence.text
    // Before the model answers, show the script's own furigana over plain runs.
    val tokens: List<RubyToken> = expl?.ruby ?: remember(state) {
        RubyBuilder.overlayScriptRuby(text, RubyBuilder.plainRuns(text, 0), state.sentence.scriptRuby)
    }
    val active = state.activeChunk
    val focus = state.focusCharIndex
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val speaker = listOfNotNull(state.speakerJp, state.speakerCn?.takeIf { it != state.speakerJp }).joinToString(" · ")
            Text(speaker.ifEmpty { "（旁白 / 内心独白）" }, color = if (speaker.isEmpty()) Wa2Colors.TextDim else Wa2Colors.Name, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (state.voice != null) {
                Chip("▶ 原声") {
                    if (onReplayVoice != null) onReplayVoice() else ai.replayVoice(state.voice)
                }
            }
            if (expl != null && expl.model != OfflineExplainer.MODEL) {
                StarButton(
                    starred = ai.isSentenceStarred(expl.id),
                    onClick = { ai.toggleSentenceStar(expl.id) },
                )
            }
        }
        // Ruby-bearing tokens stay grouped (reading centred over the base); every other character is
        // its own flow item so long kana runs wrap inside narrow panes.
        val groups: List<Pair<String?, List<Int>>> = remember(tokens, text) {
            tokens.flatMap { t ->
                val starts = ArrayList<Int>()
                var i = t.span.start
                while (i < t.span.end && i < text.length) {
                    starts += i
                    i += Character.charCount(text.codePointAt(i))
                }
                if (t.ruby != null) listOf(t.ruby to starts) else starts.map { null to listOf(it) }
            }.fold(ArrayList<Pair<String?, List<Int>>>()) { acc, g ->
                // Kinsoku: a closing punctuation mark never starts a line — glue it to a preceding
                // ruby-less group.
                val prev = acc.lastOrNull()
                val single = g.first == null && g.second.size == 1 && text[g.second[0]] in NO_LINE_START
                if (single && prev != null && prev.first == null) acc[acc.size - 1] = null to (prev.second + g.second) else acc += g
                acc
            }
        }
        val hasAnyRuby = remember(tokens) { tokens.any { !it.ruby.isNullOrBlank() } }
        val density = LocalDensity.current
        val rubyBoxH = with(density) { 13.sp.toDp() }
        val baseBoxH = with(density) { 30.sp.toDp() }
        FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            groups.forEach { (ruby, starts) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (hasAnyRuby) {
                        Box(Modifier.height(rubyBoxH), contentAlignment = Alignment.BottomCenter) {
                            if (!ruby.isNullOrBlank()) {
                                Text(
                                    ruby,
                                    color = Wa2Colors.TextDim,
                                    fontSize = 9.sp,
                                    lineHeight = 10.sp,
                                    maxLines = 1,
                                    style = TIGHT_TEXT_STYLE,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        starts.forEach { idx ->
                            val ch = String(Character.toChars(text.codePointAt(idx)))
                            val inChunk = active != null && !active.span.isEmpty && idx in active.span
                            val isFocus = idx == focus
                            Box(
                                modifier = Modifier
                                    .height(baseBoxH)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (inChunk) Wa2Colors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                                    .drawBehind {
                                        if (isFocus) {
                                            drawLine(Wa2Colors.Accent, Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), strokeWidth = 4f)
                                        }
                                    }
                                    .clickable { ai.selectChar(idx) }
                                    .padding(horizontal = 1.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    ch,
                                    color = Wa2Colors.Text,
                                    fontSize = 24.sp,
                                    lineHeight = 26.sp,
                                    style = TIGHT_TEXT_STYLE,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (state.status is AiStatus.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Wa2Colors.Accent, trackColor = Wa2Colors.AccentDim.copy(alpha = 0.3f))
            Text("${ai.persona.shortName}正在分析… Analyzing…", color = Wa2Colors.TextDim, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------------------------- summary (badges, translations, states)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryBlock(state: AiBreakdownState, ai: AiLearningController, onOpenSettings: (() -> Unit)?) {
    val expl = state.explanation
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expl != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                expl.register?.let { Tag("语体 ${it.labelCn}", Wa2Colors.Accent) }
                expl.tone?.let { Tag("语气 $it", EVOLVED) }
                if (state.isSample) Tag("示例 Sample", TEXTBOOK)
                if (expl.model == OfflineExplainer.MODEL) Tag("离线 Offline", TEXTBOOK)
                if (expl.fromCache) Tag("已缓存 Cached")
                if (!state.isSample && expl.model != OfflineExplainer.MODEL && expl.model.isNotBlank()) {
                    Tag(expl.model, Color(0x33FFFFFF), Wa2Colors.TextDim)
                }
            }
        }
        // While loading, the detail pane's skeleton card carries the translation (shown once, not twice).
        if (!state.isSkeleton) TranslationCard(state, expl)
        if (expl != null && expl.chunks.isNotEmpty()) {
            Text("意群 Chunks", color = Wa2Colors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val active = state.activeChunk
                expl.chunks.forEach { c ->
                    // From the chunk strip: also scroll the matching card into view in the detail pane.
                    Chip(c.surface, selected = active?.id == c.id, color = if (c.isEvolved) EVOLVED else Wa2Colors.Accent) { ai.selectChunk(c, scrollToCard = true) }
                }
            }
        }
    }
}

/**
 * Translations. With [skeleton] (loading, see [DetailPane]) it also carries the
 * speaker, the script-furigana sentence ([showSentence]: only when the sentence header is not
 * already right above) and the preceding context lines, so the detail pane is useful at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationCard(state: AiBreakdownState, expl: SentenceExplanation?, skeleton: Boolean = false, showSentence: Boolean = false) {
    val official = expl?.officialCnTranslation ?: state.officialCn
    if (!skeleton && expl == null && official == null) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (skeleton) {
            val speaker = listOfNotNull(state.speakerJp, state.speakerCn?.takeIf { it != state.speakerJp }).joinToString(" · ")
            Text(speaker.ifEmpty { "（旁白 / 内心独白）" }, color = if (speaker.isEmpty()) Wa2Colors.TextDim else Wa2Colors.Name, fontSize = 13.sp)
            if (showSentence && expl != null) {
                val hasAnyRuby = expl.ruby.any { !it.ruby.isNullOrBlank() }
                FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    expl.ruby.forEach { t ->
                        if (t.ruby != null) {
                            RubyCell(t.ruby, t.base, showRuby = hasAnyRuby)
                        } else {
                            // Ruby-less runs break per character so long kana runs wrap.
                            var i = 0
                            while (i < t.base.length) {
                                val n = Character.charCount(t.base.codePointAt(i))
                                RubyCell(null, t.base.substring(i, i + n), showRuby = hasAnyRuby)
                                i += n
                            }
                        }
                    }
                }
            }
        }
        if (expl != null && expl.model != OfflineExplainer.MODEL) {
            Label("AI 译")
            Text(expl.translation, color = Wa2Colors.Text, fontSize = 16.sp)
            expl.literalTranslation?.let {
                Label("直译 Literal")
                Text(it, color = Wa2Colors.TextDim, fontSize = 14.sp)
            }
        }
        if (official != null) {
            Text("官方汉化 CK-GAL", color = Wa2Colors.Name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(official, color = Wa2Colors.Text, fontSize = 14.sp)
        } else if (skeleton) {
            Text("（本行没有官方汉化 No CK-GAL line）", color = Wa2Colors.TextDim, fontSize = 12.sp)
        }
        expl?.officialTranslationNote?.let {
            Label("对照说明 💬")
            Text(it, color = Wa2Colors.Text, fontSize = 13.sp)
        }
        if (skeleton && state.contextBefore.isNotEmpty()) {
            Label("前文 Context", Wa2Colors.TextDim)
            state.contextBefore.forEach { Text(it, color = Wa2Colors.TextDim, fontSize = 12.sp) }
        }
    }
}

/** One furigana cell (reading over base) of the skeleton card's sentence. */
@Composable
private fun RubyCell(ruby: String?, base: String, showRuby: Boolean = true) {
    val density = LocalDensity.current
    val rubyBoxH = with(density) { 13.sp.toDp() }
    val baseBoxH = with(density) { 22.sp.toDp() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (showRuby) {
            Box(Modifier.height(rubyBoxH), contentAlignment = Alignment.BottomCenter) {
                if (!ruby.isNullOrBlank()) {
                    Text(
                        ruby,
                        color = Wa2Colors.TextDim,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        style = TIGHT_TEXT_STYLE,
                    )
                }
            }
        }
        Box(Modifier.height(baseBoxH), contentAlignment = Alignment.Center) {
            Text(
                base,
                color = Wa2Colors.Text,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                style = TIGHT_TEXT_STYLE,
            )
        }
    }
}

@Composable
private fun Label(text: String, color: Color = Wa2Colors.Accent) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorCard(state: AiBreakdownState, err: AiStatus.Error, ai: AiLearningController, onOpenSettings: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CARD).border(1.dp, ERROR.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(AiLearningController.errorText(err.code), color = ERROR, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (err.code == AiErrorCode.NOT_CONFIGURED) {
            Text("在 设置 → AI 学习 中填写 Gemini API Key 或 Vertex AI（可设置自定义服务器地址 / 代理）。", color = Wa2Colors.TextDim, fontSize = 12.sp)
        }
        if (err.message.isNotBlank() && err.code != AiErrorCode.NOT_CONFIGURED) {
            Text(err.message, color = Wa2Colors.TextDim, fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (err.code == AiErrorCode.NOT_CONFIGURED) {
                if (onOpenSettings != null) Chip("前往设置 Settings", selected = true, onClick = onOpenSettings)
            } else {
                Chip("重试 Retry", selected = true) { ai.explain() }
            }
            Chip("示例预览 Preview") { ai.openSample(null) }
            if (!state.isSample) Chip("离线分段 Offline") { ai.showOffline() }
        }
    }
}

// ------------------------------------------------------------------------------------- detail pane

/**
 * Chunk cards + vocab + grammar + follow-up Q&A, ending with the follow-up input. Everything lives
 * in one full-height [LazyColumn] so the whole pane scrolls (a pinned input footer used to eat about
 * a third of a landscape phone's pane height, where swipes did nothing).
 *
 * Never blank while loading: [AiBreakdownState.isSkeleton] shows the translation card (CK-GAL line,
 * speaker, context, script furigana) over shimmer placeholders, and a 300 ms [Crossfade] swaps in the
 * real breakdown. The follow-up input is only offered for a real (non-offline) breakdown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailPane(
    state: AiBreakdownState,
    ai: AiLearningController,
    persona: AiPersonaSettings,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier,
    header: (@Composable () -> Unit)?,
) {
    val listState = rememberLazyListState()
    // A lazy list state can serve one list at a time; the skeleton list (alive during the crossfade) has its own.
    val skeletonListState = rememberLazyListState()
    val headerCount = if (header != null) 1 else 0
    // Hoisted out of the lazy item so a draft survives the input scrolling out of composition.
    var input by remember(state) { mutableStateOf("") }
    // Scroll the active card into view when the focus changes from the sentence header / chunk strip.
    LaunchedEffect(state.focusFromSentence) {
        if (state.focusFromSentence == 0) return@LaunchedEffect
        val e = state.explanation ?: return@LaunchedEffect
        val active = state.activeChunk ?: return@LaunchedEffect
        val idx = e.chunks.indexOfFirst { it.id == active.id }
        // Items before the cards: [header], [error], "chunks-title".
        val errorCount = if (state.status is AiStatus.Error) 1 else 0
        if (idx >= 0 && header == null) listState.animateScrollToItem(headerCount + errorCount + 1 + idx)
    }
    Crossfade(targetState = state.isSkeleton, modifier = modifier, animationSpec = tween(durationMillis = 300), label = "ai-skeleton") { skeleton ->
        if (skeleton) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = skeletonListState,
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (header != null) item(key = "header") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { header() } }
                item(key = "skeleton-translation") { TranslationCard(state, state.shownExplanation, skeleton = true, showSentence = header == null) }
                item(key = "skeleton-title") { SkeletonTitle() }
                items(SHIMMER_CARDS, key = { "shimmer-$it" }) { ShimmerCard(it) }
                item(key = "followup-locked") { FollowUpLockedBar("AI 解析完成后即可向助教追问") }
            }
        } else {
            FullDetailList(state, ai, persona, onOpenSettings, header, listState, input, onInput = { input = it })
        }
    }
}

/** The real breakdown (or the error card / offline split): everything below the optional [header]. */
@Composable
private fun FullDetailList(
    state: AiBreakdownState,
    ai: AiLearningController,
    persona: AiPersonaSettings,
    onOpenSettings: (() -> Unit)?,
    header: (@Composable () -> Unit)?,
    listState: LazyListState,
    input: String,
    onInput: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val expl = state.explanation
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(key = "header") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { header() } }
        (state.status as? AiStatus.Error)?.let { err -> item(key = "error") { ErrorCard(state, err, ai, onOpenSettings) } }
        if (expl != null) {
            item(key = "chunks-title") { SectionHeader("逐块解析 · 📘 课本标准 vs 🔥 口语演变") }
            val active = state.activeChunk
            expl.chunks.forEachIndexed { i, c ->
                item(key = "chunk-${c.id}") { ChunkCard(i, c, expl, active?.id == c.id, ai) { ai.selectChunk(c) } }
            }
            if (expl.vocab.isNotEmpty()) {
                item(key = "vocab-title") { SectionHeader("词汇 Vocab (${expl.vocab.size})") }
                expl.vocab.forEach { v ->
                    item(key = "vocab-${v.id}") {
                        VocabCard(
                            v = v,
                            starred = ai.isVocabStarred(v),
                            onToggleStar = { ai.toggleVocabStar(v, expl) },
                        ) {
                            val target = SpanAlign.findChunkAtChar(expl.chunks, v.span.start)
                                ?: expl.chunks.firstOrNull { v.id in it.vocabIds }
                            target?.let { ai.selectChunk(it) }
                        }
                    }
                }
            }
            if (expl.grammar.isNotEmpty()) {
                item(key = "grammar-title") { SectionHeader("语法 Grammar (${expl.grammar.size})") }
                expl.grammar.forEach { g ->
                    item(key = "grammar-${g.id}") {
                        GrammarCard(
                            g = g,
                            expl = expl,
                            starred = ai.isGrammarStarred(g),
                            onToggleStar = { ai.toggleGrammarStar(g, expl) },
                        )
                    }
                }
            }
            followUpItems(state, persona)
            val canAsk = state.status !is AiStatus.Loading && state.shownExplanation?.model != OfflineExplainer.MODEL
            if (canAsk) {
                item(key = "followup-input") {
                    FollowUpInput(state, ai, input, onInput) {
                        // Scrolling to the last item clamps at the end of the list: newest Q&A + input.
                        scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                    }
                }
            } else {
                item(key = "followup-locked") {
                    FollowUpLockedBar(if (state.status is AiStatus.Loading) "AI 解析完成后即可向助教追问" else "当前为离线分段模式 · 配置 AI 后可追问")
                }
            }
        }
    }
}

/** Shimmer placeholder cards shown under the skeleton translation card. */
private val SHIMMER_CARDS = listOf(0, 1, 2)

@Composable
private fun SkeletonTitle() {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), color = Wa2Colors.Accent, strokeWidth = 2.dp)
        Text("正在通过 AI 拆解意群、口语演变与核心词汇语法…", color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Animated shimmer placeholder in the shape of a chunk card (badge + title bar + 2 text bars). */
@Composable
private fun ShimmerCard(index: Int) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    val base = Color(0x22FFFFFF)
    val shine = Color(0x44FFFFFF)
    val brush = Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = Offset(x * 600f - 300f, 0f),
        end = Offset(x * 600f, 0f),
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(CARD).border(1.dp, Wa2Colors.AccentDim.copy(alpha = 0.25f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(brush))
            Box(Modifier.fillMaxWidth(0.35f + 0.1f * (index % 3)).height(16.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        }
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
        Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
    }
}

/** Muted bar standing in for the follow-up input while it is unavailable (loading / offline). */
@Composable
private fun FollowUpLockedBar(text: String) {
    Text(
        "💬 $text", color = Wa2Colors.TextDim, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0x1AFFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

/** One semantic chunk: 📘 textbook form, 🔥 spoken evolution + formality bar, meaning, nuance; vocab & grammar when active. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChunkCard(index: Int, c: SemanticChunk, expl: SentenceExplanation, active: Boolean, ai: AiLearningController, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(if (active) CARD_ACTIVE else CARD)
            .border(if (active) 2.dp else 1.dp, if (active) Wa2Colors.Accent else Wa2Colors.AccentDim.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (c.isEvolved) EVOLVED else Wa2Colors.Accent), contentAlignment = Alignment.Center) {
                Text("${index + 1}", color = Wa2Colors.Night, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            RubyLine(if (c.alignment == SpanAlignment.UNALIGNED) emptyList() else RubyBuilder.sliceRuby(expl.ruby, c.span), c.surface, 18.sp)
            if (RubyBuilder.cleanReading(c.reading, c.surface) != null) Text(c.reading, color = Wa2Colors.TextDim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (c.role.isNotBlank()) Tag(c.role)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📘 课本标准  ", color = TEXTBOOK, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(c.textbookForm, color = Wa2Colors.Text, fontSize = 15.sp)
        }
        c.spokenEvolution?.let { evo ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(EVOLVED.copy(alpha = 0.10f)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("🔥 口语演变 · ${evo.kind}", color = EVOLVED, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(evo.derivation, color = Wa2Colors.Text, fontSize = 14.sp)
                FormalityBar(evo.formalityLevel)
            }
        }
        if (c.meaning.isNotBlank()) Text(c.meaning, color = Wa2Colors.Text, fontSize = 14.sp)
        c.nuance?.let { Text("💡 $it", color = Wa2Colors.TextDim, fontSize = 13.sp) }
        if (c.alignment == SpanAlignment.UNALIGNED) Text("⚠ 未能定位到原句", color = ERROR, fontSize = 12.sp)
        if (active) {
            val vocab = SpanAlign.findVocabForChunk(c, expl.vocab)
            if (vocab.isNotEmpty()) {
                Label("词汇")
                vocab.forEach { v ->
                    VocabRow(
                        v = v,
                        starred = ai.isVocabStarred(v),
                        onToggleStar = { ai.toggleVocabStar(v, expl) },
                    )
                }
            }
            val grammar = SpanAlign.grammarForChunk(c, expl.grammar)
            if (grammar.isNotEmpty()) {
                Label("语法")
                grammar.forEach { g ->
                    GrammarRow(
                        g = g,
                        starred = ai.isGrammarStarred(g),
                        onToggleStar = { ai.toggleGrammarStar(g, expl) },
                    )
                }
            }
        }
    }
}

/** Formality 1..5 pills (rough → formal). */
@Composable
private fun FormalityBar(level: Int) {
    val colors = listOf(Color(0xFFFF6B6B), Color(0xFFFFA36B), Color(0xFFFFD36B), Color(0xFFB6E36B), Color(0xFF6BD3A8))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("随意 ◀", color = Wa2Colors.TextDim, fontSize = 11.sp)
        for (i in 1..5) {
            Box(
                Modifier.width(22.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (i <= level) colors[i - 1] else Color(0x33FFFFFF)),
            )
        }
        Text("▶ 正式", color = Wa2Colors.TextDim, fontSize = 11.sp)
        Text("  $level/5", color = Wa2Colors.TextDim, fontSize = 11.sp)
    }
}

/** Inline furigana line (non-interactive). */
@Composable
private fun RubyLine(tokens: List<RubyToken>, fallback: String, size: TextUnit) {
    val density = LocalDensity.current
    val rubyBoxH = with(density) { 13.sp.toDp() }
    val baseBoxH = with(density) { (size.value + 6f).sp.toDp() }
    val lineH = (size.value + 2f).sp
    if (tokens.none { !it.ruby.isNullOrBlank() }) {
        Box(Modifier.height(baseBoxH), contentAlignment = Alignment.Center) {
            Text(fallback, color = Wa2Colors.Text, fontSize = size, lineHeight = lineH, style = TIGHT_TEXT_STYLE)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        tokens.forEach { t ->
            val r = t.ruby
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Box(Modifier.height(rubyBoxH), contentAlignment = Alignment.BottomCenter) {
                    if (!r.isNullOrBlank()) {
                        Text(
                            r,
                            color = Wa2Colors.TextDim,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            maxLines = 1,
                            style = TIGHT_TEXT_STYLE,
                        )
                    }
                }
                Box(Modifier.height(baseBoxH), contentAlignment = Alignment.Center) {
                    Text(
                        t.base,
                        color = Wa2Colors.Text,
                        fontSize = size,
                        lineHeight = lineH,
                        style = TIGHT_TEXT_STYLE,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VocabRow(v: VocabEntry, starred: Boolean = false, onToggleStar: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(if (RubyBuilder.cleanReading(v.lemmaReading, v.lemma) != null) "${v.lemma}【${v.lemmaReading}】" else v.lemma, color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Tag(partOfSpeechLabel(v.partOfSpeech))
                v.jlpt?.let { Tag(it, TEXTBOOK) }
                v.pitchAccent?.let { Tag("[$it]", Color(0x33FFFFFF), Wa2Colors.TextDim) }
                if (v.surface != v.lemma) Text("（${v.surface}）", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
            if (onToggleStar != null) {
                StarButton(starred = starred, onClick = onToggleStar)
            }
        }
        Text(v.meaning, color = Wa2Colors.Text, fontSize = 13.sp)
        v.conjugation?.let { Text("变形：$it", color = Wa2Colors.TextDim, fontSize = 12.sp) }
        v.notes?.let { Text("注：$it", color = Wa2Colors.TextDim, fontSize = 12.sp) }
    }
}

@Composable
private fun VocabCard(v: VocabEntry, starred: Boolean = false, onToggleStar: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(CARD)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { VocabRow(v, starred = starred, onToggleStar = onToggleStar) }
}

@Composable
private fun GrammarRow(g: GrammarPoint, starred: Boolean = false, onToggleStar: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(g.pattern, color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                g.jlpt?.let { Tag(it, TEXTBOOK) }
            }
            if (onToggleStar != null) {
                StarButton(starred = starred, onClick = onToggleStar)
            }
        }
        Text(g.explanation, color = Wa2Colors.Text, fontSize = 13.sp)
    }
}

@Composable
private fun GrammarCard(g: GrammarPoint, expl: SentenceExplanation, starred: Boolean = false, onToggleStar: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CARD).padding(horizontal = 12.dp, vertical = 8.dp)) {
        GrammarRow(g, starred = starred, onToggleStar = onToggleStar)
        val surfaces = g.chunkIds.mapNotNull { id -> expl.chunks.firstOrNull { it.id == id }?.surface }
        if (surfaces.isNotEmpty()) Text("见：" + surfaces.joinToString("、") { "「$it」" }, color = Wa2Colors.TextDim, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------------------------- follow-up

private fun LazyListScope.followUpItems(state: AiBreakdownState, persona: AiPersonaSettings) {
    item(key = "followup-title") { SectionHeader("追问 Follow-up · ${persona.shortName}") }
    state.thread.forEach { m -> item(key = "msg-${m.id}") { Bubble(m.role == FollowUpMessage.ROLE_USER, m.text, persona) } }
    state.pendingQuestion?.let { q ->
        item(key = "pending-q") { Bubble(true, q, persona) }
        item(key = "pending-a") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Wa2Colors.Accent, strokeWidth = 2.dp)
                Text("${persona.shortName}思考中…", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
        }
    }
    state.followUpError?.let { e -> item(key = "followup-error") { Text(e, color = ERROR, fontSize = 12.sp) } }
}

@Composable
private fun Bubble(user: Boolean, text: String, persona: AiPersonaSettings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(12.dp))
                .background(if (user) Wa2Colors.AccentDim.copy(alpha = 0.55f) else CARD).padding(10.dp),
        ) {
            if (!user) Text(persona.shortName, color = Wa2Colors.Name, fontSize = 11.sp)
            Text(if (user) AnnotatedString(text) else markdownLight(text), color = Wa2Colors.Text, fontSize = 14.sp)
        }
    }
}

/** `**bold**` and `` `code` `` only (the follow-up prompt asks for markdown-light answers). */
private fun markdownLight(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) { append(text.substring(i)); i = text.length } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Wa2Colors.Name)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) { append(text.substring(i)); i = text.length } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33FFFFFF))) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/** Quick-question chips + text field; the draft [input] is owned by [DetailPane]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FollowUpInput(state: AiBreakdownState, ai: AiLearningController, input: String, onInput: (String) -> Unit, onSent: () -> Unit) {
    LaunchedEffect(state.failedQuestion) {
        val f = state.failedQuestion
        if (f != null && input.isBlank()) onInput(f)
    }
    fun send(q: String) {
        if (q.isBlank() || state.followUpPending) return
        ai.askFollowUp(q)
        if (state.pendingQuestion != null) onInput("")
        onSent()
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK_QUESTIONS.forEach { q -> Chip(q, enabled = !state.followUpPending) { send(q) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("向${ai.persona.shortName}追问… Ask a follow-up", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Wa2Colors.Text, unfocusedTextColor = Wa2Colors.Text,
                    focusedBorderColor = Wa2Colors.Accent, unfocusedBorderColor = Wa2Colors.AccentDim,
                    cursorColor = Wa2Colors.Accent,
                ),
            )
            Chip("发送 Send", selected = true, enabled = input.isNotBlank() && !state.followUpPending) { send(input) }
        }
    }
}
