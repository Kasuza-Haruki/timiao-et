package app.timiao.et.di

import android.content.Context
import app.timiao.et.core.AndroidKeystoreCipher
import app.timiao.et.core.ConfigRepository
import app.timiao.et.core.Engine
import app.timiao.et.core.FileConfigRepository
import app.timiao.et.core.JniEngine
import app.timiao.et.service.ServiceEngine
import app.timiao.et.service.VpnTunProvider
import app.timiao.et.ui.data.SettingsStore

/** Single manual DI point. No Hilt. */
import app.timiao.et.core.LinkState
import app.timiao.et.traffic.TrafficRecorder
import app.timiao.et.traffic.TrafficStore
import app.timiao.et.ui.screens.currentNetworkKind
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val configRepository: ConfigRepository = FileConfigRepository(java.io.File(app.filesDir, "networks.enc"), AndroidKeystoreCipher())

    /** Raw native engine. Only the VPN service calls start/stop on it. */
    val nativeEngine: JniEngine by lazy { JniEngine(configRepository, VpnTunProvider) }

    /** What the UI uses: routes start/stop through the VPN service. */
    val engine: Engine by lazy { ServiceEngine(app, nativeEngine) }

    val settingsStore: SettingsStore = SettingsStore(app)

    val trafficRecorder = TrafficRecorder(
        TrafficStore(java.io.File(app.filesDir, "traffic-stats.txt")),
    )

    /** Records traffic for as long as the process lives (the VPN foreground service keeps it alive). */
    fun startTrafficRecording() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            var active = emptySet<String>()
            nativeEngine.status.collect { statuses ->
                val connected = statuses.filterValues { it.state == LinkState.Connected }
                (active - connected.keys).forEach { trafficRecorder.release(it) }
                active = connected.keys
                val kind = currentNetworkKind(cm)
                connected.forEach { (id, st) -> trafficRecorder.record(id, kind, st.peers) }
            }
        }
    }
}
