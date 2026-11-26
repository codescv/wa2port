package com.wa2port.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.data.SaveStore
import com.wa2port.android.sync.ConflictChoice
import com.wa2port.android.sync.ConflictSideInfo
import com.wa2port.android.sync.SyncConflict
import com.wa2port.core.sync.CloudSyncSchema
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CONFLICT_WARN = Color(0xFFFFD9A0)
private val CONFLICT_LOCK = Color(0xFFFFCA64)
private val CONFLICT_CLOUD_ACCENT = Color(0xFF7FD1FF)
private val CONFLICT_LOCAL_ACCENT = Color(0xFF9FC6FF)

/**
 * In-layout Compose overlay for interactive two-choice cloud sync conflict resolution (弹窗二选一).
 *
 * Rendered directly inside the root Box (NOT a system `AlertDialog` window) so immersive system
 * bars never flash when a conflict appears during gameplay or from settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudSyncConflictOverlay(
    conflicts: List<SyncConflict>,
    isResolving: Boolean,
    error: String?,
    onChoose: (SyncConflict, ConflictChoice) -> Unit,
    onChooseAll: (ConflictChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val conflict = conflicts.firstOrNull() ?: return
    val total = conflicts.size

    BackHandler(enabled = !isResolving) { onDismiss() }

    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xBB000000))
            .clickable(interactionSource = scrimInteraction, indication = null) {
                // Keep modal until user explicitly picks an option or taps 稍后处理.
            }
            .screenSafePadding(),
        contentAlignment = Alignment.Center,
    ) {
        val sideBySide = !LocalIsPortrait.current && maxWidth >= 560.dp
        val shape = RoundedCornerShape(14.dp)

        Column(
            Modifier
                .padding(horizontal = if (LocalCompact.current) 12.dp else 24.dp, vertical = 14.dp)
                .widthIn(max = if (sideBySide) 760.dp else 540.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Wa2Colors.Panel)
                .border(BorderStroke(1.dp, Wa2Colors.Accent), shape)
                .clickable(interactionSource = cardInteraction, indication = null) {}
                .padding(horizontal = if (LocalCompact.current) 14.dp else 20.dp, vertical = 14.dp),
        ) {
            // Header row: title + slot label + progress (1 / N) + close.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            strings.syncConflictTitle,
                            color = Wa2Colors.Text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x66203A60))
                                .border(1.dp, Wa2Colors.AccentDim, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(CloudSyncSchema.displayLabelForPath(conflict.relativePath, strings), color = Wa2Colors.Accent, fontSize = 12.sp)
                        }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x44304060))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text("1 / $total", color = Wa2Colors.TextDim, fontSize = 12.sp)
                        }
                    }
                    Text(
                        conflict.reason.localizedLabel(strings),
                        color = CONFLICT_WARN,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                CloseButton { if (!isResolving) onDismiss() }
            }

            error?.let { msg ->
                Text(
                    "✗ $msg",
                    color = Color(0xFFFFB0A0),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // Scrollable comparison area (side-by-side in landscape, stacked in portrait).
            val localNewer = conflict.local.exists && conflict.remote.exists &&
                conflict.local.timestampReliable && conflict.remote.timestampReliable &&
                conflict.local.timestampMs > conflict.remote.timestampMs
            val remoteNewer = conflict.local.exists && conflict.remote.exists &&
                conflict.local.timestampReliable && conflict.remote.timestampReliable &&
                conflict.remote.timestampMs > conflict.local.timestampMs

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (sideBySide) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConflictSideCard(
                            title = strings.syncLocalSaveTitle,
                            side = conflict.local,
                            accent = CONFLICT_LOCAL_ACCENT,
                            isNewer = localNewer,
                            modifier = Modifier.weight(1f),
                        )
                        ConflictSideCard(
                            title = strings.syncCloudSaveTitle,
                            side = conflict.remote,
                            accent = CONFLICT_CLOUD_ACCENT,
                            isNewer = remoteNewer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ConflictSideCard(
                            title = strings.syncLocalSaveTitle,
                            side = conflict.local,
                            accent = CONFLICT_LOCAL_ACCENT,
                            isNewer = localNewer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ConflictSideCard(
                            title = strings.syncCloudSaveTitle,
                            side = conflict.remote,
                            accent = CONFLICT_CLOUD_ACCENT,
                            isNewer = remoteNewer,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isResolving) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Wa2Colors.Accent,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(strings.syncResolving, color = Wa2Colors.Text, fontSize = 13.sp)
                }
            } else {
                // Primary two-choice buttons.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ConflictActionButton(
                        label = strings.syncKeepLocal,
                        subtitle = if (conflict.local.exists) strings.syncKeepLocalUpload else strings.syncKeepLocalDeleteCloud,
                        highlight = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onChoose(conflict, ConflictChoice.KEEP_LOCAL) },
                    )
                    ConflictActionButton(
                        label = strings.syncUseCloud,
                        subtitle = if (conflict.remote.exists) strings.syncUseCloudDownload else strings.syncUseCloudDeleteLocal,
                        highlight = true,
                        modifier = Modifier.weight(1f),
                        onClick = { onChoose(conflict, ConflictChoice.USE_CLOUD) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Secondary controls: Handle later + batch resolve when N > 1.
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (total > 1) {
                        ConflictSecondaryButton(strings.syncKeepAllLocal(total)) {
                            onChooseAll(ConflictChoice.KEEP_LOCAL)
                        }
                        ConflictSecondaryButton(strings.syncUseAllCloud(total)) {
                            onChooseAll(ConflictChoice.USE_CLOUD)
                        }
                    }
                    ConflictSecondaryButton(strings.syncHandleLater) { onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun ConflictSideCard(
    title: String,
    side: ConflictSideInfo,
    accent: Color,
    isNewer: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val shape = RoundedCornerShape(10.dp)
    val thumb = remember(side.thumbnailBase64) { SaveStore.decodeThumbnail(side.thumbnailBase64) }
    val borderColor = when {
        side.isLocked -> CONFLICT_LOCK
        isNewer -> accent
        else -> Wa2Colors.AccentDim
    }

    Column(
        modifier
            .clip(shape)
            .background(Color(0x6610182C))
            .border(BorderStroke(if (isNewer || side.isLocked) 1.5.dp else 1.dp, borderColor), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (side.isLocked) {
                    Text(
                        strings.saveTagLocked,
                        color = CONFLICT_LOCK,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x55302810))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (isNewer) {
                    Text(
                        strings.syncNewerTag,
                        color = Color(0xFF8FD3A8),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x44103824))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }

        if (!side.exists) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x44080C16)),
                contentAlignment = Alignment.Center,
            ) {
                Text(strings.syncDeletedOrEmpty, color = Wa2Colors.TextDim, fontSize = 13.sp)
            }
            return
        }

        if (thumb != null) {
            Image(
                bitmap = thumb,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0A0E18)),
                contentScale = ContentScale.Crop,
            )
        }

        val timeLine = if (side.timestampMs > 0L) {
            val formatted = conflictDateFormat.format(Date(side.timestampMs))
            if (side.timestampReliable) formatted else strings.syncUnreliableTs(formatted)
        } else {
            strings.syncNoTimestamp
        }
        Text(timeLine, color = Wa2Colors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)

        if (side.scene.isNotBlank()) {
            Text(sceneLabel(side.scene, strings), color = Wa2Colors.Accent, fontSize = 12.sp)
        }

        val cn = side.previewCn.replace('\n', ' ').trim()
        val jp = side.previewJp.replace('\n', ' ').trim()
        if (cn.isNotBlank()) {
            Text(
                cn,
                color = Wa2Colors.Text,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (jp.isNotBlank() && jp != cn) {
            Text(
                jp,
                color = Wa2Colors.TextDim,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConflictActionButton(
    label: String,
    subtitle: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (highlight) Color(0x99203A60) else Wa2Colors.Button)
            .border(BorderStroke(1.dp, if (highlight) Wa2Colors.Accent else Wa2Colors.AccentDim), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Wa2Colors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Wa2Colors.TextDim, fontSize = 11.sp)
    }
}

@Composable
private fun ConflictSecondaryButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(Color(0x55203050))
            .border(BorderStroke(1.dp, Wa2Colors.AccentDim), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Wa2Colors.Text, fontSize = 12.sp)
    }
}

private val conflictDateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
