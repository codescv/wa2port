package com.wa2port.android.game

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wa2port.android.AppViewModel
import com.wa2port.android.data.DisplayLang
import com.wa2port.android.data.SaveStore
import com.wa2port.android.data.WindowMode
import com.wa2port.android.ui.ConfigPanel
import com.wa2port.android.ui.SaveLoadMode
import com.wa2port.android.ui.SaveLoadPanel
import com.wa2port.android.ui.Wa2Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class BarPlacement { SIDE, TOP, OVERLAY }

private const val TAG = "Wa2Game"

/**
 * The game screen: letterboxed 16:9 stage + ADV/NOVEL text + choices + gesture layer + quick bar
 * (in the letterbox when there is room) + overlays (menu, backlog, save/load, config, movie).
 */
@Composable
fun GameScreen(vm: AppViewModel, session: GameSession) {
    val settings = vm.settings
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val painter = session.backend.text

    // Keep the screen on while playing.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Frame loop: the engine runs on the main thread, one update per vsync.
    LaunchedEffect(session) {
        var last = -1L
        while (isActive) {
            val failure = withFrameNanos { now ->
                val dt = if (last < 0) 0L else ((now - last) / 1_000_000L).coerceIn(0L, 100L)
                last = now
                runCatching { session.tick(dt) }.exceptionOrNull()
            }
            if (failure != null) {
                Log.e(TAG, "engine failure", failure)
                vm.error = "引擎错误 Engine error: ${failure.message ?: failure.javaClass.simpleName}"
                vm.exitToTitle()
                break
            }
        }
    }

    // Pause audio / persist 既読 data when backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> {
                    session.paused = true
                    session.holdSkip(false)
                    session.audio.pause()
                    scope.launch { session.persistSystem() }
                }
                Lifecycle.Event.ON_RESUME -> {
                    session.paused = false
                    session.audio.resume()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(session.ended) { if (session.ended) vm.exitToTitle() }
    LaunchedEffect(session.toast) {
        if (session.toast != null) {
            delay(1600)
            session.toast = null
        }
    }

    BackHandler {
        when {
            session.movie != null -> session.movieFinished()
            session.overlay != Overlay.NONE -> session.overlay = Overlay.NONE
            session.windowHidden -> session.windowHidden = false
            else -> session.overlay = Overlay.MENU
        }
    }

    // Quick-bar visibility: overlay bar collapses after 3 s; letterbox bars dim instead.
    var barInteraction by remember { mutableIntStateOf(0) }
    var barIdle by remember { mutableStateOf(false) }
    var barExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(barInteraction) {
        barIdle = false
        delay(3000)
        barIdle = true
        barExpanded = false
    }
    fun act(f: () -> Unit): () -> Unit = { barInteraction++; f() }

    val actions = buildList {
        add(QuickAction("AUTO", session.autoMode, act { session.toggleAuto() }))
        add(QuickAction("SKIP", session.skipToggle, act { session.toggleSkip() }))
        add(QuickAction("LOG", false, act { session.overlay = Overlay.BACKLOG }))
        if (!session.inReplayMode) {
            add(QuickAction("Q.SAVE", false, act { scope.launch { session.quickSave() } }))
            add(QuickAction("Q.LOAD", false, act { scope.launch { session.quickLoad() } }))
            add(QuickAction("SAVE", false, act { session.overlay = Overlay.SAVE }))
            add(QuickAction("LOAD", false, act { session.overlay = Overlay.LOAD }))
        }
        add(
            QuickAction(
                when (settings.lang) {
                    DisplayLang.BOTH -> "中/日 双"
                    DisplayLang.CN -> "中文"
                    DisplayLang.JP -> "日本語"
                },
                false, act { vm.updateSettings { it.copy(lang = it.lang.next()) } },
            ),
        )
        add(QuickAction(settings.windowMode.label, false, act { vm.updateSettings { it.copy(windowMode = it.windowMode.next()) } }))
        add(QuickAction("HIDE", session.windowHidden, act { session.windowHidden = true }))
        add(QuickAction("MENU", false, act { session.overlay = Overlay.MENU }))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val stageW = min(maxWidth, maxHeight * 16f / 9f)
        val stageH = stageW * 9f / 16f
        val side = (maxWidth - stageW) / 2
        val topBox = (maxHeight - stageH) / 2
        val placement = when {
            side >= 60.dp -> BarPlacement.SIDE
            topBox >= 56.dp -> BarPlacement.TOP
            else -> BarPlacement.OVERLAY
        }
        val screenWpx = with(density) { maxWidth.toPx() }
        val screenHpx = with(density) { maxHeight.toPx() }
        val stageWpx = with(density) { stageW.toPx() }
        val stageHpx = with(density) { stageH.toPx() }
        val stageOrigin = Offset((screenWpx - stageWpx) / 2f, (screenHpx - stageHpx) / 2f)
        val edgeSide = with(density) { 24.dp.toPx() }
        val edgeTop = with(density) { 32.dp.toPx() }
        val edgeBottom = with(density) { 56.dp.toPx() }

        val handle = session.backend.rememberStageHandle()
        SideEffect { session.stageHandle = handle }

        val gestureHandler = remember(session) {
            StageGestureHandler(
                onTap = { session.onTap() },
                onLongPressStart = { if (!session.windowHidden && session.choices.isEmpty()) session.holdSkip(true) },
                onLongPressEnd = { session.holdSkip(false) },
                onSwipeUp = { session.overlay = Overlay.BACKLOG },
                onTwoFingerTap = { session.windowHidden = !session.windowHidden },
            )
        }

        // ---------------------------------------------------------------- stage
        Box(Modifier.size(stageW, stageH).align(Alignment.Center)) {
            session.backend.Stage(session.stage, handle, Modifier.fillMaxSize())
            val line = session.line
            val novel = settings.windowMode == WindowMode.NOVEL || session.stage.novelMode
            val waiting = session.revealDone && session.engine.isWaitingForClick && session.choices.isEmpty()
            val opacity = settings.windowOpacity / 100f
            val textScale = settings.textScale / 100f
            if (line != null && !session.windowHidden) {
                if (novel) {
                    NovelPage(
                        painter, session.page.ifEmpty { listOf(line) }, settings.lang, session.visibleJp, session.visibleCn,
                        waiting, opacity, textScale, stageWpx, stageHpx, onOverflow = { session.trimPage() }, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AdvWindow(painter, line, settings.lang, session.visibleJp, session.visibleCn, waiting, opacity, textScale, Modifier.fillMaxSize())
                }
            }
            // Gesture layer above text, below interactive controls.
            Box(
                Modifier.fillMaxSize().stageGestures(gestureHandler) { p ->
                    val x = p.x + stageOrigin.x
                    val y = p.y + stageOrigin.y
                    x < edgeSide || x > screenWpx - edgeSide || y < edgeTop || y > screenHpx - edgeBottom
                },
            )
            if (session.choices.isNotEmpty() && !session.windowHidden) {
                ChoiceCards(
                    painter, session.choices, settings.lang, stageHpx, textScale, onChoose = { session.choose(it) },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(if (novel || line == null) 1f else 0.7f),
                )
            }
            // Mode indicators.
            if (session.skipping || session.autoMode) {
                Text(
                    if (session.skipping) "SKIP ▶▶" else "AUTO ▶",
                    color = Wa2Colors.Accent, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (placement == BarPlacement.OVERLAY && !session.windowHidden && session.overlay == Overlay.NONE) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                    if (barExpanded) {
                        QuickRow(actions + QuickAction("✕", false) { barExpanded = false })
                    } else {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x66101A30))
                                .clickable { barExpanded = true; barInteraction++ },
                            contentAlignment = Alignment.Center,
                        ) { Text("☰", color = Wa2Colors.Text, fontSize = 20.sp) }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- letterbox quick bar
        val barAlpha = if (barIdle) 0.45f else 1f
        when (placement) {
            BarPlacement.SIDE -> {
                val colW = (side - 8.dp).coerceAtMost(96.dp)
                QuickColumn(actions.take(6), colW, Modifier.align(Alignment.CenterStart).width(side).fillMaxHeight().padding(vertical = 8.dp).alpha(barAlpha))
                QuickColumn(actions.drop(6), colW, Modifier.align(Alignment.CenterEnd).width(side).fillMaxHeight().padding(vertical = 8.dp).alpha(barAlpha))
            }
            BarPlacement.TOP -> Row(Modifier.align(Alignment.TopCenter).height(topBox).fillMaxWidth().alpha(barAlpha), verticalAlignment = Alignment.CenterVertically) {
                QuickRow(actions, Modifier.padding(horizontal = 8.dp))
            }
            BarPlacement.OVERLAY -> {}
        }

        session.toast?.let {
            Text(
                it, color = Wa2Colors.Text, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                    .background(Color(0xCC101A30), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ---------------------------------------------------------------- overlays
        when (session.overlay) {
            Overlay.NONE -> {}
            Overlay.MENU -> GameMenu(
                scene = session.engine.scene,
                inReplayMode = session.inReplayMode,
                onResume = { session.overlay = Overlay.NONE },
                onSave = { if (!session.inReplayMode) session.overlay = Overlay.SAVE },
                onLoad = { if (!session.inReplayMode) session.overlay = Overlay.LOAD },
                onLog = { session.overlay = Overlay.BACKLOG },
                onConfig = { session.overlay = Overlay.CONFIG },
                onTitle = { vm.exitToTitle() },
            )
            Overlay.BACKLOG -> {
                val entries = remember { session.backlog() }
                BacklogOverlay(
                    painter, entries, session::resolve, settings.lang,
                    onReplay = { ref -> ref.voice?.let { session.audio.replayVoice(it) } },
                    onClose = { session.overlay = Overlay.NONE },
                )
            }
            Overlay.SAVE -> {
                val thumb = remember { SaveStore.encodeThumbnail(handle.captureThumbnail()) }
                Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
                    SaveLoadPanel(SaveLoadMode.SAVE, vm.saves, settings.lang, onPick = { slot ->
                        session.toast = if (session.saveTo(slot, thumb)) "已保存 Saved" else "存档失败 Save failed"
                    }, onClose = { session.overlay = Overlay.NONE })
                }
            }
            Overlay.LOAD -> Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
                SaveLoadPanel(SaveLoadMode.LOAD, vm.saves, settings.lang, onPick = { slot ->
                    if (session.loadFrom(slot)) session.toast = "已读取 Loaded" else session.toast = "读档失败 Load failed"
                }, onClose = { session.overlay = Overlay.NONE })
            }
            Overlay.CONFIG -> Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
                ConfigPanel(settings, vm::updateSettings) { session.overlay = Overlay.NONE }
            }
        }

        session.movie?.let { stem ->
            session.backend.Movie(stem, onFinished = { session.movieFinished() }, modifier = Modifier.fillMaxSize())
        }
    }
}
