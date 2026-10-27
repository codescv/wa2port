package com.wa2port.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wa2port.android.ai.AiLearningController
import com.wa2port.android.bridge.RenderBackend
import com.wa2port.android.bridge.RenderBackends
import com.wa2port.android.data.AssetCandidate
import com.wa2port.android.data.AssetLocator
import com.wa2port.android.data.ImportProgress
import com.wa2port.android.data.SafImporter
import com.wa2port.android.data.SaveStore
import com.wa2port.android.data.Settings
import com.wa2port.android.data.SettingsStore
import com.wa2port.android.game.GameSession
import com.wa2port.android.update.UpdateController
import com.wa2port.core.archive.Wa2Assets
import com.wa2port.core.engine.SystemData
import com.wa2port.core.engine.Wa2Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Top-level destinations (simple back stack; no navigation library needed). */
sealed interface Screen {
    data object Title : Screen
    data object Game : Screen
    data object Config : Screen
    data object Assets : Screen
    data object Load : Screen
    data object Gallery : Screen
    data object BgmGallery : Screen
    data object SceneReplay : Screen
}

/** App-wide state: navigation, settings, asset location/import, engine and the active [GameSession]. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val settingsStore = SettingsStore(File(app.filesDir, "settings.json"))
    val saves = SaveStore(File(app.filesDir, "saves"))
    private val systemFile = File(app.filesDir, "system.json")

    var settings by mutableStateOf(settingsStore.load())
        private set

    val backStack = mutableStateListOf<Screen>(Screen.Title)
    val screen: Screen get() = backStack.last()

    var assetRoot by mutableStateOf<File?>(null)
        private set
    var candidates by mutableStateOf<List<AssetCandidate>>(emptyList())
        private set
    var importProgress by mutableStateOf<ImportProgress?>(null)
        private set
    private var importJob: Job? = null

    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    private var engine: Wa2Engine? = null
    private var backend: RenderBackend? = null
    var session by mutableStateOf<GameSession?>(null)
        private set

    /** AI learning (Kotori-style breakdown); survives sessions, cache in `filesDir/ai_cache`. */
    val ai = AiLearningController(File(app.filesDir, "ai_cache"), { settings }, viewModelScope)

    /** In-app update check / APK download (manual only); APKs live in `cacheDir/updates`. */
    val update = UpdateController(app, { settings }, viewModelScope)

    init {
        rescanAssets()
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(s: Screen) {
        backStack += s
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.size - 1)
        return true
    }

    private fun resetTo(s: Screen) {
        backStack.clear()
        backStack += s
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(f: (Settings) -> Settings) {
        val next = f(settings)
        if (next == settings) return
        settings = next
        settingsStore.save(next)
        session?.audio?.setVolumes(next.bgmVolume / 100f, next.seVolume / 100f, next.voiceVolume / 100f)
    }

    // ------------------------------------------------------------------ assets

    fun rescanAssets() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { AssetLocator.candidates(ctx, settings) }
            candidates = list
            val root = list.firstOrNull { it.valid }?.dir
            if (root?.absolutePath != assetRoot?.absolutePath) {
                closeEngine()
                assetRoot = root
            }
        }
    }

    fun useCustomPath(path: String) {
        updateSettings { it.copy(assetPath = path.trim().ifEmpty { null }) }
        rescanAssets()
    }

    fun startImport(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        updateSettings { it.copy(importTreeUri = uri.toString()) }
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val dest = AssetLocator.importDir(ctx)
            val result = SafImporter(ctx).import(uri, dest) { p -> viewModelScope.launch(Dispatchers.Main) { importProgress = p } }
            importProgress = result
            if (result.phase == ImportProgress.Phase.DONE) {
                if (!AssetLocator.isValid(dest)) {
                    importProgress = result.copy(
                        phase = ImportProgress.Phase.FAILED,
                        message = "复制完成，但未找到 script.pak / ck-gal.pak。请选择游戏根目录。Copied, but no script.pak/ck-gal.pak found — pick the game's root folder.",
                    )
                }
                rescanAssets()
            }
        }
    }

    fun resumeImport() {
        settings.importTreeUri?.let { startImport(Uri.parse(it)) }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    // ------------------------------------------------------------------ game

    private suspend fun ensureEngine(): Wa2Engine? {
        engine?.let { return it }
        val root = assetRoot ?: return null
        val e = withContext(Dispatchers.IO) {
            val eng = Wa2Engine(Wa2Assets(root))
            if (systemFile.isFile) runCatching { eng.system.loadJson(systemFile.readText()) }
            eng
        }
        engine = e
        val b = RenderBackends.create(ctx, e)
        backend = b
        runCatching { b.preload() }
        return e
    }

    private suspend fun newSession(): GameSession? {
        val e = ensureEngine() ?: run {
            error = "未找到游戏资源 Assets not found"
            return null
        }
        ai.close(session)
        session?.dispose()
        val s = GameSession(e, backend!!, saves, systemFile) { settings }
        s.audio.setVolumes(settings.bgmVolume / 100f, settings.seVolume / 100f, settings.voiceVolume / 100f)
        session = s
        return s
    }

    /** Starts a new game at [scene] (`1001` = IC, `2001` = CC). */
    fun startGame(scene: String) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "载入中… Loading…"
            try {
                val s = newSession() ?: return@launch
                if (!s.startNew(scene)) {
                    error = "无法开始场景 Cannot start scene $scene"
                    return@launch
                }
                resetTo(Screen.Title)
                navigate(Screen.Game)
            } catch (t: Throwable) {
                error = "启动失败 Start failed: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Loads [slot] from the title screen. */
    fun loadGame(slot: Int) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "载入中… Loading…"
            try {
                val data = saves.read(slot) ?: run { error = "存档损坏 Save unreadable"; return@launch }
                val s = newSession() ?: return@launch
                s.loadData(data)
                resetTo(Screen.Title)
                navigate(Screen.Game)
            } catch (t: Throwable) {
                error = "读档失败 Load failed: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Starts Scene Replay slot [slotNumber] (`1..24`) and returns to [Screen.SceneReplay] when finished. */
    fun startReplay(slotNumber: Int, fallbackScript: String? = null) {
        if (busy != null) return
        viewModelScope.launch {
            busy = "载入回想… Loading replay…"
            try {
                val s = newSession() ?: return@launch
                if (!s.startReplay(slotNumber, fallbackScript)) {
                    error = "无法开始回想 Cannot start replay #$slotNumber"
                    return@launch
                }
                if (backStack.lastOrNull() != Screen.Game) navigate(Screen.Game)
            } catch (t: Throwable) {
                error = "启动失败 Start failed: ${t.message}"
            } finally {
                busy = null
            }
        }
    }

    fun exitToTitle() {
        ai.close(session)
        val wasReplay = session?.inReplayMode == true
        session?.dispose()
        session = null
        if (wasReplay && Screen.SceneReplay in backStack) {
            while (backStack.size > 1 && backStack.last() != Screen.SceneReplay) {
                backStack.removeAt(backStack.size - 1)
            }
        } else {
            resetTo(Screen.Title)
        }
    }

    /** Memory pressure: drop decoded bitmaps. */
    fun trimMemory() {
        backend?.trimMemory()
    }

    /** Returns the current [SystemData] (live from the engine if initialized, otherwise read from disk). */
    fun currentSystemData(): SystemData {
        engine?.system?.let { return it }
        val sys = SystemData()
        if (systemFile.isFile) runCatching { sys.loadJson(systemFile.readText()) }
        return sys
    }

    private fun closeEngine() {
        ai.close(session)
        session?.dispose()
        session = null
        backend?.release()
        backend = null
        engine?.assets?.close()
        engine = null
    }

    override fun onCleared() {
        update.cancelDownload()
        closeEngine()
        super.onCleared()
    }
}
