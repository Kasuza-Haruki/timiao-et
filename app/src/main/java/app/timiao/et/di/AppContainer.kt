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
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val configRepository: ConfigRepository = FileConfigRepository(java.io.File(app.filesDir, "networks.enc"), AndroidKeystoreCipher())

    /** Raw native engine. Only the VPN service calls start/stop on it. */
    val nativeEngine: JniEngine by lazy { JniEngine(configRepository, VpnTunProvider) }

    /** What the UI uses: routes start/stop through the VPN service. */
    val engine: Engine by lazy { ServiceEngine(app, nativeEngine) }

    val settingsStore: SettingsStore = SettingsStore(app)
}
