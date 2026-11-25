package app.timiao.et.service

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.timiao.et.MainActivity
import app.timiao.et.TimiaoApplication
import app.timiao.et.core.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: one tap connects or disconnects the last used network. */
class ConnectionTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        val engine = (application as TimiaoApplication).container.engine
        s.launch {
            engine.status.collect { map ->
                val on = map.values.any {
                    it.state == LinkState.Connected || it.state == LinkState.Connecting
                }
                qsTile?.apply {
                    state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        if (qsTile?.state == Tile.STATE_ACTIVE) {
            TimiaoVpnService.stop(this)
            return
        }
        val id = ServiceState.last(this)
        if (id == null || VpnService.prepare(this) != null) {
            // No network chosen yet, or VPN consent is missing: open the app.
            startActivityAndCollapse(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            TimiaoVpnService.start(this, id)
        }
    }
}
