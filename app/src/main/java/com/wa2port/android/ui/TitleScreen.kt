package com.wa2port.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.AppViewModel
import com.wa2port.android.Screen

/** Title: start IC/CC, load, config, asset folder setup. */
@Composable
fun TitleScreen(vm: AppViewModel) {
    val ready = vm.assetRoot != null
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B2744), Wa2Colors.Night, Color(0xFF05070D)))),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WHITE ALBUM 2", color = Wa2Colors.Text, fontSize = 40.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
                Text("白色相簿2  ·  ホワイトアルバム2", color = Wa2Colors.Accent, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (ready) "资源 Assets: ${vm.assetRoot?.absolutePath}" else "未找到游戏资源 — 请先设置资源文件夹\nGame assets not found — open Asset Folder Setup",
                    color = if (ready) Wa2Colors.TextDim else Color(0xFFFFB0A0),
                    fontSize = 12.sp,
                )
            }
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                MenuButton("序章 Introductory Chapter", "开始 / はじめから (1001)", enabled = ready, modifier = Modifier.fillMaxWidth()) { vm.startGame("1001") }
                MenuButton("终章 Closing Chapter", "开始 / はじめから (2001)", enabled = ready, modifier = Modifier.fillMaxWidth()) { vm.startGame("2001") }
                MenuButton("读取进度 Load", "ロード", enabled = ready, modifier = Modifier.fillMaxWidth()) { vm.navigate(Screen.Load) }
                MenuButton("设置 Config", "コンフィグ", modifier = Modifier.fillMaxWidth()) { vm.navigate(Screen.Config) }
                MenuButton("资源文件夹 Asset Folder", "素材フォルダ設定", modifier = Modifier.fillMaxWidth()) { vm.navigate(Screen.Assets) }
            }
        }
    }
}
