package app.timiao.et

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import app.timiao.et.service.TimiaoVpnService
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.PendingOpenNetwork
import app.timiao.et.ui.navigation.TimiaoRoot
import app.timiao.et.ui.theme.LocalReducedMotion
import app.timiao.et.ui.theme.TimiaoTheme

class MainActivity : AppCompatActivity() {
    private var pendingGo: (() -> Unit)? = null
    private var appViewModel: AppViewModel? = null
    private var queuedOpen: PendingOpenNetwork? = null

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val go = pendingGo
        pendingGo = null
        if (r.resultCode == RESULT_OK) go?.invoke()
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureVpnConsent(go: () -> Unit) {
        val intent = TimiaoVpnService.prepareIntent(this)
        if (intent == null) go() else {
            pendingGo = go
            vpnConsent.launch(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * timiao://import links and shared/opened .toml files. Saved without the secret if the link had none.
     * The imported network is opened in the editor; a missing secret focuses that field.
     */
    private fun handleImportIntent(intent: Intent?) {
        val container = (application as TimiaoApplication).container
        lifecycleScope.launch {
            val result = app.timiao.et.share.ImportIntents.handle(this@MainActivity, intent) { toml, hasSecret ->
                var cfg = container.configRepository.importToml(toml)
                // Never reuse someone else's instance id: two devices with one id collide.
                val existing = container.configRepository.get(cfg.id)
                if (existing != null) cfg = cfg.copy(instanceId = java.util.UUID.randomUUID().toString())
                try {
                    container.configRepository.save(cfg)
                } catch (error: Exception) {
                    val message = if (error is app.timiao.et.core.ConfigWriteBlockedException) {
                        getString(R.string.storage_write_blocked)
                    } else {
                        getString(R.string.storage_save_failed)
                    }
                    throw IllegalStateException(message, error)
                }
                openImported(cfg.id, focusSecret = !hasSecret)
                if (!hasSecret) android.widget.Toast.makeText(this@MainActivity, getString(R.string.main_imported_missing_secret, cfg.networkName), android.widget.Toast.LENGTH_LONG).show()
                else android.widget.Toast.makeText(this@MainActivity, getString(R.string.main_imported, cfg.networkName), android.widget.Toast.LENGTH_SHORT).show()
            }
            if (result is app.timiao.et.share.ImportIntentResult.Failure)
                android.widget.Toast.makeText(this@MainActivity, result.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Opens [id] when the UI is ready; otherwise the id stays queued until setContent. */
    private fun openImported(id: String, focusSecret: Boolean) {
        val vm = appViewModel
        if (vm == null) {
            queuedOpen = PendingOpenNetwork(id, focusSecret)
        } else {
            vm.requestOpenNetwork(id, focusSecret)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val container = (application as TimiaoApplication).container
        // A configuration change reuses the launch intent; import only on a cold start.
        if (savedInstanceState == null) handleImportIntent(intent)
        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.factory(container))
            appViewModel = vm
            // An import that finished before the UI existed opens once the nav host is ready.
            LaunchedEffect(Unit) {
                queuedOpen?.let {
                    vm.requestOpenNetwork(it.id, it.focusSecret)
                    queuedOpen = null
                }
            }
            vm.ensureVpnConsent = ::ensureVpnConsent
            val settings by vm.settings.collectAsStateWithLifecycle()
            val reducedMotion = rememberSystemReducedMotion()
            val dark = when (settings.themeMode) {
                app.timiao.et.ui.data.ThemeMode.System -> isSystemInDarkTheme()
                app.timiao.et.ui.data.ThemeMode.Light -> false
                app.timiao.et.ui.data.ThemeMode.Dark -> true
            }
            SystemBarAppearance(dark = dark)
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                TimiaoTheme(settings = settings) {
                    TimiaoRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Reads the system animator scale; zero means "reduce motion" is on. */
@Composable
private fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    fun read(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Exception) {
        false
    }

    var reduced by remember { mutableStateOf(read()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = read()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reduced
}
