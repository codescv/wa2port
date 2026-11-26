package com.wa2port.android.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.ui.LocalAppStrings
import com.wa2port.android.ui.LocalCompact
import com.wa2port.android.ui.MenuButton
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.core.i18n.Wa2I18n
import com.wa2port.core.i18n.Wa2Strings

private val PACK_GREEN = Color(0xFF8FD3A8)
private val PACK_RED = Color(0xFFFF8A80)
private val PACK_CARD = Color(0x33203050)

internal fun packProgressText(done: Long, total: Long, strings: Wa2Strings = Wa2I18n.s): String =
    if (total > 0) {
        strings.packProgressKnown(AiPackController.mb(done), AiPackController.mb(total), (done * 100 / total).coerceIn(0, 100))
    } else {
        strings.packProgressUnknown(AiPackController.mb(done))
    }

internal fun packCoverageText(s: AiPackState.Installed, strings: Wa2Strings = Wa2I18n.s): String =
    strings.packCoverage(s.stats.sceneCount, s.stats.occurrenceCount, AiPackController.mb(s.stats.fileBytes))

@Composable
private fun PackProgress(s: AiPackState.Downloading) {
    val strings = LocalAppStrings.current
    val f = s.fraction
    if (f != null) {
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = Wa2Colors.Accent, trackColor = Wa2Colors.AccentDim.copy(alpha = 0.3f))
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Wa2Colors.Accent, trackColor = Wa2Colors.AccentDim.copy(alpha = 0.3f))
    }
    Text(packProgressText(s.downloadedBytes, s.totalBytes, strings), color = Wa2Colors.TextDim, fontSize = 12.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflinePackSettingsCard(packs: AiPackController) {
    val strings = LocalAppStrings.current
    val compact = LocalCompact.current
    val state = packs.state
    var confirmDelete by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(PACK_CARD)
            .border(1.dp, if (state is AiPackState.Installed) PACK_GREEN.copy(alpha = 0.6f) else Wa2Colors.AccentDim, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.packSettingsTitle, color = Wa2Colors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        when (state) {
            is AiPackState.Installed -> {
                Text(strings.packInstalled, color = PACK_GREEN, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(packCoverageText(state, strings), color = Wa2Colors.TextDim, fontSize = 12.sp)
                Text(strings.packInstalledHint, color = Wa2Colors.TextDim, fontSize = 12.sp)
                state.updateInfo?.let { u ->
                    Text(
                        if (u.sizeBytes > 0) strings.packUpdateAvailableSize(AiPackController.mb(u.sizeBytes)) else strings.packUpdateAvailable,
                        color = Wa2Colors.Accent, fontSize = 13.sp,
                    )
                }
                if (state.checked && state.updateInfo == null) Text(strings.packUpToDate, color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
            is AiPackState.Downloading -> {
                Text(strings.packDownloading, color = Wa2Colors.Accent, fontSize = 14.sp)
                PackProgress(state)
            }
            AiPackState.Checking -> Text(strings.packChecking, color = Wa2Colors.TextDim, fontSize = 13.sp)
            is AiPackState.Error -> {
                Text("✗ ${state.message}", color = PACK_RED, fontSize = 13.sp)
                if (state.resumableBytes > 0) Text(strings.packResumableHint(AiPackController.mb(state.resumableBytes)), color = Wa2Colors.TextDim, fontSize = 12.sp)
                state.previousInstalled?.let { Text(strings.packStillUsingInstalled(packCoverageText(it, strings)), color = PACK_GREEN, fontSize = 12.sp) }
            }
            AiPackState.NotInstalled, is AiPackState.Available -> {
                Text(
                    strings.packNotInstalledDesc,
                    color = Wa2Colors.TextDim, fontSize = 12.sp,
                )
                (state as? AiPackState.Available)?.info?.sizeBytes?.takeIf { it > 0 }?.let {
                    Text(strings.packSizeLabel(AiPackController.mb(it)), color = Wa2Colors.TextDim, fontSize = 12.sp)
                }
                val partial = packs.partialBytes
                if (partial > 0) Text(strings.packPartialResumeHint(AiPackController.mb(partial)), color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is AiPackState.Installed -> {
                    if (state.updateInfo != null) MenuButton(strings.packUpdateBtn, compact = compact) { packs.download() }
                    MenuButton(strings.packCheckUpdateBtn, compact = compact) { packs.checkUpdate() }
                    if (!confirmDelete) {
                        MenuButton(strings.packDeleteBtn, compact = compact) { confirmDelete = true }
                    } else {
                        MenuButton(strings.packConfirmDeleteBtn, subtitle = strings.packFreeSpaceSub(AiPackController.mb(state.stats.fileBytes)), compact = compact) {
                            confirmDelete = false
                            packs.delete()
                        }
                        MenuButton(strings.commonCancel, compact = compact) { confirmDelete = false }
                    }
                }
                is AiPackState.Downloading -> MenuButton(strings.commonCancel, compact = compact) { packs.cancel() }
                AiPackState.Checking -> MenuButton(strings.commonCancel, compact = compact) { packs.cancel() }
                is AiPackState.Error -> MenuButton(strings.packRetryBtn, compact = compact) { packs.download() }
                AiPackState.NotInstalled, is AiPackState.Available -> MenuButton(
                    if (packs.partialBytes > 0) strings.packResumeDownloadBtn else strings.packDownloadBtn,
                    compact = compact,
                ) { packs.download() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflinePackOfferCard(packs: AiPackController) {
    val strings = LocalAppStrings.current
    val state = packs.state
    if (state is AiPackState.Installed) return
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PACK_CARD).border(1.dp, Wa2Colors.Accent.copy(alpha = 0.6f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.packOfferTitle, color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(strings.packOfferDesc, color = Wa2Colors.TextDim, fontSize = 12.sp)
        when (state) {
            is AiPackState.Downloading -> PackProgress(state)
            is AiPackState.Error -> {
                Text("✗ ${state.message}", color = PACK_RED, fontSize = 12.sp)
                if (state.resumableBytes > 0) Text(strings.packResumableHint(AiPackController.mb(state.resumableBytes)), color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
            AiPackState.Checking -> Text(strings.packChecking, color = Wa2Colors.TextDim, fontSize = 12.sp)
            else -> {}
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is AiPackState.Downloading, AiPackState.Checking -> MenuButton(strings.commonCancel, compact = compact) { packs.cancel() }
                is AiPackState.Error -> MenuButton(strings.packRetryBtn, compact = compact) { packs.download() }
                else -> MenuButton(strings.packOfferBtn, compact = compact) { packs.download() }
            }
        }
    }
}
