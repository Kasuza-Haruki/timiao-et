package app.timiao.et.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.timiao.et.TimiaoApplication
import app.timiao.et.service.TimiaoVpnService
import app.timiao.et.split.SplitMode
import app.timiao.et.split.SplitPolicy
import app.timiao.et.split.SplitStore
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
                    // Sets the per-app split policy. Extras: id (optional), mode (all|allow|deny),
                    // packages (comma separated). Takes effect on the next connect.
                    "app.timiao.et.DEBUG_SPLIT" -> {
                        val id = intent.getStringExtra("id")
                            ?: container.configRepository.get("Home-Lab")?.id
                        if (id == null) {
                            Log.w(TAG, "split: no network id")
                        } else {
                            val mode = SplitMode.fromKey(intent.getStringExtra("mode")) ?: SplitMode.All
                            val packages = (intent.getStringExtra("packages") ?: "")
                                .split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                            SplitStore(context).save(id, SplitPolicy(mode, packages))
                            Log.i(TAG, "split id=$id mode=${mode.key} apps=${packages.size}")
                        }
                    }
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
