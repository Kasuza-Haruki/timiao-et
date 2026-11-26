package app.timiao.et.debug

import android.content.Context
import app.timiao.et.core.ConfigRepository
import app.timiao.et.core.Engine
import app.timiao.et.core.FakeConfigRepository
import app.timiao.et.di.AppContainer

/** Debug-only container: fictional config + offline engine, so screenshots never touch a real network. */
class DemoContainer(context: Context) : AppContainer(context) {
    override val configRepository: ConfigRepository = FakeConfigRepository(listOf(DemoData.config()))
    override val engine: Engine = DemoEngine()

    /** No kernel is running in demo mode, so there is nothing to record. */
    override fun startTrafficRecording() = Unit
}
