package com.wa2port.android.ui

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.Settings
import com.wa2port.android.data.WindowMode

/** Full-screen config (title) — same panel as the in-game overlay. */
@Composable
fun ConfigScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        ConfigPanel(settings, onChange, onClose)
    }
}

/** Text speed, auto speed, volumes, skip mode, display language, window mode, opacity, text size. */
@Composable
fun ConfigPanel(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
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
            LabeledSlider("文字大小 Text size", settings.textScale, 70..150, "${settings.textScale}%") { v -> onChange { it.copy(textScale = v) } }
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
        }
    }
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
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
