package com.wa2port.android.ui

import java.util.Locale
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ai.AiLearningController
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.OrientationMode
import com.wa2port.android.data.Settings
import com.wa2port.android.data.WindowMode
import com.wa2port.android.game.AiBreakdownOverlay
import com.wa2port.android.game.GameSession
import com.wa2port.android.game.NamePlate
import com.wa2port.android.sync.CloudSyncManager
import com.wa2port.android.update.UpdateController
import com.wa2port.core.i18n.AppLanguage
import com.wa2port.core.i18n.Wa2Strings
import com.wa2port.core.text.FuriganaMode
import com.wa2port.core.text.MessageLayout
import com.wa2port.core.text.TextScale
import com.wa2port.core.update.AgentFeedbackHelper

/**
 * Full-screen config (title) — same panel as the in-game overlay. Without a game session the AI
 * sample preview (示例预览) is hosted here.
 */
@Composable
fun ConfigScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    ai: AiLearningController? = null,
    update: UpdateController? = null,
    cloudSync: CloudSyncManager? = null,
    initialCategory: ConfigCategory = ConfigCategory.ALL,
) {
    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        ConfigPanel(
            settings = settings,
            onChange = onChange,
            onClose = onClose,
            ai = ai,
            update = update,
            cloudSync = cloudSync,
            initialCategory = initialCategory,
        )
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

/** Top-level category filter tabs in [ConfigPanel] for 1-tap access without long scrolling. */
enum class ConfigCategory {
    ALL,
    TEXT_DISPLAY,
    AUDIO,
    CLOUD_SYNC,
    AI,
    UPDATE,
    ;

    fun localizedLabel(s: Wa2Strings): String = when (this) {
        ALL -> s.configTabAll
        TEXT_DISPLAY -> s.configTabTextDisplay
        AUDIO -> s.configTabAudio
        CLOUD_SYNC -> s.configTabCloudSync
        AI -> s.configTabAi
        UPDATE -> s.configTabUpdate
    }
}

/** Preset values for `settings.windowOpacity`: 85%, 70%, 50%. */
fun windowOpacityPresets(s: Wa2Strings): List<Pair<Int, String>> = listOf(
    85 to s.opacityPresetClear,
    70 to s.opacityPresetOriginal,
    50 to s.opacityPresetTranslucent,
)

/**
 * Text speed, auto speed, volumes, skip mode, display language, window mode, opacity, text size and
 * (when [ai] is given) the AI learning settings. [session] is the running game, if any (previews
 * open above the in-game config). [update] (title config only) adds 版本与更新 App Update. The
 * Agent 反馈 row's 撰写反馈 button opens [AgentFeedbackDialog] above the whole panel.
 */
@Composable
fun ConfigPanel(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onClose: () -> Unit,
    ai: AiLearningController? = null,
    session: GameSession? = null,
    update: UpdateController? = null,
    cloudSync: CloudSyncManager? = null,
    appVersion: String = update?.currentVersion ?: AgentFeedbackHelper.DEFAULT_APP_VERSION,
    initialCategory: ConfigCategory = ConfigCategory.ALL,
) {
    val tabs = remember(ai != null, cloudSync != null) {
        buildList {
            add(ConfigCategory.ALL)
            add(ConfigCategory.TEXT_DISPLAY)
            add(ConfigCategory.AUDIO)
            if (cloudSync != null) add(ConfigCategory.CLOUD_SYNC)
            if (ai != null) add(ConfigCategory.AI)
            add(ConfigCategory.UPDATE)
        }
    }
    var category by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    val activeCategory = if (category in tabs) category else ConfigCategory.ALL
    val scrollState = rememberScrollState()
    LaunchedEffect(activeCategory) { scrollState.scrollTo(0) }

    // 撰写反馈: the in-layout AgentFeedbackDialog overlays the whole panel (not the scrolled list).
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }

    val strings = LocalAppStrings.current
    val compact = LocalCompact.current
    Box(Modifier.fillMaxSize()) {
        // Safe-area / IME padding exactly once here (not on the outer Box hosting AgentFeedbackDialog,
        // which pads itself, and not again on the scroll Column below).
        Column(Modifier.fillMaxSize().screenSafePadding().padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.configTitle, color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f))
                CloseButton(onClose)
            }
            // Sticky category tab bar: 1 tap jumps straight to ✨ AI 学习 or 版本更新 without scrolling.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = tab == activeCategory
                    val shape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier
                            .heightIn(min = 38.dp)
                            .clip(shape)
                            .background(if (selected) Wa2Colors.Accent else Color(0x66203050))
                            .border(1.dp, if (selected) Wa2Colors.Accent else Wa2Colors.AccentDim, shape)
                            .clickable { category = tab }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.localizedLabel(strings),
                            color = if (selected) Wa2Colors.Night else Wa2Colors.Text,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)) {
                if (activeCategory == ConfigCategory.ALL || activeCategory == ConfigCategory.TEXT_DISPLAY) {
                    SectionTitle(strings.configSectionText)
                    LabeledSlider(
                        strings.configTextSpeed, settings.textSpeed, 5..Settings.TEXT_SPEED_INSTANT,
                        if (settings.instantText) strings.configTextSpeedInstant else "${settings.textSpeed}/s",
                    ) { v -> onChange { it.copy(textSpeed = v) } }
                    LabeledSlider(strings.configAutoDelay, settings.autoDelayMs / 100, 3..60, "%.1f s".format(Locale.ROOT, settings.autoDelayMs / 1000f)) { v ->
                        onChange { it.copy(autoDelayMs = v * 100) }
                    }
                    FontSizeSection(settings.textScale, settings.lang, settings.furiganaMode) { v -> onChange { it.withTextScale(v) } }
                    OptionRow(strings.configFurigana) {
                        Segmented(
                            listOf(
                                FuriganaMode.ALL to strings.furiganaModeAll,
                                FuriganaMode.TAP to strings.furiganaModeTap,
                                FuriganaMode.OFF to strings.furiganaModeOff,
                            ),
                            settings.furiganaMode,
                            { v -> onChange { it.copy(furiganaMode = v) } },
                        )
                    }
                    Text(
                        strings.configFuriganaHint,
                        color = Wa2Colors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    OptionRow(strings.configWindowOpacityPresets) {
                        Segmented(
                            windowOpacityPresets(strings),
                            settings.windowOpacity,
                            { v -> onChange { it.copy(windowOpacity = v) } },
                        )
                    }
                    LabeledSlider(strings.configWindowOpacity, settings.windowOpacity, 0..100, "${settings.windowOpacity}%") { v -> onChange { it.copy(windowOpacity = v) } }

                    SectionTitle(strings.configSectionDisplay)
                    OptionRow(strings.configAppLanguage) {
                        Segmented(
                            listOf(
                                AppLanguage.SYSTEM to strings.langSystemDefault,
                                AppLanguage.ZH to "中文",
                                AppLanguage.EN to "English",
                                AppLanguage.JA to "日本語",
                            ),
                            settings.appLanguage,
                            { v -> onChange { it.copy(appLanguage = v) } },
                        )
                    }
                    OptionRow(strings.configOrientation) {
                        Segmented(
                            OrientationMode.entries.map { it to it.localizedLabel(strings) },
                            settings.orientation, { v -> onChange { it.copy(orientation = v) } },
                        )
                    }
                    Text(
                        strings.configOrientationHint,
                        color = Wa2Colors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    OptionRow(strings.configScriptLanguage) {
                        Segmented(
                            listOf(
                                DisplayLang.BOTH to strings.scriptLangBoth,
                                DisplayLang.CN to strings.scriptLangCn,
                                DisplayLang.JP to strings.scriptLangJp,
                            ),
                            settings.lang, { v -> onChange { it.copy(lang = v) } },
                        )
                    }
                    OptionRow(strings.configWindowMode) {
                        Segmented(
                            listOf(WindowMode.ADV to strings.windowModeAdv, WindowMode.NOVEL to strings.windowModeNovel),
                            settings.windowMode, { v -> onChange { it.copy(windowMode = v) } },
                        )
                    }
                    OptionRow(strings.configSkip) {
                        Segmented(
                            listOf(false to strings.configSkipReadOnly, true to strings.configSkipAll),
                            settings.skipAll, { v -> onChange { it.copy(skipAll = v) } },
                        )
                    }
                }

                if (activeCategory == ConfigCategory.ALL || activeCategory == ConfigCategory.AUDIO) {
                    SectionTitle(strings.configSectionVolume)
                    LabeledSlider(strings.configVolumeBgm, settings.bgmVolume, 0..100, "${settings.bgmVolume}") { v -> onChange { it.copy(bgmVolume = v) } }
                    LabeledSlider(strings.configVolumeSe, settings.seVolume, 0..100, "${settings.seVolume}") { v -> onChange { it.copy(seVolume = v) } }
                    LabeledSlider(strings.configVolumeVoice, settings.voiceVolume, 0..100, "${settings.voiceVolume}") { v -> onChange { it.copy(voiceVolume = v) } }
                }

                if (cloudSync != null && (activeCategory == ConfigCategory.ALL || activeCategory == ConfigCategory.CLOUD_SYNC)) {
                    CloudSyncSettingsSection(settings, onChange, cloudSync)
                }
                if (ai != null && (activeCategory == ConfigCategory.ALL || activeCategory == ConfigCategory.AI)) {
                    AiSettingsSection(settings, onChange, ai, session)
                }
                if (activeCategory == ConfigCategory.ALL || activeCategory == ConfigCategory.UPDATE) {
                    if (update != null) {
                        UpdateSettingsSection(update)
                    }
                    AgentFeedbackSection(
                        settings = settings,
                        onOpenFeedback = { showFeedbackDialog = true },
                    )
                }
            }
        }
        if (showFeedbackDialog) {
            AgentFeedbackDialog(
                settings = settings,
                onChange = onChange,
                onDismiss = { showFeedbackDialog = false },
                currentVersion = appVersion,
                session = session,
            )
        }
    }
}

/**
 * Font size: one-tap presets (小 85 % … 超大 145 %), − / + steps (5 %), a fine 70 %–150 % slider
 * and a live preview of the ADV window at the chosen size.
 */
@Composable
private fun FontSizeSection(textScale: Int, lang: DisplayLang, furiganaMode: FuriganaMode, onSet: (Int) -> Unit) {
    val strings = LocalAppStrings.current
    val current = TextScale.clamp(textScale)
    OptionRow(strings.configFontPresets) {
        Segmented(
            TextScale.localizedPresets(strings).map { (p, label) -> p to "$label $p%" }, current, onSet,
        )
    }
    val compact = LocalCompact.current
    // Compact: label above a `[−][slider][+][%]` row; wide: label inline (unchanged).
    if (compact) Text(strings.configFontSize, color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) Text(strings.configFontSize, color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.width(150.dp))
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
        Text("$current%", color = Wa2Colors.TextDim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(if (compact) 48.dp else 56.dp))
    }
    FontPreviewCard(current, lang, furiganaMode, LocalIsPortrait.current)
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
private fun FontPreviewCard(percent: Int, lang: DisplayLang, furiganaMode: FuriganaMode = FuriganaMode.ALL, portrait: Boolean = false) {
    if (portrait) {
        PortraitFontPreviewCard(percent, lang, furiganaMode)
        return
    }
    val t = TextScale.factor(percent)
    val density = LocalDensity.current
    val twoCol = lang == DisplayLang.BOTH
    val textStage = (if (twoCol) MessageLayout.TWO_COL_PX else MessageLayout.MAIN_PX) * t
    val capChars = if (twoCol) {
        MessageLayout.lineCap(MessageLayout.advColumnWidth(t), textStage, MessageLayout.TWO_COL_MAX_CHARS)
    } else {
        MessageLayout.lineCap(MessageLayout.advSingleWidth(), textStage, MessageLayout.SINGLE_MAX_CHARS)
    }.toInt()
    val strings = LocalAppStrings.current
    val lines = if (twoCol) MessageLayout.ADV_TWO_COL_LINES else MessageLayout.ADV_MIN_LINES
    val windowFrac = MessageLayout.ADV_BOTTOM - MessageLayout.advTop(t, twoCol)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(strings.configPreviewTitle, color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                            PreviewJpText(textSp, lineSp, furiganaMode, modifier = Modifier.weight(1f))
                        }
                    } else if (lang == DisplayLang.JP) {
                        PreviewJpText(textSp, lineSp, furiganaMode)
                    } else {
                        Text(PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp)
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
            strings.configPreviewCaptionLandscape(
                percent,
                TextScale.presetLabel(percent, strings),
                lines,
                capChars,
                Math.round(windowFrac * 100),
            ),
            color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Portrait deck scale: dp per stage px (the deck renders as if the stage were 412 dp tall). */
private const val PREVIEW_DECK_SCALE = 412f / 720f

/** Gap (dp) between the stacked CN and JP blocks of the portrait deck. */
private const val PREVIEW_STACKED_GAP_DP = 18f

/**
 * Portrait 实时预览: the reading deck at 1:1 true size. Glyph em = stage px × (412 / 720) × scale
 * dp (`dp.toSp()`, no system fontScale compounding), chrome (pad, name box, radius) likewise; the
 * card is as wide as the deck (screen − 2 × 16 dp). 双语 is stacked (CN above, JP below, 18 dp
 * gap) unless two 19-char columns fit; 中文 / 日本語 use one 30 px × scale block.
 */
@Composable
private fun PortraitFontPreviewCard(percent: Int, lang: DisplayLang, furiganaMode: FuriganaMode = FuriganaMode.ALL) {
    val strings = LocalAppStrings.current
    val t = TextScale.factor(percent)
    val density = LocalDensity.current
    val s = PREVIEW_DECK_SCALE
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(strings.configPreviewPortraitTitle, color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            fun spOfDp(v: Float) = with(density) { v.dp.toSp() }
            val bilingual = lang == DisplayLang.BOTH
            val pad = MessageLayout.ADV_PAD * s
            val textW = maxWidth.value - 2 * pad
            val em = (if (bilingual) MessageLayout.TWO_COL_PX else MessageLayout.MAIN_PX) * s * t
            val colGap = MessageLayout.advColumnGap(t) * s
            val twoCol = bilingual && (textW - colGap) / 2f >= MessageLayout.TWO_COL_MAX_CHARS * em
            val capChars = if (twoCol) {
                MessageLayout.lineCap((textW - colGap) / 2f, em, MessageLayout.TWO_COL_MAX_CHARS)
            } else {
                MessageLayout.lineCap(textW - maxOf(0f, em - pad), em, MessageLayout.SINGLE_MAX_CHARS)
            }.toInt()
            val textSp = spOfDp(em)
            val lineSp = spOfDp(em * MessageLayout.LINE_PITCH)
            val nameH = MessageLayout.NAME_PX * s * t * NamePlate.PORTRAIT_TAB_EM
            val nameAbove = nameH * NamePlate.OVERHANG
            val shape = RoundedCornerShape((14f * s).dp)
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = nameAbove.dp)
                            .clip(shape).background(Color(0xFF0A1428)).border(1.dp, Wa2Colors.AccentDim, shape)
                            .padding(start = pad.dp, end = pad.dp, top = (pad * 1.25f).dp, bottom = (pad * 0.6f).dp),
                    ) {
                        when {
                            twoCol -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                Text(PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp, modifier = Modifier.weight(1f))
                                Box(Modifier.width(colGap.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                    Box(Modifier.width(1.dp).fillMaxHeight().background(Wa2Colors.AccentDim.copy(alpha = 0.45f)))
                                }
                                PreviewJpText(textSp, lineSp, furiganaMode, modifier = Modifier.weight(1f))
                            }
                            bilingual -> {
                                Text(PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp)
                                Box(Modifier.fillMaxWidth().height(PREVIEW_STACKED_GAP_DP.dp), contentAlignment = Alignment.Center) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(Wa2Colors.AccentDim.copy(alpha = 0.45f)))
                                }
                                PreviewJpText(textSp, lineSp, furiganaMode)
                            }
                            lang == DisplayLang.JP -> PreviewJpText(textSp, lineSp, furiganaMode)
                            else -> Text(PREVIEW_CN, color = Wa2Colors.Text, fontSize = textSp, lineHeight = lineSp)
                        }
                    }
                    // Speaker name box overlapping the deck top, as in AdvWindow.
                    Box(
                        Modifier.padding(start = pad.dp).height(nameH.dp).clip(RoundedCornerShape((8f * s).dp))
                            .background(Color(0xFF16284A)).border(1.dp, Wa2Colors.AccentDim, RoundedCornerShape((8f * s).dp))
                            .padding(horizontal = pad.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(PREVIEW_NAME, color = Wa2Colors.Name, fontSize = spOfDp(MessageLayout.NAME_PX * s * t))
                    }
                }
                Text(
                    strings.configPreviewCaptionPortrait(
                        percent,
                        TextScale.presetLabel(percent, strings),
                        if (twoCol) strings.configPreviewLayoutTwoCol else if (bilingual) strings.configPreviewLayoutStacked else strings.configPreviewLayoutSingle,
                        capChars,
                    ),
                    color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private const val PREVIEW_NAME = "雪菜"
private const val PREVIEW_CN = "「……啊，下雪了呢。今年的冬天，好像也会很冷吧。」"
private const val PREVIEW_JP = "「……あ、雪だね。今年の冬も、きっと寒くなるね」"

private data class PreviewToken(val base: String, val ruby: String? = null)

private val PREVIEW_JP_TOKENS = listOf(
    PreviewToken("「"), PreviewToken("…"), PreviewToken("…"), PreviewToken("あ、"),
    PreviewToken("雪", "ゆき"),
    PreviewToken("だ"), PreviewToken("ね。"),
    PreviewToken("今年", "ことし"),
    PreviewToken("の"),
    PreviewToken("冬", "ふゆ"),
    PreviewToken("も、"), PreviewToken("き"), PreviewToken("っ"), PreviewToken("と"),
    PreviewToken("寒", "さむ"),
    PreviewToken("く"), PreviewToken("な"), PreviewToken("る"), PreviewToken("ね」"),
)

/**
 * Live preview of the Japanese sample line reflecting [furiganaMode]:
 * - [FuriganaMode.ALL]: shows hiragana readings above kanji.
 * - [FuriganaMode.TAP]: reserves ruby headroom with zero shift; tapping a kanji toggles its reading.
 * - [FuriganaMode.OFF]: plain text without auto furigana.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewJpText(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    furiganaMode: FuriganaMode,
    modifier: Modifier = Modifier,
) {
    if (furiganaMode == FuriganaMode.OFF) {
        Text(PREVIEW_JP, color = Wa2Colors.Text, fontSize = fontSize, lineHeight = lineHeight, modifier = modifier)
        return
    }
    var revealed by remember(furiganaMode) { mutableStateOf(emptySet<Int>()) }
    val rubySize = fontSize * 0.45f
    val rubyLine = fontSize * 0.50f
    FlowRow(modifier = modifier) {
        PREVIEW_JP_TOKENS.forEachIndexed { idx, tok ->
            val hasRuby = tok.ruby != null
            val showRuby = hasRuby && (furiganaMode == FuriganaMode.ALL || idx in revealed)
            val tapModifier = if (furiganaMode == FuriganaMode.TAP && hasRuby) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    revealed = if (idx in revealed) revealed - idx else revealed + idx
                }
            } else {
                Modifier
            }
            Column(
                modifier = tapModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = tok.ruby ?: " ",
                    color = if (showRuby) Wa2Colors.Text else Color.Transparent,
                    fontSize = rubySize,
                    lineHeight = rubyLine,
                    maxLines = 1,
                )
                Text(
                    text = tok.base,
                    color = Wa2Colors.Text,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun OptionRow(label: String, content: @Composable () -> Unit) {
    if (LocalCompact.current) {
        // Compact: label above the control row; no scroll modifier here (Segmented owns its scroll).
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, color = Wa2Colors.Text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
        return
    }
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
