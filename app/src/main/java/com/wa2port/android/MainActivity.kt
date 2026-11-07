package com.wa2port.android

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wa2port.android.ai.NotebookOverlay
import com.wa2port.android.data.OrientationMode
import com.wa2port.android.game.AiBreakdownOverlay
import com.wa2port.android.game.GameScreen
import com.wa2port.android.ui.AssetSetupScreen
import com.wa2port.android.ui.BgmGalleryScreen
import com.wa2port.android.ui.CgGalleryScreen
import com.wa2port.android.ui.ConfigCategory
import com.wa2port.android.ui.ConfigScreen
import com.wa2port.android.ui.LocalCompact
import com.wa2port.android.ui.LocalIsPortrait
import com.wa2port.android.ui.LocalScreenInsets
import com.wa2port.android.ui.SaveLoadMode
import com.wa2port.android.ui.SaveLoadScreen
import com.wa2port.android.ui.SceneReplayScreen
import com.wa2port.android.ui.ScreenClass
import com.wa2port.android.ui.TitleScreen
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.android.ui.Wa2Theme
import com.wa2port.android.ui.rememberScreenInsets

/** Single activity: portrait by default (landscape / auto via [OrientationMode]), immersive full-screen, Compose UI. */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        applyOrientation(vm.settings.orientation)
        setContent {
            LaunchedEffect(vm.settings.orientation) { applyOrientation(vm.settings.orientation) }
            Wa2Theme { Wa2App(vm) }
        }
    }

    /** Requests the Activity orientation for [mode] (only when it differs; never recreates: `configChanges`). */
    private fun applyOrientation(mode: OrientationMode) {
        val wanted = mode.activityOrientation
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    override fun onResume() {
        super.onResume()
        vm.update.onHostResume(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) vm.trimMemory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Root composable: routes [AppViewModel.screen] and shows global busy/error UI. Provides
 * [LocalIsPortrait] / [LocalCompact] from the measured window constraints and the floored
 * [LocalScreenInsets]; each leaf screen / overlay applies them once via `screenSafePadding()`.
 */
@Composable
fun Wa2App(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        val portrait = ScreenClass.isPortrait(maxWidth.value, maxHeight.value)
        val compact = ScreenClass.isCompact(portrait, maxWidth.value)
        CompositionLocalProvider(
            LocalIsPortrait provides portrait,
            LocalCompact provides compact,
            LocalScreenInsets provides rememberScreenInsets(),
        ) { Wa2Routes(vm) }
    }
}

@Composable
private fun Wa2Routes(vm: AppViewModel) {
    Box(Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.Title -> TitleScreen(vm, onNotebook = { vm.ai.openNotebook(null) })
            Screen.Game -> {
                val s = vm.session
                if (s != null) GameScreen(vm, s) else LaunchedEffect(Unit) { vm.exitToTitle() }
            }
            Screen.Config -> ConfigScreen(
                vm.settings,
                onChange = vm::updateSettings,
                onClose = { vm.back() },
                ai = vm.ai,
                update = vm.update,
                initialCategory = vm.initialConfigCategory,
            )
            Screen.Assets -> AssetSetupScreen(vm)
            Screen.Load -> SaveLoadScreen(
                mode = SaveLoadMode.LOAD,
                store = vm.saves,
                lang = vm.settings.lang,
                onPick = { slot -> vm.loadGame(slot) },
                onClose = { vm.back() },
            )
            Screen.Gallery -> CgGalleryScreen(vm = vm, onClose = { vm.back() })
            Screen.BgmGallery -> BgmGalleryScreen(vm = vm, onClose = { vm.back() })
            Screen.SceneReplay -> SceneReplayScreen(vm = vm, onClose = { vm.back() })
        }
        if (vm.screen != Screen.Game && vm.screen != Screen.Config) {
            vm.ai.breakdown?.let { st ->
                AiBreakdownOverlay(
                    state = st,
                    ai = vm.ai,
                    persona = vm.ai.persona,
                    onPersona = { id -> vm.updateSettings { it.copy(aiPersonaId = id) } },
                    onReplayVoice = null,
                    onOpenSettings = {
                        vm.ai.close(null)
                        vm.ai.closeNotebook(null)
                        vm.openConfig(ConfigCategory.AI)
                    },
                    onClose = { vm.ai.close(null) },
                )
            }
        }
        if (vm.screen != Screen.Game && vm.ai.notebookOpen && vm.ai.breakdown == null) {
            NotebookOverlay(
                ai = vm.ai,
                onReplayVoice = null,
                onClose = { vm.ai.closeNotebook(null) },
            )
        }
        // The AI sheet (here or in ConfigScreen) handles Back itself while the notebook is closed: it
        // slides down like a pull-dismiss, so this handler stays disabled then (deterministic order).
        val aiSheetOwnsBack = vm.ai.breakdown != null && !vm.ai.notebookOpen
        if (vm.screen != Screen.Game && (vm.ai.notebookOpen || vm.ai.breakdown != null || vm.screen != Screen.Title)) {
            BackHandler(enabled = !aiSheetOwnsBack) {
                when {
                    vm.ai.notebookOpen -> vm.ai.closeNotebook(null)
                    vm.ai.breakdown != null -> vm.ai.close(null)
                    vm.screen != Screen.Title -> vm.back()
                }
            }
        }

        vm.busy?.let {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Wa2Colors.Accent)
            }
        }
        vm.error?.let { msg ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
                text = { Text(msg) },
            )
        }
    }
}
