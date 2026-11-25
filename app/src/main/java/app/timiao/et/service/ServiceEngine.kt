package app.timiao.et.service

import android.content.Context
import app.timiao.et.core.Engine
import app.timiao.et.core.JniEngine
import app.timiao.et.core.NetworkStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Engine the UI talks to. start/stop go through [TimiaoVpnService] so the TUN device and the
 * foreground notification always exist while a network runs. Status comes from the native engine.
 */
class ServiceEngine(
    private val context: Context,
    private val native: JniEngine,
) : Engine {
    override val status: StateFlow<Map<String, NetworkStatus>> = native.status

    override suspend fun start(id: String) {
        TimiaoVpnService.start(context, id)
    }

    override suspend fun stop(id: String) {
        TimiaoVpnService.stop(context, id)
    }
}
