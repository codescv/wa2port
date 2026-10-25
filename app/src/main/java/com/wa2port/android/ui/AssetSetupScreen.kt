package com.wa2port.android.ui

import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.AppViewModel
import com.wa2port.android.data.AssetLocator
import com.wa2port.android.data.ImportProgress

/** Shows where assets are searched, SAF import with resumable progress, and a manual path field. */
@Composable
fun AssetSetupScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.startImport(uri)
    }
    val progress = vm.importProgress
    val running = progress?.running == true
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    var path by remember { mutableStateOf(vm.settings.assetPath ?: AssetLocator.TMP_DIR) }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("资源文件夹 Asset Folder Setup", color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f))
            CloseButton { vm.back() }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "复制 PC 版游戏文件夹（含 script.pak / ck-gal.pak / fon.pak 等）。Copy your PC WA2 folder (with the CK-GAL patch). " +
                    "adb: adb push WA2/. ${AssetLocator.importDir(view.context).absolutePath}",
                color = Wa2Colors.TextDim, fontSize = 12.sp,
            )
            SectionTitle("搜索位置 Search locations")
            vm.candidates.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33203050)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            c.valid -> "✔"
                            c.exists -> "✖"
                            else -> "—"
                        },
                        color = if (c.valid) Color(0xFF8FE3A0) else Wa2Colors.TextDim, fontSize = 18.sp, modifier = Modifier.width(32.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(c.label, color = Wa2Colors.Text, fontSize = 14.sp)
                        Text(c.dir.absolutePath, color = Wa2Colors.TextDim, fontSize = 11.sp)
                    }
                    Text(
                        when {
                            c.valid && c.dir.absolutePath == vm.assetRoot?.absolutePath -> "使用中 In use"
                            c.valid -> "有效 Valid"
                            c.exists -> "缺少 script.pak"
                            else -> "不存在 Not found"
                        },
                        color = Wa2Colors.TextDim, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MenuButton("从文件夹导入 Import folder…", "SAF 选择游戏根目录", enabled = !running) { picker.launch(null) }
                if (!running && vm.settings.importTreeUri != null && progress?.phase != ImportProgress.Phase.DONE) {
                    MenuButton("继续导入 Resume import", "跳过已复制文件") { vm.resumeImport() }
                }
                if (running) MenuButton("暂停 Pause", "可稍后继续") { vm.cancelImport() }
                MenuButton("重新扫描 Rescan", enabled = !running) { vm.rescanAssets() }
            }
            if (progress != null) ImportStatus(progress)

            SectionTitle("自定义路径 Custom path")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                MenuButton("使用 Use") { vm.useCustomPath(path) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ImportStatus(p: ImportProgress) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val title = when (p.phase) {
            ImportProgress.Phase.SCANNING -> "扫描中… Scanning…"
            ImportProgress.Phase.COPYING -> "复制中 Copying %d/%d  %.2f / %.2f GB".format(Locale.ROOT, p.filesDone + 1, p.filesTotal, p.bytesDone / 1e9, p.bytesTotal / 1e9)
            ImportProgress.Phase.DONE -> "完成 Done (%d files, %.2f GB)".format(Locale.ROOT, p.filesTotal, p.bytesTotal / 1e9)
            ImportProgress.Phase.FAILED -> "失败 Failed"
            ImportProgress.Phase.CANCELLED -> "已暂停 Paused"
        }
        Text(title, color = Wa2Colors.Text, fontSize = 14.sp)
        if (p.phase == ImportProgress.Phase.SCANNING) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent)
        } else {
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Wa2Colors.Accent)
        }
        if (p.current.isNotEmpty()) Text(p.current, color = Wa2Colors.TextDim, fontSize = 11.sp)
        p.message?.let { Text(it, color = Color(0xFFFFB0A0), fontSize = 12.sp) }
    }
}
