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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wa2port.android.game.GameScreen
import com.wa2port.android.ui.AssetSetupScreen
import com.wa2port.android.ui.CgGalleryScreen
import com.wa2port.android.ui.ConfigScreen
import com.wa2port.android.ui.SaveLoadMode
import com.wa2port.android.ui.SaveLoadScreen
import com.wa2port.android.ui.TitleScreen
import com.wa2port.android.ui.Wa2Colors
import com.wa2port.android.ui.Wa2Theme

/** Single activity: landscape, immersive full-screen, Compose UI. */
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
        setContent { Wa2Theme { Wa2App(vm) } }
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

/** Root composable: routes [AppViewModel.screen] and shows global busy/error UI. */
@Composable
fun Wa2App(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Wa2Colors.Night)) {
        when (vm.screen) {
            Screen.Title -> TitleScreen(vm)
            Screen.Game -> {
                val s = vm.session
                if (s != null) GameScreen(vm, s) else LaunchedEffect(Unit) { vm.exitToTitle() }
            }
            Screen.Config -> ConfigScreen(vm.settings, onChange = vm::updateSettings, onClose = { vm.back() })
            Screen.Assets -> AssetSetupScreen(vm)
            Screen.Load -> SaveLoadScreen(
                mode = SaveLoadMode.LOAD,
                store = vm.saves,
                lang = vm.settings.lang,
                onPick = { slot -> vm.loadGame(slot) },
                onClose = { vm.back() },
            )
            Screen.Gallery -> CgGalleryScreen(vm = vm, onClose = { vm.back() })
        }
        if (vm.screen != Screen.Title && vm.screen != Screen.Game) BackHandler { vm.back() }

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
