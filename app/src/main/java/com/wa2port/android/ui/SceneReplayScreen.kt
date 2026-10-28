package com.wa2port.android.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.AppViewModel
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.render.AssetImageCache
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.SystemData
import com.wa2port.core.gallery.ReplayCategory
import com.wa2port.core.gallery.SceneReplayEntry
import com.wa2port.core.gallery.SceneReplayGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val REPLAY_CACHE_BUDGET_BYTES = 48 * 1024 * 1024

/** Filter tabs for the 24-slot Scene Replay screen (`WA2.exe` `.data 0x4c1bf0`). */
enum class ReplayFilter(val label: String, val category: ReplayCategory?) {
    ALL("全部 All (24)", null),
    IC("IC 序章 (1)", ReplayCategory.IC),
    CC("CC 终章 (13)", ReplayCategory.CC),
    CODA("Coda (10)", ReplayCategory.CODA),
}

/**
 * Full-screen Scene Replay (`场景回想 Scene Replay` / `回想モード`) wired to [AppViewModel].
 *
 * Renders the 24 replay cards from `WA2.exe` `.data 0x4c1bf0` using pre-baked `tv%06d.tga`
 * (`272×160`) thumbnails from `grp.pak` / `IC/grp.pak`, with chapter filters, `解锁全部 Show All`
 * toggle, bilingual heroine & scene titles (`CN` + `JP`), and launches `9999.bnr` (`GetReplayMode` = `1..24`)
 * via [AppViewModel.startReplay].
 */
@Composable
fun SceneReplayScreen(
    vm: AppViewModel,
    onClose: () -> Unit = { vm.back() },
) {
    val root = vm.assetRoot
    if (root == null) {
        Box(Modifier.fillMaxSize().background(Wa2Colors.Night), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("未找到游戏资源 Assets not found", color = Wa2Colors.Text, fontSize = 16.sp)
                MenuButton("返回 Back", onClick = onClose)
            }
        }
        return
    }

    val assets = remember(root) { Wa2Assets(root) }
    val imageCache = remember(assets) { AssetImageCache(assets, budgetBytes = REPLAY_CACHE_BUDGET_BYTES) }
    val system = remember(root) { vm.currentSystemData() }

    DisposableEffect(assets, imageCache) {
        onDispose {
            imageCache.close()
            assets.close()
        }
    }

    BackHandler(onBack = onClose)

    SceneReplayContent(
        assets = assets,
        system = system,
        imageCache = imageCache,
        lang = vm.settings.lang,
        onLaunchReplay = { entry -> vm.startReplay(entry.slotNumber, entry.scriptName) },
        onClose = onClose,
        showAll = vm.settings.galleryShowAll,
        onShowAllChange = { on -> vm.updateSettings { it.copy(galleryShowAll = on) } },
    )
}

/** [showAll] = `解锁全部 Show All` (persisted as `Settings.galleryShowAll`, off by default). */
@Composable
private fun SceneReplayContent(
    assets: Wa2Assets,
    system: SystemData,
    imageCache: AssetImageCache,
    lang: DisplayLang,
    onLaunchReplay: (SceneReplayEntry) -> Unit,
    onClose: () -> Unit,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ReplayFilter.ALL) }

    val thumbCache = remember(assets) {
        object : LruCache<String, ImageBitmap>(64) {}
    }

    val allEntries = SceneReplayGallery.entries
    val visibleEntries = remember(filter) { SceneReplayGallery.filter(filter.category) }
    val unlockedCount = remember(system) { allEntries.count { it.isUnlocked(system) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Wa2Colors.Night)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        // ---- Top bar
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "场景回想  Scene Replay  ·  回想モード",
                    color = Wa2Colors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "已解锁 $unlockedCount / ${allEntries.size} 幕  ·  当前分类 ${visibleEntries.size} 幕",
                    color = Wa2Colors.TextDim,
                    fontSize = 12.sp,
                )
            }
            ReplayFilter.entries.forEach { tab ->
                val active = filter == tab
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .heightIn(min = 38.dp)
                        .clip(shape)
                        .background(if (active) Wa2Colors.ButtonActive else Wa2Colors.Button)
                        .border(BorderStroke(1.dp, if (active) Wa2Colors.Accent else Wa2Colors.Border), shape)
                        .clickable { filter = tab }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) Wa2Colors.Accent else Wa2Colors.Text,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            val toggleShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .heightIn(min = 38.dp)
                    .clip(toggleShape)
                    .background(if (showAll) Wa2Colors.ButtonActive else Wa2Colors.Button)
                    .border(BorderStroke(1.dp, if (showAll) Wa2Colors.Accent else Wa2Colors.Border), toggleShape)
                    .clickable { onShowAllChange(!showAll) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (showAll) "解锁全部 Show All: ON" else "解锁全部 Show All: OFF",
                    color = if (showAll) Wa2Colors.Accent else Wa2Colors.TextDim,
                    fontSize = 12.sp,
                )
            }
            CloseButton(onClose)
        }

        Spacer(Modifier.height(10.dp))

        // ---- 3-column Replay Card Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visibleEntries, key = { it.slotNumber }) { entry ->
                val unlocked = showAll || entry.isUnlocked(system)
                ReplayCard(
                    entry = entry,
                    unlocked = unlocked,
                    lang = lang,
                    assets = assets,
                    imageCache = imageCache,
                    thumbCache = thumbCache,
                    onClick = { if (unlocked) onLaunchReplay(entry) },
                )
            }
        }
    }
}

@Composable
private fun ReplayCard(
    entry: SceneReplayEntry,
    unlocked: Boolean,
    lang: DisplayLang,
    assets: Wa2Assets,
    imageCache: AssetImageCache,
    thumbCache: LruCache<String, ImageBitmap>,
    onClick: () -> Unit,
) {
    val file = remember(entry) { entry.resolveThumbnail(assets) }
    var thumb by remember(file, unlocked) {
        mutableStateOf(if (unlocked && file != null) thumbCache.get(file) else null)
    }

    LaunchedEffect(file, unlocked) {
        if (!unlocked || file == null || thumb != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val raw: Bitmap = imageCache.get("grp", file) ?: return@withContext null
            raw.asImageBitmap()
        }
        if (loaded != null) {
            thumbCache.put(file, loaded)
            thumb = loaded
        }
    }

    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (unlocked) Color(0xDD101B30) else Color(0x660B1220))
            .border(BorderStroke(1.dp, if (unlocked) Wa2Colors.Border else Color(0x33405580)), shape)
            .clickable(enabled = unlocked, onClick = onClick),
    ) {
        // 16:9 / 17:10 thumbnail box
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF070C18)),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumb
            if (unlocked && image != null) {
                Image(
                    bitmap = image,
                    contentDescription = entry.titleCn,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (!unlocked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOCKED", color = Wa2Colors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("未解锁", color = Wa2Colors.TextDim.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            } else {
                Text("Loading…", color = Wa2Colors.TextDim, fontSize = 12.sp)
            }

            // Top-left slot + category badge
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xBB060B16), RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    "#%02d · %s".format(Locale.ROOT, entry.slotNumber, entry.category.name),
                    color = Wa2Colors.Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Top-right heroine badge
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0xBB10203B), RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    if (lang == DisplayLang.JP) entry.heroineJp else entry.heroineCn,
                    color = Wa2Colors.Name,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Title caption area
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!unlocked) {
                Text(
                    "？？？（未解锁场景）",
                    color = Wa2Colors.TextDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                Text(
                    "Script ${entry.scriptName} · Flag ${entry.gflagId}",
                    color = Wa2Colors.TextDim.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            } else {
                when (lang) {
                    DisplayLang.CN -> {
                        Text(entry.titleCn, color = Wa2Colors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.heroineCn, color = Wa2Colors.TextDim, fontSize = 11.sp, maxLines = 1)
                    }
                    DisplayLang.JP -> {
                        Text(entry.titleJp, color = Wa2Colors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.heroineJp, color = Wa2Colors.TextDim, fontSize = 11.sp, maxLines = 1)
                    }
                    DisplayLang.BOTH -> {
                        Text(entry.titleCn, color = Wa2Colors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.titleJp, color = Wa2Colors.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
