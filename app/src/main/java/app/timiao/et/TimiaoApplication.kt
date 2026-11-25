package app.timiao.et

import android.app.Application
import app.timiao.et.di.AppContainer

class TimiaoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.startTrafficRecording()
        app.timiao.et.auto.AutoConnect.ensureRegistered(this)
        app.timiao.et.auto.AutoConnect.ensureInProcess(this)
    }
}
