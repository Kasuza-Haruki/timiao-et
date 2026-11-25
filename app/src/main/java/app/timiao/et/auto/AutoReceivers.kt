package app.timiao.et.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Re-arms the network callback after reboot and applies the rules once the
 * network stack has settled. Boot broadcasts are exempt from the Android 12
 * foreground-service start restriction.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val store = AutoRulesStore.get(context)
        if (!store.state.value.enabled) {
            Log.i(TAG, "boot: auto disabled")
            return
        }
        val pending = goAsync()
        receiverScope.launch {
            try {
                AutoConnect.ensureRegistered(context)
                AutoConnect.ensureInProcess(context)
                // ConnectivityManager usually has a default network a few seconds after boot.
                delay(BOOT_SETTLE_MS)
                AutoConnect.evaluateAndApply(context)
            } catch (e: Exception) {
                Log.w(TAG, "boot auto-connect failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TimiaoAuto"
        const val BOOT_SETTLE_MS = 3_000L
    }
}

/**
 * Receives the PendingIntent network callbacks. Registered by [AutoConnect] with
 * an explicit component, so it does not need to be exported.
 */
class AutoEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "network event action=${intent.action} extras=${intent.extras?.keySet()}")
        // The callback itself proves the system registration is alive; no re-arm here.
        // The process is now awake (the VPN service runs in it), so also register the
        // in-process callback that sees transport changes the PendingIntent misses.
        AutoConnect.ensureInProcess(context)
        // The default network can lag the available callback by a moment, so let the
        // handover settle before reading the current transport.
        val pending = goAsync()
        receiverScope.launch {
            try {
                delay(EVENT_SETTLE_MS)
                AutoConnect.evaluateAndApply(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TimiaoAuto"
        const val EVENT_SETTLE_MS = 1_500L
    }
}
