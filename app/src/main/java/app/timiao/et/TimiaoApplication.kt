package app.timiao.et

import android.app.Application
import app.timiao.et.di.AppContainer

open class TimiaoApplication : Application() {
    val container: AppContainer by lazy { createContainer() }

    /** Overridden only by the debug source set (demo mode). Release uses AppContainer. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container.startTrafficRecording()
        app.timiao.et.auto.AutoConnect.ensureRegistered(this)
        app.timiao.et.auto.AutoConnect.ensureInProcess(this)
    }
}
