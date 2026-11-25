package app.timiao.et.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.timiao.et.TimiaoApplication
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.GlassBackdrop
import app.timiao.et.ui.screens.DiagnosticsScreen
import app.timiao.et.ui.theme.LocalReducedMotion
import app.timiao.et.ui.theme.TimiaoTheme

/** Debug-only entry so the diagnostics screen can be opened without touching AppNav. */
class DiagnosticsDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as TimiaoApplication).container
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            val settings by vm.settings.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalReducedMotion provides false) {
                TimiaoTheme(settings = settings) {
                    Box(Modifier.fillMaxSize()) {
                        GlassBackdrop()
                        DiagnosticsScreen(
                            vm = vm,
                            onBack = { finish() },
                            modifier = Modifier
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        )
                    }
                }
            }
        }
    }
}
