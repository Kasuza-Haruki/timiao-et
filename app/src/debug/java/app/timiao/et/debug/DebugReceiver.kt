package app.timiao.et.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.timiao.et.TimiaoApplication
import app.timiao.et.service.TimiaoVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug-only test hook. Never logs config contents (they hold the network secret). */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as TimiaoApplication).container
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    "app.timiao.et.DEBUG_IMPORT" -> {
                        val file = java.io.File(context.getExternalFilesDir(null), intent.getStringExtra("file") ?: "emu.toml")
                        val cfg = container.configRepository.importToml(file.readText())
                        container.configRepository.save(cfg)
                        Log.i(TAG, "imported id=${cfg.id} name=${cfg.networkName} peers=${cfg.peers.size}")
                    }
                    "app.timiao.et.DEBUG_START" -> {
                        val id = intent.getStringExtra("id") ?: container.configRepository.get("Home-Lab")?.id
                        if (id != null) TimiaoVpnService.start(context, id) else Log.w(TAG, "no network to start")
                    }
                    "app.timiao.et.DEBUG_STOP" -> TimiaoVpnService.stop(context)
                    "app.timiao.et.DEBUG_DUMP" -> {
                        val json = com.easytier.jni.EasyTierJNI.collectNetworkInfos(32) ?: "null"
                        java.io.File(context.getExternalFilesDir(null), "infos.json").writeText(json)
                        Log.i(TAG, "dumped ${json.length} chars")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "debug action failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "TimiaoDebug" }
}
