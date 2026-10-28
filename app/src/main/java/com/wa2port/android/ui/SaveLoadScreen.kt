package com.wa2port.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.SaveStore
import com.wa2port.android.data.SlotMeta
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SaveLoadMode { SAVE, LOAD }

/** Full-screen Save/Load (title → Load). */
@Composable
fun SaveLoadScreen(mode: SaveLoadMode, store: SaveStore, lang: DisplayLang, onPick: suspend (Int) -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        SaveLoadPanel(mode, store, lang, onPick, onClose)
    }
}

/**
 * Grid of the quick slot + 24 slots with thumbnail, timestamp, scene and preview text. LOAD also
 * shows the autosave (`自动 AUTO`) first; SAVE never offers it (autosave is automatic).
 */
@Composable
fun SaveLoadPanel(mode: SaveLoadMode, store: SaveStore, lang: DisplayLang, onPick: suspend (Int) -> Unit, onClose: () -> Unit) {
    var metas by remember { mutableStateOf<Map<Int, SlotMeta>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmSlot by remember { mutableStateOf<Int?>(null) }
    var deleteConfirmSlot by remember { mutableStateOf<Int?>(null) }
    var lastSavedSlot by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { metas = store.listMeta() }

    fun pick(slot: Int) {
        scope.launch {
            onPick(slot)
            if (mode == SaveLoadMode.SAVE && !store.isLocked(slot)) lastSavedSlot = slot
            reload++
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (mode == SaveLoadMode.SAVE) "保存进度 Save  セーブ" else "读取进度 Load  ロード",
                color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(Color(0x66203050))
                    .border(1.dp, Wa2Colors.AccentDim, RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("返回游戏 Back", color = Wa2Colors.Text, fontSize = 13.sp)
            }
            CloseButton(onClose)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val slots = if (mode == SaveLoadMode.LOAD) SaveStore.CONTINUABLE_SLOTS else SaveStore.CONTINUABLE_SLOTS - SaveStore.AUTO_SLOT
            items(slots, key = { it }) { slot ->
                val meta = metas[slot]
                val locked = meta?.locked == true
                val enabled = if (mode == SaveLoadMode.SAVE) !locked else meta != null
                SlotCard(
                    slot = slot,
                    meta = meta,
                    lang = lang,
                    mode = mode,
                    justSaved = mode == SaveLoadMode.SAVE && slot == lastSavedSlot,
                    enabled = enabled,
                    onToggleLock = if (meta != null && slot >= 0) {
                        {
                            store.setLocked(slot, !locked)
                            reload++
                        }
                    } else null,
                    onDelete = if (meta != null && slot >= 0 && !locked) {
                        { deleteConfirmSlot = slot }
                    } else null,
                    onClick = {
                        if (mode == SaveLoadMode.SAVE && meta != null) confirmSlot = slot else pick(slot)
                    },
                )
            }
        }
    }
    confirmSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmSlot = null },
            confirmButton = { TextButton(onClick = { confirmSlot = null; pick(slot) }) { Text("覆盖 Overwrite") } },
            dismissButton = { TextButton(onClick = { confirmSlot = null }) { Text("取消 Cancel") } },
            text = { Text("覆盖存档 ${slotName(slot)}？ / 上書きしますか？") },
        )
    }
    deleteConfirmSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { deleteConfirmSlot = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = slot
                        deleteConfirmSlot = null
                        scope.launch {
                            store.delete(target)
                            if (lastSavedSlot == target) lastSavedSlot = null
                            reload++
                        }
                    },
                ) { Text("删除 Delete", color = Color(0xFFFF8A80)) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmSlot = null }) { Text("取消 Cancel") } },
            text = { Text("确定删除存档 ${slotName(slot)}？此操作不可撤销。\nセーブデータ ${slotName(slot)} を削除しますか？") },
        )
    }
}

private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

private fun slotName(slot: Int) = when (slot) {
    SaveStore.QUICK_SLOT -> "Q"
    SaveStore.AUTO_SLOT -> "AUTO"
    else -> "%02d".format(Locale.ROOT, slot)
}

/** Short relative age of a save for the title 「继续」 subtitle (e.g. `3 分钟前`), falling back to the date. */
fun relativeTimeText(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - timestamp).coerceAtLeast(0L)
    val min = diff / 60_000L
    return when {
        min < 1 -> "刚刚 just now"
        min < 60 -> "$min 分钟前"
        min < 24 * 60 -> "${min / 60} 小时前"
        min < 7 * 24 * 60 -> "${min / (24 * 60)} 天前"
        else -> dateFormat.format(Date(timestamp))
    }
}

/** Human label for a scene id (`1xxx` = IC, `2xxx` = CC, `3xxx` = coda). */
fun sceneLabel(scene: String): String = when (scene.firstOrNull()) {
    '1' -> "序章 IC · $scene"
    '2' -> "终章 CC · $scene"
    '3' -> "Coda · $scene"
    else -> scene
}

@Composable
private fun SlotCard(
    slot: Int,
    meta: SlotMeta?,
    lang: DisplayLang,
    mode: SaveLoadMode,
    justSaved: Boolean,
    enabled: Boolean,
    onToggleLock: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val locked = meta?.locked == true
    val borderWidth = if (justSaved) 2.dp else 1.dp
    val borderColor = when {
        justSaved -> Wa2Colors.Accent
        locked -> Color(0xFFFFCA64)
        slot == SaveStore.QUICK_SLOT || slot == SaveStore.AUTO_SLOT -> Wa2Colors.Accent
        else -> Wa2Colors.AccentDim
    }
    Column(
        Modifier
            .clip(shape)
            .background(if (justSaved) Color(0x77284570) else Color(0x55203050))
            .border(borderWidth, borderColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0E18))) {
            meta?.thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                when (slot) {
                    SaveStore.QUICK_SLOT -> "快速 QUICK"
                    SaveStore.AUTO_SLOT -> "自动 AUTO"
                    else -> "No.${slotName(slot)}"
                },
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (onToggleLock != null) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(Color(0xB3081020))
                            .clickable(onClick = onToggleLock)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (locked) "🔒" else "🔓", fontSize = 12.sp)
                    }
                    if (onDelete != null) {
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Color(0xB3281018))
                                .clickable(onClick = onDelete)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🗑", fontSize = 12.sp)
                        }
                    }
                }
            }
            if (locked && mode == SaveLoadMode.SAVE) {
                Text(
                    "🔒 已锁定 Locked",
                    color = Color(0xFFFFCA64), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xCC081020)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (justSaved) {
                Text(
                    "✓ 刚刚保存 Saved",
                    color = Wa2Colors.Accent, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xCC081020)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (meta == null) {
            Text("— 空 Empty —", color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(" ", fontSize = 11.sp)
        } else {
            val statusSuffix = if (locked) " · 🔒 已锁定" else ""
            Text("${dateFormat.format(Date(meta.timestamp))}  ${sceneLabel(meta.scene)}$statusSuffix", color = Wa2Colors.Accent, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            val preview = when (lang) {
                DisplayLang.JP -> meta.previewJp
                DisplayLang.CN -> meta.previewCn.ifBlank { meta.previewJp }
                DisplayLang.BOTH -> meta.previewCn.ifBlank { meta.previewJp }
            }.replace('\n', ' ')
            Text(preview, color = Wa2Colors.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
