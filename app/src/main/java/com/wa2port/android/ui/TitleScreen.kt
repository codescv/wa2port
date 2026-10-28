package com.wa2port.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
 * entry fits a ~405 dp-high landscape screen without scrolling.
 */
@Composable
fun TitleScreen(vm: AppViewModel, onNotebook: (() -> Unit)? = null) {
    val ready = vm.assetRoot != null
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2744), Wa2Colors.Night, Color(0xFF05070D)))),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("WHITE ALBUM 2", color = Wa2Colors.Text, fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
                Text("白色相簿2  ·  ホワイトアルバム2  ·  v${vm.update.currentVersion}", color = Wa2Colors.Accent, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    UpdatePill(vm.update) {
                        vm.update.dialogOpen = true
                        if (vm.update.state is UpdateState.Idle || vm.update.state is UpdateState.Error) vm.update.checkForUpdates()
                    }
                    Box(
                        Modifier
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(19.dp))
                            .background(Color(0x66203A60))
                            .border(1.dp, Wa2Colors.Accent, RoundedCornerShape(19.dp))
                            .clickable { onNotebook?.invoke() }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📒 学习记录 Notebook", color = Wa2Colors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (ready) "资源 Assets: ${vm.assetRoot?.absolutePath}" else "未找到游戏资源 — 请先设置资源文件夹\nGame assets not found — open Asset Folder Setup",
                    color = if (ready) Wa2Colors.TextDim else Color(0xFFFFB0A0),
                    fontSize = 12.sp,
                )
            }
            Column(
                Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                vm.latestContinuable?.let { latest ->
                    TitleButton(
                        "继续 Continue",
                        "最近: ${sceneLabel(latest.scene)} · ${relativeTimeText(latest.timestamp)}",
                        enabled = ready, primary = true, modifier = Modifier.fillMaxWidth(),
                    ) { vm.continueLatest() }
                }
                TitleRow(
                    { m -> TitleButton("序章 IC", "はじめから 1001", enabled = ready, modifier = m) { vm.startGame("1001") } },
                    { m -> TitleButton("终章 CC", "はじめから 2001", enabled = ready, modifier = m) { vm.startGame("2001") } },
                )
                TitleRow(
                    { m -> TitleButton("读取进度 Load", "ロード", enabled = ready, modifier = m) { vm.navigate(Screen.Load) } },
                    { m -> TitleButton("场景回想 Scene Replay", "回想モード", enabled = ready, modifier = m) { vm.navigate(Screen.SceneReplay) } },
                )
                TitleRow(
                    { m -> TitleButton("CG 鉴赏 Gallery", "CG モード", enabled = ready, modifier = m) { vm.navigate(Screen.Gallery) } },
                    { m -> TitleButton("音乐鉴赏 BGM", "音楽モード", enabled = ready, modifier = m) { vm.navigate(Screen.BgmGallery) } },
                )
                TitleRow(
                    { m -> TitleButton("设置 Config", "コンフィグ", modifier = m) { vm.navigate(Screen.Config) } },
                    { m -> TitleButton("资源文件夹 Assets", "素材フォルダ設定", modifier = m) { vm.navigate(Screen.Assets) } },
                )
            }
        }
        if (vm.update.dialogOpen) {
            UpdateDialog(vm.update) { vm.update.dialogOpen = false }
            BackHandler { vm.update.dialogOpen = false }
        }
    }
}

/** Two equal-width title buttons side by side. */
@Composable
private fun TitleRow(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
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
