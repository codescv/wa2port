package com.wa2port.android.ui

import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

/**
 * Shows where assets are searched, SAF import with resumable progress, and a manual path field.
 * Action buttons wrap ([FlowRow]); compact ([LocalCompact]) uses 16 dp side padding.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    val strings = LocalAppStrings.current
    val compact = LocalCompact.current
    Column(Modifier.fillMaxSize().screenSafePadding().padding(horizontal = if (compact) 16.dp else 32.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.assetsTitle, color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f))
            CloseButton { vm.back() }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                strings.assetsGuide(AssetLocator.importDir(view.context).absolutePath),
                color = Wa2Colors.TextDim, fontSize = 12.sp,
            )
            SectionTitle(strings.assetsSectionSearch)
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
                        Text(c.kind.localizedLabel(strings), color = Wa2Colors.Text, fontSize = 14.sp)
                        Text(c.dir.absolutePath, color = Wa2Colors.TextDim, fontSize = 11.sp)
                    }
                    Text(
                        when {
                            c.valid && c.dir.absolutePath == vm.assetRoot?.absolutePath -> strings.assetsStatusInUse
                            c.valid -> strings.assetsStatusValid
                            c.exists -> strings.assetsStatusMissingPak
                            else -> strings.assetsStatusNotFound
                        },
                        color = Wa2Colors.TextDim, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuButton(strings.assetsImportBtn, strings.assetsImportSub, enabled = !running) { picker.launch(null) }
                if (!running && vm.settings.importTreeUri != null && progress?.phase != ImportProgress.Phase.DONE) {
                    MenuButton(strings.assetsResumeBtn, strings.assetsResumeSub) { vm.resumeImport() }
                }
                if (running) MenuButton(strings.commonPause, strings.assetsPauseSub) { vm.cancelImport() }
                MenuButton(strings.assetsRescanBtn, enabled = !running) { vm.rescanAssets() }
            }
            if (progress != null) ImportStatus(progress)

            SectionTitle(strings.assetsSectionCustomPath)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = path, onValueChange = { path = it }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                MenuButton(strings.commonUse) { vm.useCustomPath(path) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ImportStatus(p: ImportProgress) {
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val title = when (p.phase) {
            ImportProgress.Phase.SCANNING -> strings.importPhaseScanning
            ImportProgress.Phase.COPYING -> strings.importPhaseCopying(
                p.filesDone + 1,
                p.filesTotal,
                "%.2f".format(Locale.ROOT, p.bytesDone / 1e9),
                "%.2f".format(Locale.ROOT, p.bytesTotal / 1e9),
            )
            ImportProgress.Phase.DONE -> strings.importPhaseDone(p.filesTotal, "%.2f".format(Locale.ROOT, p.bytesTotal / 1e9))
            ImportProgress.Phase.FAILED -> strings.importPhaseFailed
            ImportProgress.Phase.CANCELLED -> strings.importPhaseCancelled
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
