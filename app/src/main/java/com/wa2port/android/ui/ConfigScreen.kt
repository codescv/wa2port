package com.wa2port.android.ui

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ai.AiLearningController
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.Settings
import com.wa2port.android.data.WindowMode
import com.wa2port.android.game.AiBreakdownOverlay
import com.wa2port.android.game.GameSession
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.TextScale

/**
 * Full-screen config (title) — same panel as the in-game overlay. Without a game session the AI
 * sample preview (示例预览) is hosted here.
 */
@Composable
fun ConfigScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onClose: () -> Unit, ai: AiLearningController? = null) {
    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        ConfigPanel(settings, onChange, onClose, ai)
        ai?.breakdown?.let { st ->
            AiBreakdownOverlay(
                state = st,
                ai = ai,
                persona = ai.persona,
                onPersona = { id -> onChange { it.copy(aiPersonaId = id) } },
                onReplayVoice = null,
                onOpenSettings = { ai.close(null) },
                onClose = { ai.close(null) },
            )
        }
    }
}

/**
 * Text speed, auto speed, volumes, skip mode, display language, window mode, opacity, text size and
 * (when [ai] is given) the AI learning settings. [session] is the running game, if any (previews
 * open above the in-game config).
 */
@Composable
fun ConfigPanel(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    ai: AiLearningController? = null,
    session: GameSession? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设置 Config  コンフィグ", color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f))
            CloseButton(onClose)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SectionTitle("文字 Text")
            LabeledSlider(
                "文字速度 Text speed", settings.textSpeed, 5..Settings.TEXT_SPEED_INSTANT,
                if (settings.instantText) "瞬间 Instant" else "${settings.textSpeed}/s",
            ) { v -> onChange { it.copy(textSpeed = v) } }
            LabeledSlider("自动速度 Auto delay", settings.autoDelayMs / 100, 3..60, "%.1f s".format(Locale.ROOT, settings.autoDelayMs / 1000f)) { v ->
                onChange { it.copy(autoDelayMs = v * 100) }
            }
            FontSizeSection(settings.textScale, settings.lang) { v -> onChange { it.withTextScale(v) } }
            LabeledSlider("窗口透明度 Window", settings.windowOpacity, 0..100, "${settings.windowOpacity}%") { v -> onChange { it.copy(windowOpacity = v) } }

            SectionTitle("显示 Display")
            OptionRow("显示语言 Language") {
                Segmented(
                    listOf(DisplayLang.BOTH to "双语 中+日", DisplayLang.CN to "中文", DisplayLang.JP to "日本語"),
                    settings.lang, { v -> onChange { it.copy(lang = v) } },
                )
            }
            OptionRow("窗口模式 Window mode") {
                Segmented(
                    listOf(WindowMode.ADV to "ADV 对话框", WindowMode.NOVEL to "NOVEL 全屏"),
                    settings.windowMode, { v -> onChange { it.copy(windowMode = v) } },
                )
            }
            OptionRow("快进 Skip") {
                Segmented(
                    listOf(false to "仅已读 Read only (既読)", true to "全部 All"),
                    settings.skipAll, { v -> onChange { it.copy(skipAll = v) } },
                )
            }

            SectionTitle("音量 Volume")
            LabeledSlider("音乐 BGM", settings.bgmVolume, 0..100, "${settings.bgmVolume}") { v -> onChange { it.copy(bgmVolume = v) } }
            LabeledSlider("音效 SE", settings.seVolume, 0..100, "${settings.seVolume}") { v -> onChange { it.copy(seVolume = v) } }
            LabeledSlider("语音 Voice", settings.voiceVolume, 0..100, "${settings.voiceVolume}") { v -> onChange { it.copy(voiceVolume = v) } }

            if (ai != null) AiSettingsSection(settings, onChange, ai, session)
        }
    }
}

/**
 * Font size: one-tap presets (小 85 % … 超大 145 %), − / + steps (5 %), a fine 70 %–150 % slider
 * and a live preview of the ADV window at the chosen size.
 */
@Composable
private fun FontSizeSection(textScale: Int, lang: DisplayLang, onSet: (Int) -> Unit) {
    val current = TextScale.clamp(textScale)
    OptionRow("字号预设 Presets") {
        Segmented(
            TextScale.PRESETS.map { (p, label) -> p to "$label $p%" }, current, onSet,
            Modifier.horizontalScroll(rememberScrollState()),
        )
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("文字大小 Text size", color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.width(150.dp))
        StepButton("−", enabled = current > TextScale.MIN) { onSet(TextScale.step(current, up = false)) }
        Slider(
            value = current.toFloat(),
            onValueChange = { onSet(Math.round(it / TextScale.STEP) * TextScale.STEP) },
            valueRange = TextScale.MIN.toFloat()..TextScale.MAX.toFloat(),
            steps = (TextScale.MAX - TextScale.MIN) / TextScale.STEP - 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Wa2Colors.Accent, activeTrackColor = Wa2Colors.Accent),
        )
        StepButton("+", enabled = current < TextScale.MAX) { onSet(TextScale.step(current, up = true)) }
        Text("$current%", color = Wa2Colors.TextDim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
    }
    FontPreviewCard(current, lang)
}

/** 48 dp square − / + button. */
@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(48.dp).clip(shape)
            .background(if (enabled) Wa2Colors.Button else Color(0x22202030))
            .border(1.dp, if (enabled) Wa2Colors.AccentDim else Color(0x33FFFFFF), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Wa2Colors.Text else Wa2Colors.TextDim, fontSize = 22.sp)
    }
}

/**
 * 实时预览: a to-scale miniature of the ADV window at [percent], using the same stage geometry as
 * `AdvWindow` (window height from [MessageLayout.advTop], speaker name above the window) and
 * following [lang]: 双语 = CN | JP columns at 28 px × scale, 中文 / 日本語 = one 30 px × scale
 * column. The caption shows the resulting per-line cap and window height. Drawn with Compose
 * `Text` (no glyph-atlas painter exists on the title screen), so it shows the relative size.
 */
@Composable
private fun FontPreviewCard(percent: Int, lang: DisplayLang) {
    val t = TextScale.factor(percent)
    val density = LocalDensity.current
    val twoCol = lang == DisplayLang.BOTH
    val textStage = (if (twoCol) MessageLayout.TWO_COL_PX else MessageLayout.MAIN_PX) * t
    val capChars = if (twoCol) {
        MessageLayout.lineCap(MessageLayout.advColumnWidth(t), textStage, MessageLayout.TWO_COL_MAX_CHARS, stepped = false)
    } else {
        MessageLayout.lineCap(MessageLayout.advSingleWidth(), textStage, MessageLayout.SINGLE_MAX_CHARS, stepped = false)
    }.toInt()
    val windowFrac = MessageLayout.ADV_BOTTOM - MessageLayout.advTop(t)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text("实时预览 Preview", color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
            // k: device px per stage px, taking the card width as the ADV window width.
            val k = with(density) { maxWidth.toPx() } / ((MessageLayout.ADV_RIGHT - MessageLayout.ADV_LEFT) * MessageLayout.STAGE_W)
            fun dpOf(stage: Float) = with(density) { (stage * k).toDp() }
            fun spOf(stage: Float) = with(density) { (stage * k).toSp() }
            val shape = RoundedCornerShape(dpOf(14f))
            val nameH = MessageLayout.NAME_PX * t * 1.5f
            val nameAbove = nameH * 0.55f
            val textSp = spOf(textStage)
            val lineSp = spOf(textStage * MessageLayout.LINE_PITCH)
            Box(Modifier.fillMaxWidth().height(dpOf(windowFrac * MessageLayout.STAGE_H + nameAbove))) {
                Box(
                    Modifier.fillMaxSize().padding(top = dpOf(nameAbove))
                        .clip(shape).background(Color(0xFF0A1428)).border(1.dp, Wa2Colors.AccentDim, shape)
                        .padding(start = dpOf(MessageLayout.ADV_PAD), end = dpOf(MessageLayout.ADV_PAD), top = dpOf(MessageLayout.ADV_PAD * 1.25f)),
                ) {
                    if (twoCol) {
                        Row(Modifier.fillMaxSize()) {
                            Text(PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp, modifier = Modifier.weight(1f))
                            Box(Modifier.width(dpOf(MessageLayout.advColumnGap(t))).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.width(1.dp).fillMaxHeight().background(Wa2Colors.AccentDim.copy(alpha = 0.45f)))
                            }
                            Text(PREVIEW_JP, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp, modifier = Modifier.weight(1f))
                        }
                    } else {
                        Text(if (lang == DisplayLang.JP) PREVIEW_JP else PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp)
                    }
                }
                // Speaker name box overlapping the window top, as in AdvWindow.
                Box(
                    Modifier.padding(start = dpOf(MessageLayout.ADV_PAD)).height(dpOf(nameH)).clip(RoundedCornerShape(dpOf(8f)))
                        .background(Color(0xFF16284A)).padding(horizontal = dpOf(MessageLayout.ADV_PAD)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(PREVIEW_NAME, color = Wa2Colors.Name, fontSize = spOf(MessageLayout.NAME_PX * t))
                }
            }
        }
        Text(
            "$percent%${TextScale.presetLabel(percent)?.let { " · $it" } ?: ""} · 每行 ≤$capChars 字 chars/line · " +
                "窗口 Window ${Math.round(windowFrac * 100)}%",
            color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private const val PREVIEW_NAME = "雪菜"
private const val PREVIEW_CN = "「……啊，下雪了呢。今年的冬天，好像也会很冷吧。」"
private const val PREVIEW_JP = "「……あ、雪だね。今年の冬も、きっと寒くなるね」"

@Composable
internal fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.widthIn(min = 150.dp).padding(end = 12.dp))
        content()
    }
}

/** 48 dp "✕" close button. */
@Composable
fun CloseButton(onClose: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", color = Wa2Colors.Text, fontSize = 22.sp)
    }
}

/** Horizontal arrangement helper reused by panels. */
val PanelSpacing = Arrangement.spacedBy(8.dp)
