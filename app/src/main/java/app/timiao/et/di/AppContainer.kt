package app.timiao.et.di

import android.content.Context
import app.timiao.et.core.ConfigRepository
import app.timiao.et.core.Engine
import app.timiao.et.core.FakeConfigRepository
import app.timiao.et.core.FakeEngine
import app.timiao.et.ui.data.SettingsStore

/**
 * Single manual DI point. When the real JNI engine and persistent repository land,
 * swap the two Fake* instances here. No Hilt.
 */
class AppContainer(context: Context) {
    val engine: Engine = FakeEngine()
    val configRepository: ConfigRepository = FakeConfigRepository()
    val settingsStore: SettingsStore = SettingsStore(context)
}
