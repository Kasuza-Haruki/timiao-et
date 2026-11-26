package app.timiao.et.debug

import app.timiao.et.TimiaoApplication
import app.timiao.et.di.AppContainer
import java.io.File

/**
 * Debug-only application. When files/demo_mode exists it swaps in [DemoContainer].
 * Toggle with: adb shell am broadcast -a app.timiao.et.DEBUG_DEMO --ez on true
 * (the receiver restarts the process so the container is rebuilt).
 */
class DebugApplication : TimiaoApplication() {
    override fun createContainer(): AppContainer =
        if (File(filesDir, "demo_mode").isFile) DemoContainer(this) else AppContainer(this)
}
