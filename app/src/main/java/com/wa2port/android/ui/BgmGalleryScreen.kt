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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wa2port.android.AppViewModel
import com.wa2port.android.audio.AudioEngine
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.EngineEvent
import com.wa2port.core.engine.SystemData
import com.wa2port.core.gallery.BgmGallery
import com.wa2port.core.gallery.BgmPage
import com.wa2port.core.gallery.BgmTrackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Filter tabs for the 63-track BGM Gallery (`WA2.exe` `.data 0x4c1dd0`). */
enum class BgmFilter(val label: String, val page: BgmPage?) {
    ALL("全部 All (63)", null),
    PAGE_1("Page 1 · IC (31)", BgmPage.PAGE_1),
    PAGE_2("Page 2 · CC/Coda (32)", BgmPage.PAGE_2),
}

/**
 * Full-screen BGM Gallery (`音乐鉴赏 BGM Gallery` / `音楽モード`) wired to [AppViewModel].
 *
 * Displays all 63 tracks from `WA2.exe` `.data 0x4c1dd0` in exact display order with title and
 * composer/singer credits, `BGM_%03d` and `Intro+Loop (_A+_B)` vs `Single` badges, page filter tabs,
 * `解锁全部 Show All` toggle, and transport controls (`⏮`, `▶/⏸`, `⏹`, `⏭`) backed by [AudioEngine].
 */
@Composable
fun BgmGalleryScreen(
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

    val context = LocalContext.current
    val assets = remember(root) { Wa2Assets(root) }
    val audio = remember(assets) {
        AudioEngine(context, assets).also {
            it.setVolumes(vm.settings.bgmVolume / 100f, vm.settings.seVolume / 100f, vm.settings.voiceVolume / 100f)
        }
    }
    val system = remember(root) {
        vm.currentSystemData().also { BgmGallery.applyAutoUnlocks(it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, audio) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> audio.pause()
                Lifecycle.Event.ON_RESUME -> audio.resume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            audio.release()
            assets.close()
        }
    }

    BackHandler(onBack = onClose)

    BgmGalleryContent(
        assets = assets,
        system = system,
        audio = audio,
        onClose = onClose,
        showAll = vm.settings.galleryShowAll,
        onShowAllChange = { on -> vm.updateSettings { it.copy(galleryShowAll = on) } },
    )
}

/** [showAll] = `解锁全部 Show All` (persisted as `Settings.galleryShowAll`, off by default). */
@Composable
private fun BgmGalleryContent(
    assets: Wa2Assets,
    system: SystemData,
    audio: AudioEngine,
    onClose: () -> Unit,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(BgmFilter.ALL) }
    var currentSlot by rememberSaveable { mutableIntStateOf(0) }
    var isPlaying by rememberSaveable { mutableStateOf(false) }
    var isPaused by rememberSaveable { mutableStateOf(false) }

    // Precompute which tracks have _A + _B intro+loop pairs on IO
    var introLoopSlots by remember(assets) { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(assets) {
        introLoopSlots = withContext(Dispatchers.IO) {
            BgmGallery.tracks.filter { BgmGallery.resolvePlayEvent(assets, it, system)?.intro != null }
                .mapTo(HashSet()) { it.slotNumber }
        }
    }

    val allTracks = BgmGallery.tracks
    val visibleTracks = remember(filter) { BgmGallery.filter(filter.page) }
    val unlockedCount = remember(system) { allTracks.count { it.isUnlocked(system) } }

    fun playTrack(entry: BgmTrackEntry) {
        if (!showAll && !entry.isUnlocked(system)) return
        val ev = BgmGallery.resolvePlayEvent(assets, entry, system) ?: return
        currentSlot = entry.slotNumber
        isPlaying = true
        isPaused = false
        audio.handle(ev)
    }

    fun togglePlayPause() {
        val cur = allTracks.firstOrNull { it.slotNumber == currentSlot }
        if (cur == null) {
            val first = visibleTracks.firstOrNull { showAll || it.isUnlocked(system) } ?: return
            playTrack(first)
            return
        }
        if (isPlaying && !isPaused) {
            audio.pause()
            isPaused = true
        } else if (isPlaying && isPaused) {
            audio.resume()
            isPaused = false
        } else {
            playTrack(cur)
        }
    }

    fun stopPlayback() {
        audio.handle(EngineEvent.StopBgm(0))
        isPlaying = false
        isPaused = false
    }

    fun stepTrack(delta: Int) {
        val playable = visibleTracks.filter { showAll || it.isUnlocked(system) }
        if (playable.isEmpty()) return
        val idx = playable.indexOfFirst { it.slotNumber == currentSlot }
        val next = if (idx < 0) playable.first() else playable[(idx + delta + playable.size) % playable.size]
        playTrack(next)
    }

    val activeEntry = allTracks.firstOrNull { it.slotNumber == currentSlot }

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
                    "音乐鉴赏  BGM Gallery  ·  音楽モード",
                    color = Wa2Colors.Text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "已解锁 $unlockedCount / ${allTracks.size} 曲  ·  当前列表 ${visibleTracks.size} 曲",
                    color = Wa2Colors.TextDim,
                    fontSize = 12.sp,
                )
            }
            BgmFilter.entries.forEach { tab ->
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

        // ---- Track list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visibleTracks, key = { it.slotNumber }) { entry ->
                val unlocked = showAll || entry.isUnlocked(system)
                val active = entry.slotNumber == currentSlot && isPlaying
                val hasIntro = entry.slotNumber in introLoopSlots
                val shape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            when {
                                active -> Wa2Colors.ButtonActive
                                unlocked -> Color(0xCC101B30)
                                else -> Color(0x660B1220)
                            }
                        )
                        .border(
                            BorderStroke(
                                width = if (active) 1.5.dp else 1.dp,
                                color = if (active) Wa2Colors.Accent else Wa2Colors.Border,
                            ),
                            shape,
                        )
                        .clickable(enabled = unlocked) { playTrack(entry) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Slot number / playing indicator
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) Wa2Colors.Accent.copy(alpha = 0.22f) else Color(0x44000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (active) (if (isPaused) "⏸" else "♪") else "#%02d".format(Locale.ROOT, entry.slotNumber),
                            color = if (active) Wa2Colors.Accent else Wa2Colors.TextDim,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Track title & credits
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (unlocked) entry.titleJp else "？？？（未解锁 Locked）",
                            color = if (unlocked) Wa2Colors.Text else Wa2Colors.TextDim,
                            fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (unlocked) entry.creditsJp else "在游戏正篇中播放该曲目后自动解锁",
                            color = Wa2Colors.TextDim,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // BGM ID & A+B loop badge
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.badgeLabel(system),
                            color = if (active) Wa2Colors.Accent else Wa2Colors.TextDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (hasIntro) "A→B 无缝循环" else "单曲循环 Loop",
                            color = Wa2Colors.TextDim.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Bottom Transport Bar
        val barShape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(barShape)
                .background(Wa2Colors.Panel)
                .border(BorderStroke(1.dp, Wa2Colors.Border), barShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (activeEntry != null && isPlaying) {
                        val stateTag = if (isPaused) "已暂停 Paused" else "正在播放 Now Playing"
                        "$stateTag  ·  #%02d  %s".format(Locale.ROOT, activeEntry.slotNumber, activeEntry.titleJp)
                    } else {
                        "未播放 Stopped  ·  点击任意曲目开始欣赏 Tap any track to play"
                    },
                    color = if (activeEntry != null && isPlaying) Wa2Colors.Accent else Wa2Colors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    activeEntry?.creditsJp ?: "支持原版 BGM_nnn_A 前奏 → BGM_nnn_B 无缝循环播放",
                    color = Wa2Colors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            TransportPill("⏮ 上一曲", onClick = { stepTrack(-1) })
            TransportPill(
                label = when {
                    !isPlaying -> "▶ 播放 Play"
                    isPaused -> "▶ 继续 Resume"
                    else -> "⏸ 暂停 Pause"
                },
                highlighted = isPlaying && !isPaused,
                onClick = ::togglePlayPause,
            )
            TransportPill("⏹ 停止 Stop", onClick = ::stopPlayback)
            TransportPill("⏭ 下一曲", onClick = { stepTrack(+1) })
        }
    }
}

@Composable
private fun TransportPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (highlighted) Wa2Colors.ButtonActive else Wa2Colors.Button)
            .border(BorderStroke(1.dp, if (highlighted) Wa2Colors.Accent else Wa2Colors.Border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) Wa2Colors.Accent else Wa2Colors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
