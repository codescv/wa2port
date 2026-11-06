package com.wa2port.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.AppViewModel
import com.wa2port.android.Screen
import com.wa2port.android.update.UpdateState

/**
 * Title: 「继续 Continue」 (newest save, when any), start IC/CC, load, galleries, config, asset
 * folder setup, version + 检查更新 (update overlay). The menu is a compact two-column grid so every
 * entry fits a ~405 dp-high landscape screen without scrolling. Compact ([LocalCompact], §3.5): a
 * single scrolling Column (hero, then full-width buttons; one button per row below 480 dp).
 */
@Composable
fun TitleScreen(vm: AppViewModel, onNotebook: (() -> Unit)? = null) {
    val ready = vm.assetRoot != null
    var showFeedbackDialog by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2744), Wa2Colors.Night, Color(0xFF05070D)))),
    ) {
        if (LocalCompact.current) {
            // Compact (§3.5): one scrolling Column — hero on top, buttons full width below.
            val singleColumn = maxWidth < 480.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .screenSafePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                TitleHero(vm, ready, onNotebook, compact = true)
                Spacer(Modifier.height(16.dp))
                TitleButtons(vm, ready, singleColumn = singleColumn, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxSize().screenSafePadding().padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    TitleHero(vm, ready, onNotebook, compact = false)
                }
                TitleButtons(
                    vm, ready, singleColumn = false,
                    modifier = Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                )
            }
        }
        if (vm.update.dialogOpen) {
            UpdateDialog(
                vm.update,
                onDismiss = { vm.update.dialogOpen = false },
                onOpenFeedback = {
                    vm.update.dialogOpen = false
                    showFeedbackDialog = true
                },
            )
            BackHandler { vm.update.dialogOpen = false }
        }
        if (showFeedbackDialog) {
            AgentFeedbackDialog(
                settings = vm.settings,
                onChange = vm::updateSettings,
                onDismiss = { showFeedbackDialog = false },
                currentVersion = vm.update.currentVersion,
            )
        }
    }
}

/** Title, version line, pills (update · 📒 · ⟳ orientation) and the asset path. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleHero(vm: AppViewModel, ready: Boolean, onNotebook: (() -> Unit)?, compact: Boolean) {
    val isPortrait = LocalIsPortrait.current
    Text(
        "WHITE ALBUM 2", color = Wa2Colors.Text, fontSize = if (compact) 30.sp else 40.sp,
        fontWeight = FontWeight.Light, letterSpacing = if (compact) 4.sp else 6.sp,
    )
    Text("白色相簿2  ·  ホワイトアルバム2  ·  v${vm.update.currentVersion}", color = Wa2Colors.Accent, fontSize = 18.sp)
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.align(Alignment.CenterVertically)) {
            UpdatePill(vm.update) {
                vm.update.dialogOpen = true
                if (vm.update.state is UpdateState.Idle || vm.update.state is UpdateState.Error) vm.update.checkForUpdates()
            }
        }
        TitlePill("📒 学习记录 Notebook", Modifier.align(Alignment.CenterVertically)) { onNotebook?.invoke() }
        TitlePill(
            if (isPortrait) "⟳ 横屏 Landscape" else "⟳ 竖屏 Portrait",
            Modifier.align(Alignment.CenterVertically),
        ) { vm.updateSettings { it.copy(orientation = it.orientation.toggle(isPortrait)) } }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        if (ready) "资源 Assets: ${vm.assetRoot?.absolutePath}" else "未找到游戏资源 — 请先设置资源文件夹\nGame assets not found — open Asset Folder Setup",
        color = if (ready) Wa2Colors.TextDim else Color(0xFFFFB0A0),
        fontSize = 12.sp,
        maxLines = if (compact) 2 else Int.MAX_VALUE,
        overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** Accent-bordered rounded pill on the title hero (📒 Notebook, ⟳ orientation). */
@Composable
private fun TitlePill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0x66203A60))
            .border(1.dp, Wa2Colors.Accent, RoundedCornerShape(19.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The title menu. WIDE: the 360 dp scrolling column passed in [modifier]; compact: full width inside
 * the outer scrolling Column (no scroll of its own). [singleColumn] stacks every button (< 480 dp).
 */
@Composable
private fun TitleButtons(vm: AppViewModel, ready: Boolean, singleColumn: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        vm.latestContinuable?.let { latest ->
            TitleButton(
                "继续 Continue",
                "最近: ${sceneLabel(latest.scene)} · ${relativeTimeText(latest.timestamp)}",
                enabled = ready, primary = true, modifier = Modifier.fillMaxWidth(),
            ) { vm.continueLatest() }
        }
        TitleRow(
            singleColumn,
            { m -> TitleButton("序章 IC", "はじめから 1001", enabled = ready, modifier = m) { vm.startGame("1001") } },
            { m -> TitleButton("终章 CC", "はじめから 2001", enabled = ready, modifier = m) { vm.startGame("2001") } },
        )
        TitleRow(
            singleColumn,
            { m -> TitleButton("读取进度 Load", "ロード", enabled = ready, modifier = m) { vm.navigate(Screen.Load) } },
            { m -> TitleButton("场景回想 Scene Replay", "回想モード", enabled = ready, modifier = m) { vm.navigate(Screen.SceneReplay) } },
        )
        TitleRow(
            singleColumn,
            { m -> TitleButton("CG 鉴赏 Gallery", "CG モード", enabled = ready, modifier = m) { vm.navigate(Screen.Gallery) } },
            { m -> TitleButton("音乐鉴赏 BGM", "音楽モード", enabled = ready, modifier = m) { vm.navigate(Screen.BgmGallery) } },
        )
        TitleRow(
            singleColumn,
            { m -> TitleButton("设置 Config", "コンフィグ", modifier = m) { vm.openConfig(ConfigCategory.ALL) } },
            { m -> TitleButton("资源文件夹 Assets", "素材フォルダ設定", modifier = m) { vm.navigate(Screen.Assets) } },
        )
    }
}

/** Two equal-width title buttons side by side, or stacked full width when [single] (compact < 480 dp). */
@Composable
private fun TitleRow(single: Boolean, left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    if (single) {
        left(Modifier.fillMaxWidth())
        right(Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}

/** Compact bilingual title button (≥ 48 dp touch height); [primary] = accent border (「继续」). */
@Composable
private fun TitleButton(
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val border = when {
        !enabled -> Color(0x33FFFFFF)
        primary -> Wa2Colors.Accent
        else -> Wa2Colors.AccentDim
    }
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (!enabled) Color(0x33202030) else if (primary) Color(0x99203A60) else Color(0x66203050))
            .border(BorderStroke(if (primary) 1.5.dp else 1.dp, border), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title, color = if (enabled) Wa2Colors.Text else Wa2Colors.TextDim,
            fontSize = if (primary) 18.sp else 14.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(subtitle, color = Wa2Colors.TextDim, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
