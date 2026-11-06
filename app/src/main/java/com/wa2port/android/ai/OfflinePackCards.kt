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
import com.wa2port.android.ui.LocalCompact
import com.wa2port.android.ui.MenuButton
import com.wa2port.android.ui.Wa2Colors

private val PACK_GREEN = Color(0xFF8FD3A8)
private val PACK_RED = Color(0xFFFF8A80)
private val PACK_CARD = Color(0x33203050)

/** `已下载 X.X / Y.Y MB (ZZ%)` (total unknown → `已下载 X.X MB`). */
internal fun packProgressText(done: Long, total: Long): String =
    if (total > 0) {
        "已下载 ${AiPackController.mb(done)} / ${AiPackController.mb(total)} MB (${(done * 100 / total).coerceIn(0, 100)}%)"
    } else {
        "已下载 ${AiPackController.mb(done)} MB"
    }

/** `覆盖 N 幕 · M 句 · X.X MB`. */
internal fun packCoverageText(s: AiPackState.Installed): String =
    "覆盖 ${s.stats.sceneCount} 幕 · ${s.stats.occurrenceCount} 句 · ${AiPackController.mb(s.stats.fileBytes)} MB"

@Composable
private fun PackProgress(s: AiPackState.Downloading) {
    val f = s.fraction
    if (f != null) {
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = Wa2Colors.Accent, trackColor = Wa2Colors.AccentDim.copy(alpha = 0.3f))
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Wa2Colors.Accent, trackColor = Wa2Colors.AccentDim.copy(alpha = 0.3f))
    }
    Text(packProgressText(s.downloadedBytes, s.totalBytes), color = Wa2Colors.TextDim, fontSize = 12.sp)
}

/**
 * Top of 设置 → AI 学习: `📦 全剧本离线 AI 解读包 Offline AI Pack` — download / progress + cancel /
 * installed stats + update check + delete (in-layout two-step confirmation, no window dialog) /
 * error + resumable retry. Buttons wrap in a [FlowRow] so nothing clips at 360 dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflinePackSettingsCard(packs: AiPackController) {
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
        Text("📦 全剧本离线 AI 解读包 Offline AI Pack", color = Wa2Colors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        when (state) {
            is AiPackState.Installed -> {
                Text("✓ 已安装离线解读包", color = PACK_GREEN, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(packCoverageText(state), color = Wa2Colors.TextDim, fontSize = 12.sp)
                Text("无需 API Key：✨ AI 解析、点词、生词本与原声跟读均可离线使用。已配置 API Key 时 ↻ 可在线重新生成。", color = Wa2Colors.TextDim, fontSize = 12.sp)
                state.updateInfo?.let { u ->
                    Text(
                        "发现新版本数据包" + (if (u.sizeBytes > 0) "（${AiPackController.mb(u.sizeBytes)} MB）" else ""),
                        color = Wa2Colors.Accent, fontSize = 13.sp,
                    )
                }
                if (state.checked && state.updateInfo == null) Text("已是最新数据包 Up to date", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
            is AiPackState.Downloading -> {
                Text("正在下载离线解读包… Downloading", color = Wa2Colors.Accent, fontSize = 14.sp)
                PackProgress(state)
            }
            AiPackState.Checking -> Text("正在检查数据包… Checking…", color = Wa2Colors.TextDim, fontSize = 13.sp)
            is AiPackState.Error -> {
                Text("✗ ${state.message}", color = PACK_RED, fontSize = 13.sp)
                if (state.resumableBytes > 0) Text("已保留 ${AiPackController.mb(state.resumableBytes)} MB，重试将断点续传", color = Wa2Colors.TextDim, fontSize = 12.sp)
                state.previousInstalled?.let { Text("✓ 仍在使用已安装的离线包 · ${packCoverageText(it)}", color = PACK_GREEN, fontSize = 12.sp) }
            }
            AiPackState.NotInstalled, is AiPackState.Available -> {
                Text(
                    "一键下载全剧本 AI 逐句精讲数据包，无需配置 API Key 即可全程离线使用 ✨ AI 解析、点词、生词本与原声跟读",
                    color = Wa2Colors.TextDim, fontSize = 12.sp,
                )
                (state as? AiPackState.Available)?.info?.sizeBytes?.takeIf { it > 0 }?.let {
                    Text("数据包大小 ${AiPackController.mb(it)} MB", color = Wa2Colors.TextDim, fontSize = 12.sp)
                }
                val partial = packs.partialBytes
                if (partial > 0) Text("已下载 ${AiPackController.mb(partial)} MB，可继续下载", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is AiPackState.Installed -> {
                    if (state.updateInfo != null) MenuButton("⬇ 更新数据包 Update", compact = compact) { packs.download() }
                    MenuButton("检查数据包更新", compact = compact) { packs.checkUpdate() }
                    if (!confirmDelete) {
                        MenuButton("删除离线包", compact = compact) { confirmDelete = true }
                    } else {
                        MenuButton("确认删除 Delete", subtitle = "释放 ${AiPackController.mb(state.stats.fileBytes)} MB", compact = compact) {
                            confirmDelete = false
                            packs.delete()
                        }
                        MenuButton("取消 Cancel", compact = compact) { confirmDelete = false }
                    }
                }
                is AiPackState.Downloading -> MenuButton("取消 Cancel", compact = compact) { packs.cancel() }
                AiPackState.Checking -> MenuButton("取消 Cancel", compact = compact) { packs.cancel() }
                is AiPackState.Error -> MenuButton("重试下载 Retry", compact = compact) { packs.download() }
                AiPackState.NotInstalled, is AiPackState.Available -> MenuButton(
                    if (packs.partialBytes > 0) "⬇ 继续下载离线解读包" else "⬇ 一键下载离线解读包",
                    compact = compact,
                ) { packs.download() }
            }
        }
    }
}

/**
 * In-sheet offer shown with a NOT_CONFIGURED / NETWORK / TIMEOUT error while no pack is installed:
 * `⬇ 一键下载全剧本离线包（免 API Key）` with live progress right inside the AI breakdown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflinePackOfferCard(packs: AiPackController) {
    val state = packs.state
    if (state is AiPackState.Installed) return
    val compact = LocalCompact.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PACK_CARD).border(1.dp, Wa2Colors.Accent.copy(alpha = 0.6f), shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📦 离线 AI 解读包一键下载", color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("全剧本逐句 AI 精讲，下载后无需 API Key、无需联网即可直接查看这句的完整解析。", color = Wa2Colors.TextDim, fontSize = 12.sp)
        when (state) {
            is AiPackState.Downloading -> PackProgress(state)
            is AiPackState.Error -> {
                Text("✗ ${state.message}", color = PACK_RED, fontSize = 12.sp)
                if (state.resumableBytes > 0) Text("已保留 ${AiPackController.mb(state.resumableBytes)} MB，重试将断点续传", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }
            AiPackState.Checking -> Text("正在检查数据包… Checking…", color = Wa2Colors.TextDim, fontSize = 12.sp)
            else -> {}
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                is AiPackState.Downloading, AiPackState.Checking -> MenuButton("取消 Cancel", compact = compact) { packs.cancel() }
                is AiPackState.Error -> MenuButton("重试下载 Retry", compact = compact) { packs.download() }
                else -> MenuButton("⬇ 一键下载全剧本离线包（免 API Key）", compact = compact) { packs.download() }
            }
        }
    }
}
