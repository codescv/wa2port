package com.wa2port.android.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wa2port.android.AppViewModel
import com.wa2port.android.render.AssetImageCache
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.SystemData
import com.wa2port.core.gallery.CgCategory
import com.wa2port.core.gallery.CgGallery
import com.wa2port.core.gallery.CgGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val THUMB_WIDTH = 320
private const val THUMB_HEIGHT = 180
private const val GALLERY_CACHE_BUDGET_BYTES = 96 * 1024 * 1024

/** Filter tabs shown at the top of the CG Gallery screen. */
enum class CgFilter(val label: String, val category: CgCategory?) {
    ALL("全部 All", null),
    IC("IC 序章", CgCategory.IC),
    CC("CC 终章·Coda", CgCategory.CC),
}

/**
 * Full-screen CG Gallery (`CG 鉴赏`) wired to [AppViewModel].
 *
 * Indexes the `WA2.exe` card table (`149` cards / `272` variants in `grp.pak` + `IC/grp.pak`) on
 * [Dispatchers.IO], displays a filterable grid of group cards with downscaled ~320×180 thumbnails
 * decoded via [AssetImageCache], and opens [CgViewer] when an unlocked card is tapped.
 */
@Composable
fun CgGalleryScreen(
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
    val imageCache = remember(assets) { AssetImageCache(assets, budgetBytes = GALLERY_CACHE_BUDGET_BYTES) }
    val system = remember(root) { vm.currentSystemData() }

    DisposableEffect(assets, imageCache) {
        onDispose {
            imageCache.close()
            assets.close()
        }
    }

    CgGalleryScreen(
        assets = assets,
        system = system,
        imageCache = imageCache,
        onClose = onClose,
    )
}

/**
 * Stateless/injectable overload of [CgGalleryScreen] taking [Wa2Assets], [SystemData], and [AssetImageCache].
 */
@Composable
fun CgGalleryScreen(
    assets: Wa2Assets,
    system: SystemData,
    imageCache: AssetImageCache,
    onClose: () -> Unit,
) {
    var gallery by remember(assets) { mutableStateOf<CgGallery?>(null) }
    var filter by rememberSaveable { mutableStateOf(CgFilter.ALL) }
    var showAll by rememberSaveable { mutableStateOf(true) }
    var viewerGroupId by rememberSaveable { mutableStateOf<String?>(null) }

    // Cache of downscaled 320x180 ImageBitmaps so scrolling the grid stays fast even after full-res eviction.
    val thumbCache = remember(assets) {
        object : LruCache<String, ImageBitmap>(256) {}
    }

    LaunchedEffect(assets) {
        gallery = withContext(Dispatchers.IO) { CgGallery.fromAssets(assets) }
    }

    val loaded = gallery
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Wa2Colors.Night), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Wa2Colors.Accent)
        }
        return
    }

    val filteredGroups = remember(loaded, filter) {
        loaded.groupsFor(filter.category)
    }
    val unlockedCount = remember(filteredGroups, system, showAll) {
        if (showAll) filteredGroups.size else filteredGroups.count { it.isUnlocked(system) }
    }

    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            // Top bar: Title + Filter chips + Show All toggle + Close button
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "CG 鉴赏 CG Gallery",
                        color = Wa2Colors.Text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "共 ${filteredGroups.size} 组 ($unlockedCount 已解锁 Unlocked)",
                        color = Wa2Colors.TextDim,
                        fontSize = 11.sp,
                    )
                }

                Segmented(
                    options = CgFilter.entries.map { it to it.label },
                    selected = filter,
                    onSelect = { filter = it },
                )

                ShowAllToggle(
                    checked = showAll,
                    onToggle = { showAll = !showAll },
                )

                CloseButton(onClose)
            }

            // Grid of CG group cards
            LazyVerticalGrid(
                columns = GridCells.Adaptive(185.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(filteredGroups, key = { _, g -> g.id }) { _, group ->
                    val unlocked = showAll || group.isUnlocked(system)
                    val coverName = group.coverFor(system, showAll)
                    CgGroupCard(
                        group = group,
                        coverName = coverName,
                        unlocked = unlocked,
                        imageCache = imageCache,
                        thumbCache = thumbCache,
                        onClick = {
                            if (unlocked) viewerGroupId = group.id
                        },
                    )
                }
            }
        }

        // Full-screen viewer overlay
        val activeGroupId = viewerGroupId
        if (activeGroupId != null) {
            val viewableGroups = remember(filteredGroups, showAll, system) {
                if (showAll) filteredGroups else filteredGroups.filter { it.isUnlocked(system) }
            }
            val startIndex = viewableGroups.indexOfFirst { it.id == activeGroupId }.coerceAtLeast(0)
            if (viewableGroups.isNotEmpty()) {
                CgViewer(
                    groups = viewableGroups,
                    initialGroupIndex = startIndex,
                    imageCache = imageCache,
                    system = system,
                    showAll = showAll,
                    onClose = { viewerGroupId = null },
                )
            } else {
                viewerGroupId = null
            }
        }
    }
}

/** Toggle pill for `解锁全部 Show All` (default ON). */
@Composable
private fun ShowAllToggle(
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (checked) Wa2Colors.Accent else Color(0x33203050))
            .border(BorderStroke(1.dp, if (checked) Wa2Colors.Accent else Wa2Colors.AccentDim), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "解锁全部 Show All: ${if (checked) "ON" else "OFF"}",
            color = if (checked) Wa2Colors.Night else Wa2Colors.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Single CG group card in the gallery grid.
 * Decodes [coverName] via [AssetImageCache] on [Dispatchers.IO] and downscales to 320×180.
 */
@Composable
private fun CgGroupCard(
    group: CgGroup,
    coverName: String,
    unlocked: Boolean,
    imageCache: AssetImageCache,
    thumbCache: LruCache<String, ImageBitmap>,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var thumb by remember(coverName, unlocked) {
        mutableStateOf(if (unlocked) thumbCache.get(coverName) else null)
    }

    LaunchedEffect(coverName, unlocked) {
        if (!unlocked || thumb != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            thumbCache.get(coverName)?.let { return@withContext it }
            val full = imageCache.get("grp", coverName) ?: return@withContext null
            val scaled = if (full.width == THUMB_WIDTH && full.height == THUMB_HEIGHT) {
                full
            } else {
                Bitmap.createScaledBitmap(full, THUMB_WIDTH, THUMB_HEIGHT, true)
            }
            scaled.asImageBitmap().also { thumbCache.put(coverName, it) }
        }
        thumb = decoded
    }

    Column(
        Modifier
            .clip(shape)
            .background(if (unlocked) Color(0x55203050) else Color(0x33141A28))
            .border(1.dp, if (unlocked) Wa2Colors.AccentDim else Color(0x33FFFFFF), shape)
            .clickable(enabled = unlocked, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0A0E18)),
            contentAlignment = Alignment.Center,
        ) {
            if (unlocked) {
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = group.id,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text("…", color = Wa2Colors.TextDim, fontSize = 13.sp)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOCKED", color = Wa2Colors.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("未解锁", color = Wa2Colors.TextDim, fontSize = 11.sp)
                }
            }

            // Variation-count badge (e.g. "1/4") in the top-right corner
            Text(
                "1/${group.variations.size}",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )

            // Group ID badge in the bottom-left corner
            Text(
                group.id,
                color = Wa2Colors.Accent,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Full-screen interactive CG viewer (`CgViewer`).
 *
 * * Tap left/right edges or swipe left/right to cycle variations in the current group, then step to
 *   the next/previous group.
 * * Pinch with two fingers to zoom (`1×..4×`) and pan while zoomed; double-tap toggles `1×`/`2×` zoom.
 * * Center tap toggles a subtle HUD (`v100100 · 1/3`).
 * * Per-variant lock: when [showAll] is false, unseen variants within an unlocked card display a locked
 *   placeholder inside the viewer.
 * * System Back (or HUD `✕`) closes the viewer and returns to the gallery grid.
 */
@Composable
fun CgViewer(
    groups: List<CgGroup>,
    initialGroupIndex: Int,
    imageCache: AssetImageCache,
    system: SystemData = SystemData(),
    showAll: Boolean = true,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)

    val startGroupIdx = initialGroupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
    val startVarIdx = remember(startGroupIdx, showAll) {
        if (showAll) {
            0
        } else {
            groups[startGroupIdx].variations.indexOfFirst { CgGallery.isUnlocked(it, system) }.coerceAtLeast(0)
        }
    }

    var groupIndex by rememberSaveable(initialGroupIndex) { mutableIntStateOf(startGroupIdx) }
    var variationIndex by rememberSaveable(initialGroupIndex) { mutableIntStateOf(startVarIdx) }
    var hudVisible by rememberSaveable { mutableStateOf(true) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val currentGroup = groups[groupIndex.coerceIn(0, groups.lastIndex)]
    val safeVarIdx = variationIndex.coerceIn(0, currentGroup.variations.lastIndex)
    val currentFile = currentGroup.variations[safeVarIdx]
    val stemLabel = currentFile.removeSuffix(".tga")
    val variantUnlocked = showAll || CgGallery.isUnlocked(currentFile, system)

    var bitmap by remember(currentFile, variantUnlocked) {
        mutableStateOf(if (variantUnlocked) imageCache.peek("grp", currentFile)?.asImageBitmap() else null)
    }

    LaunchedEffect(currentFile, variantUnlocked, groupIndex, safeVarIdx) {
        if (variantUnlocked && bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                imageCache.get("grp", currentFile)?.asImageBitmap()
            }
        }
        // Prefetch the next variation or next group's cover for instant transitions.
        val nextFile = if (safeVarIdx + 1 < currentGroup.variations.size) {
            currentGroup.variations[safeVarIdx + 1]
        } else if (groupIndex + 1 < groups.size) {
            groups[groupIndex + 1].coverFor(system, showAll)
        } else {
            null
        }
        if (nextFile != null && (showAll || CgGallery.isUnlocked(nextFile, system))) {
            imageCache.prefetch("grp", nextFile)
        }
    }

    fun resetTransform() {
        scale = 1f
        offset = Offset.Zero
    }

    fun stepNext() {
        resetTransform()
        if (safeVarIdx + 1 < currentGroup.variations.size) {
            variationIndex = safeVarIdx + 1
        } else if (groupIndex + 1 < groups.size) {
            groupIndex += 1
            variationIndex = 0
        } else {
            groupIndex = 0
            variationIndex = 0
        }
    }

    fun stepPrev() {
        resetTransform()
        if (safeVarIdx > 0) {
            variationIndex = safeVarIdx - 1
        } else if (groupIndex > 0) {
            groupIndex -= 1
            variationIndex = groups[groupIndex].variations.lastIndex
        } else {
            groupIndex = groups.lastIndex
            variationIndex = groups[groupIndex].variations.lastIndex
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(groupIndex, safeVarIdx, variantUnlocked) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!variantUnlocked) return@detectTapGestures
                        if (scale > 1.05f) {
                            resetTransform()
                        } else {
                            scale = 2f
                            offset = Offset.Zero
                        }
                    },
                    onTap = { pos ->
                        if (scale <= 1.05f && containerSize.width > 0) {
                            val xRatio = pos.x / containerSize.width.toFloat()
                            when {
                                xRatio < 0.22f -> stepPrev()
                                xRatio > 0.78f -> stepNext()
                                else -> hudVisible = !hudVisible
                            }
                        } else {
                            hudVisible = !hudVisible
                        }
                    },
                )
            }
            .pointerInput(groupIndex, safeVarIdx, variantUnlocked) {
                val touchSlop = viewConfiguration.touchSlop
                val swipeThreshold = 64.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var totalMoved = 0f
                    var hadMultiTouch = false

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (variantUnlocked && (event.changes.size >= 2 || zoomChange != 1f)) {
                                hadMultiTouch = true
                                val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
                                scale = nextScale
                                offset = if (nextScale > 1.01f) {
                                    clampPanOffset(offset + panChange, nextScale, containerSize)
                                } else {
                                    Offset.Zero
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (variantUnlocked && scale > 1.05f) {
                                totalMoved += panChange.getDistance()
                                if (totalMoved > touchSlop) {
                                    offset = clampPanOffset(offset + panChange, scale, containerSize)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else {
                                totalDragX += panChange.x
                                totalDragY += panChange.y
                                totalMoved += panChange.getDistance()
                                if (totalMoved > touchSlop) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!hadMultiTouch && scale <= 1.05f &&
                        abs(totalDragX) >= swipeThreshold &&
                        abs(totalDragX) > abs(totalDragY) * 1.2f
                    ) {
                        if (totalDragX < 0f) stepNext() else stepPrev()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!variantUnlocked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("LOCKED VARIANT · 未解锁差分", color = Wa2Colors.TextDim, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("$stemLabel · ${safeVarIdx + 1}/${currentGroup.variations.size}", color = Wa2Colors.Accent, fontSize = 14.sp)
            }
        } else {
            val img = bitmap
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = stemLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            } else {
                CircularProgressIndicator(color = Wa2Colors.Accent)
            }
        }

        // Subtle HUD overlay (toggled by center tap)
        if (hudVisible) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC0B1020))
                    .border(1.dp, Wa2Colors.AccentDim, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "◀",
                    color = Wa2Colors.Accent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { stepPrev() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    if (variantUnlocked) {
                        "$stemLabel · ${safeVarIdx + 1}/${currentGroup.variations.size}"
                    } else {
                        "$stemLabel · ${safeVarIdx + 1}/${currentGroup.variations.size} (未解锁 Locked)"
                    },
                    color = if (variantUnlocked) Wa2Colors.Text else Wa2Colors.TextDim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "(${groupIndex + 1}/${groups.size})",
                    color = Wa2Colors.TextDim,
                    fontSize = 12.sp,
                )
                Text(
                    "▶",
                    color = Wa2Colors.Accent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { stepNext() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "✕",
                    color = Wa2Colors.TextDim,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { onClose() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Clamps pan [offset] so a zoomed-in image cannot be dragged past its scaled edges. */
private fun clampPanOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0 || scale <= 1f) return Offset.Zero
    val maxX = (size.width * (scale - 1f)) / 2f
    val maxY = (size.height * (scale - 1f)) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
