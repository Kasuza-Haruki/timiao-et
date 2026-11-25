package app.timiao.et

import android.app.Application
import app.timiao.et.di.AppContainer

class TimiaoApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
