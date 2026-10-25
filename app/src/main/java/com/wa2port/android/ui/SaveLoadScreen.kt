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

/** Grid of the quick slot + 24 slots with thumbnail, timestamp, scene and preview text. */
@Composable
fun SaveLoadPanel(mode: SaveLoadMode, store: SaveStore, lang: DisplayLang, onPick: suspend (Int) -> Unit, onClose: () -> Unit) {
    var metas by remember { mutableStateOf<Map<Int, SlotMeta>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmSlot by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { metas = store.listMeta() }

    fun pick(slot: Int) {
        scope.launch {
            onPick(slot)
            reload++
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (mode == SaveLoadMode.SAVE) "保存进度 Save  セーブ" else "读取进度 Load  ロード",
                color = Wa2Colors.Text, fontSize = 22.sp, modifier = Modifier.weight(1f),
            )
            CloseButton(onClose)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(listOf(SaveStore.QUICK_SLOT) + (1..SaveStore.SLOT_COUNT).toList(), key = { it }) { slot ->
                val meta = metas[slot]
                SlotCard(slot, meta, lang, enabled = mode == SaveLoadMode.SAVE || meta != null) {
                    if (mode == SaveLoadMode.SAVE && meta != null) confirmSlot = slot else pick(slot)
                }
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
}

private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

private fun slotName(slot: Int) = if (slot == SaveStore.QUICK_SLOT) "Q" else "%02d".format(slot)

/** Human label for a scene id (`1xxx` = IC, `2xxx` = CC, `3xxx` = coda). */
fun sceneLabel(scene: String): String = when (scene.firstOrNull()) {
    '1' -> "序章 IC · $scene"
    '2' -> "终章 CC · $scene"
    '3' -> "Coda · $scene"
    else -> scene
}

@Composable
private fun SlotCard(slot: Int, meta: SlotMeta?, lang: DisplayLang, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0x55203050))
            .border(1.dp, if (slot == SaveStore.QUICK_SLOT) Wa2Colors.Accent else Wa2Colors.AccentDim, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0E18))) {
            meta?.thumbnail?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                if (slot == SaveStore.QUICK_SLOT) "QUICK" else "No.${slotName(slot)}",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (meta == null) {
            Text("— 空 Empty —", color = Wa2Colors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(" ", fontSize = 11.sp)
        } else {
            Text("${dateFormat.format(Date(meta.timestamp))}  ${sceneLabel(meta.scene)}", color = Wa2Colors.Accent, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            val preview = when (lang) {
                DisplayLang.JP -> meta.previewJp
                DisplayLang.CN -> meta.previewCn.ifBlank { meta.previewJp }
                DisplayLang.BOTH -> meta.previewCn.ifBlank { meta.previewJp }
            }.replace('\n', ' ')
            Text(preview, color = Wa2Colors.Text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
