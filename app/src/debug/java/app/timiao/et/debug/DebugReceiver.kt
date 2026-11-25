package app.timiao.et.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.timiao.et.TimiaoApplication
import app.timiao.et.core.NetworkConfig
import app.timiao.et.service.TimiaoVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
                        val target = findConfig(intent, container.configRepository.networks.first())
                        if (target != null) TimiaoVpnService.start(context, target.id) else Log.w(TAG, "no network to start")
                    }
                    "app.timiao.et.DEBUG_STOP" -> {
                        val explicit = EXTRAS.any { intent.hasExtra(it) }
                        if (!explicit) {
                            TimiaoVpnService.stop(context)
                        } else {
                            val target = findConfig(intent, container.configRepository.networks.first())
                            if (target != null) TimiaoVpnService.stop(context, target.id) else Log.w(TAG, "network to stop not found")
                        }
                    }
                    "app.timiao.et.DEBUG_PREPARE" -> {
                        val intent = TimiaoVpnService.prepareIntent(context)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            Log.i(TAG, "requested VPN consent")
                        } else {
                            Log.i(TAG, "VPN consent already granted")
                        }
                    }
                    "app.timiao.et.DEBUG_DUMP" -> {
                        val json = com.easytier.jni.EasyTierJNI.collectNetworkInfos(32) ?: "null"
                        java.io.File(context.getExternalFilesDir(null), "infos.json").writeText(json)
                        Log.i(TAG, "dumped ${json.length} chars")
                    }
                    "app.timiao.et.DEBUG_LIST" -> {
                        val all = container.configRepository.networks.first()
                        Log.i(TAG, "networks: " + all.joinToString { "${it.id}/${it.instanceName}/${it.networkName}" })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "debug action failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /** Accepts id, instance name, or network name. Defaults to the sample Home-Lab network. */
    private fun findConfig(intent: Intent, all: List<NetworkConfig>): NetworkConfig? =
        intent.getStringExtra("id")?.let { id -> all.firstOrNull { it.id == id } }
            ?: intent.getStringExtra("instance")?.let { name -> all.firstOrNull { it.instanceName == name } }
            ?: intent.getStringExtra("network")?.let { name -> all.firstOrNull { it.networkName == name } }
            ?: all.firstOrNull { it.instanceName == "Home-Lab" }

    private companion object {
        const val TAG = "TimiaoDebug"
        val EXTRAS = listOf("id", "instance", "network")
    }
}
