package app.timiao.et.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.timiao.et.core.FakeConfigRepository
import app.timiao.et.core.FakeEngine
import app.timiao.et.core.PeerInfo
import app.timiao.et.share.ImportSink
import app.timiao.et.share.PeerActionsSheetContent
import app.timiao.et.traffic.BytePair
import app.timiao.et.traffic.NetworkKind
import app.timiao.et.traffic.TrafficData
import app.timiao.et.traffic.TrafficPolicy
import app.timiao.et.traffic.TrafficRecorder
import app.timiao.et.traffic.TrafficStore
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.GlassBackdrop
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.data.SettingsStore
import app.timiao.et.ui.screens.ScanScreen
import app.timiao.et.ui.screens.ShareScreen
import app.timiao.et.ui.screens.TrafficScreen
import app.timiao.et.ui.theme.LocalReducedMotion
import app.timiao.et.ui.theme.TimiaoTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Debug-only gallery for the share / scan / traffic screens. Not part of release builds.
 * Launch: adb shell am start -n app.timiao.et/.debug.ShareDebugActivity
 */
class ShareDebugActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsStore = SettingsStore(this)
        val repository = FakeConfigRepository()
        val engine = FakeEngine()
        val vm = AppViewModel(engine, repository, settingsStore)
        val recorder = seededRecorder()

        setContent {
            TimiaoTheme(settings = AppSettings()) {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize()) {
                        GlassBackdrop()
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            var tab by rememberSaveable { mutableIntStateOf(0) }
                            SegmentedGlass(
                                options = listOf("分享", "扫码", "流量", "节点"),
                                selected = tab,
                                onSelect = { tab = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> ShareScreen(vm, "home", onBack = {})
                                    1 -> ScanScreen(
                                        importSink = ImportSink { _, _ ->
                                            delay(200)
                                        },
                                        onBack = {},
                                    )
                                    2 -> TrafficScreen(vm, recorder)
                                    else -> Column(
                                        Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        PeerActionsSheetContent(
                                            peer = SamplePeer,
                                            onDone = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun seededRecorder(): TrafficRecorder {
        val store = TrafficStore(File(cacheDir, "debug-traffic.txt"))
        val today = LocalDate.now()
        val days = (0..6).associate { offset ->
            val date = today.minusDays(offset.toLong())
            val scale = (offset + 2).toLong()
            date to mapOf(
                NetworkKind.WIFI to BytePair(tx = 120_000 * scale, rx = 480_000 * scale),
                NetworkKind.MOBILE to BytePair(tx = 18_000 * scale, rx = 96_000 * scale),
            )
        }
        store.save(
            TrafficData(
                days = days,
                policy = TrafficPolicy(blockOnMobile = false, warnAtMb = 500),
            ),
        )
        return TrafficRecorder(store)
    }

    private val SamplePeer = PeerInfo(
        hostname = "nas-home",
        ipv4 = "10.144.144.2/24",
        cost = "p2p",
        tunnel = "udp",
        latencyMs = 12.0,
        lossRate = 0.0,
        natType = "FullCone",
        txBytes = 1_024_000,
        rxBytes = 2_048_000,
        version = "2.7.1",
    )
}
