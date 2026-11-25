package app.timiao.et.auto

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.timiao.et.MainActivity
import app.timiao.et.R
import app.timiao.et.service.ServiceState
import app.timiao.et.service.TimiaoVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runtime glue for automatic connect/disconnect.
 *
 * Two event sources, because neither is enough alone:
 * - In-process callbacks (default network, Wi-Fi, cellular) do the real work while
 *   the process lives, which includes the whole time the VPN service runs.
 * - PendingIntent callback registrations are best effort. On Android 15 they are
 *   released when the process dies (verified on the emulator), so they are re-armed
 *   at boot, on app start, and whenever a receiver wakes the process.
 *
 * Android 12+ blocks a background app from starting a foreground service. Connect
 * decisions that the OS blocks fall back to a notification that opens the app.
 */
object AutoConnect {
    private const val TAG = "TimiaoAuto"
    private const val START_GRACE_MS = 10_000L
    private const val NOTIFY_GAP_MS = 30_000L
    private const val EVENT_SETTLE_MS = 1_500L

    private const val CHANNEL = "auto_rules"
    private const val NOTIF_CONSENT = 20
    private const val NOTIF_BLOCKED = 21
    private const val NOTIF_DISCONNECTED = 22
    private const val REQUEST_WIFI = 0x51
    private const val REQUEST_CELLULAR = 0x52

    @Volatile private var registered = false
    private var inProcessCallback: ConnectivityManager.NetworkCallback? = null
    private var inProcessWifiCallback: ConnectivityManager.NetworkCallback? = null
    private var inProcessCellCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingEvaluation: Job? = null
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureRegistered(context: Context) {
        if (!AutoRulesStore.get(context).state.value.enabled) return
        if (registered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // One request per transport: a shared "INTERNET" request stays satisfied by
        // cellular and would never report a newly available Wi-Fi network.
        val registrations = listOf(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build() to callbackIntent(context, REQUEST_WIFI),
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build() to callbackIntent(context, REQUEST_CELLULAR),
        )
        // Re-arm replaces stale registrations from a previous process.
        registrations.forEach { (_, pending) -> runCatching { cm.unregisterNetworkCallback(pending) } }
        var any = false
        registrations.forEach { (request, pending) ->
            runCatching { cm.registerNetworkCallback(request, pending) }
                .onSuccess {
                    any = true
                    Log.i(TAG, "network callback registered")
                }
                .onFailure { Log.w(TAG, "network callback registration failed", it) }
        }
        registered = any
    }

    fun unregister(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        listOf(REQUEST_WIFI, REQUEST_CELLULAR).forEach { code ->
            runCatching { cm.unregisterNetworkCallback(callbackIntent(context, code)) }
        }
        registered = false
        Log.i(TAG, "network callback unregistered")
    }

    /**
     * While the process lives (which includes the whole time the VPN service runs)
     * a regular default-network callback reacts immediately to Wi-Fi/cellular
     * handovers. This covers the case the PendingIntent requests miss: with the VPN
     * up, transport changes can arrive as capabilities changes, not available/lost.
     */
    fun ensureInProcess(context: Context) {
        if (!AutoRulesStore.get(context).state.value.enabled) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val app = context.applicationContext
        if (inProcessCallback == null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = evaluateSoon(app)
                override fun onLost(network: Network) = evaluateSoon(app)
            }
            runCatching { cm.registerDefaultNetworkCallback(callback) }
                .onSuccess {
                    inProcessCallback = callback
                    Log.i(TAG, "in-process default callback registered")
                }
                .onFailure { Log.w(TAG, "in-process callback registration failed", it) }
        }

        // In-process transport callbacks are the reliable signal while the VPN is up:
        // the PendingIntent requests can stay silent once the VPN owns the default.
        if (inProcessWifiCallback == null) {
            fun wifiAvailable(network: Network) {
                AutoNetwork.offerWifiCapabilities(cm.getNetworkCapabilities(network))
                evaluateSoon(app)
            }

            // API 31+: ask callbacks to include location-sensitive WifiInfo so the
            // SSID can be matched while the process is alive.
            val wifiCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : ConnectivityManager.NetworkCallback(
                    ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
                ) {
                    override fun onAvailable(network: Network) = wifiAvailable(network)
                    override fun onLost(network: Network) = evaluateSoon(app)
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        AutoNetwork.offerWifiCapabilities(networkCapabilities)
                    }
                }
            } else {
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = wifiAvailable(network)
                    override fun onLost(network: Network) = evaluateSoon(app)
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        AutoNetwork.offerWifiCapabilities(networkCapabilities)
                    }
                }
            }
            val wifiRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching {
                cm.registerNetworkCallback(wifiRequest, wifiCallback)
            }
                .onSuccess {
                    inProcessWifiCallback = wifiCallback
                    Log.i(TAG, "in-process wifi callback registered")
                }
                .onFailure { Log.w(TAG, "in-process wifi callback failed", it) }
        }

        if (inProcessCellCallback == null) {
            val cellCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = evaluateSoon(app)
                override fun onLost(network: Network) = evaluateSoon(app)
            }
            val cellRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            runCatching {
                cm.registerNetworkCallback(cellRequest, cellCallback)
            }
                .onSuccess {
                    inProcessCellCallback = cellCallback
                    Log.i(TAG, "in-process cellular callback registered")
                }
                .onFailure { Log.w(TAG, "in-process cellular callback failed", it) }
        }
    }

    fun unregisterInProcess(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        inProcessCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        inProcessWifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        inProcessCellCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        inProcessCallback = null
        inProcessWifiCallback = null
        inProcessCellCallback = null
    }

    /** Coalesces a burst of callbacks into one evaluation after the handover settles. */
    fun evaluateSoon(context: Context) {
        pendingEvaluation?.cancel()
        pendingEvaluation = processScope.launch {
            delay(EVENT_SETTLE_MS)
            evaluateAndApply(context.applicationContext)
        }
    }

    fun evaluateNow(context: Context): AutoDecision {
        val state = AutoRulesStore.get(context).state.value
        return evaluate(AutoNetwork.snapshot(context), state.rules)
    }

    /** Called by boot and network-event receivers. */
    fun evaluateAndApply(context: Context) {
        val store = AutoRulesStore.get(context)
        val rules = store.state.value
        if (!rules.enabled) {
            Log.i(TAG, "auto disabled, skip")
            return
        }
        val snapshot = AutoNetwork.snapshot(context)
        val decision = evaluate(snapshot, rules.rules)
        Log.i(TAG, "snapshot=$snapshot decision=$decision")
        when (decision) {
            is AutoDecision.Connect -> applyConnect(context, decision)
            is AutoDecision.Disconnect -> applyDisconnect(context, decision.rule)
            is AutoDecision.Skip -> clearStaleStart(context)
        }
    }

    /** Turns auto on: register the callback and act on the current network now. */
    fun enable(context: Context) {
        AutoRulesStore.get(context).setEnabled(true)
        ensureRegistered(context)
        ensureInProcess(context)
        evaluateAndApply(context)
    }

    fun disable(context: Context) {
        AutoRulesStore.get(context).setEnabled(false)
        unregister(context)
        unregisterInProcess(context)
    }

    private fun applyConnect(context: Context, decision: AutoDecision.Connect) {
        val target = decision.networkId ?: globalTarget(context)
        if (target == null) {
            Log.w(TAG, "connect decision has no target network")
            notifyOnce(context, "no-target", NOTIF_BLOCKED, context.getString(R.string.auto_notif_blocked_title), context.getString(R.string.auto_notif_blocked_text))
            return
        }
        if (TimiaoVpnService.prepareIntent(context) != null) {
            notifyOnce(
                context, "consent", NOTIF_CONSENT,
                context.getString(R.string.auto_notif_vpn_title),
                context.getString(R.string.auto_notif_vpn_text),
            )
            return
        }

        val running = VpnRuntime.isRunning(context)
        val started = AutoState.startedId(context)
        if (started == target && (running || AutoState.withinGrace(context, START_GRACE_MS))) {
            AutoState.markTouched(context)
            return
        }

        if (running && started != null && started != target) {
            Log.i(TAG, "switching auto network $started -> $target")
            runCatching { TimiaoVpnService.stop(context, started) }
        }

        try {
            TimiaoVpnService.start(context, target)
            AutoState.setStarted(context, target)
            AutoState.cancel(context, "consent")
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_CONSENT) }
            Log.i(TAG, "started $target")
        } catch (e: Exception) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12+.
            Log.w(TAG, "background start blocked", e)
            notifyOnce(
                context, "blocked", NOTIF_BLOCKED,
                context.getString(R.string.auto_notif_waiting_title),
                context.getString(R.string.auto_notif_waiting_text),
            )
        }
    }

    private fun applyDisconnect(context: Context, rule: AutoRule) {
        val started = AutoState.startedId(context) ?: return
        val running = VpnRuntime.isRunning(context)
        if (!running) {
            if (!AutoState.withinGrace(context, START_GRACE_MS)) AutoState.clear(context)
            return
        }
        try {
            TimiaoVpnService.stop(context, started)
            AutoState.clear(context)
            Log.i(TAG, "stopped $started")
            if (rule.keepNotification) {
                notifyOnce(
                    context, "disconnected", NOTIF_DISCONNECTED,
                    context.getString(R.string.auto_notif_disconnected_title),
                    context.getString(R.string.auto_notif_disconnected_text),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "background stop blocked", e)
            notifyOnce(
                context, "blocked", NOTIF_BLOCKED,
                context.getString(R.string.auto_notif_disconnect_waiting_title),
                context.getString(R.string.auto_notif_disconnect_waiting_text),
            )
        }
    }

    private fun clearStaleStart(context: Context) {
        if (AutoState.startedId(context) != null &&
            !VpnRuntime.isRunning(context) &&
            !AutoState.withinGrace(context, START_GRACE_MS)
        ) {
            AutoState.clear(context)
        }
    }

    /** Global rules connect the last network the user used. */
    private fun globalTarget(context: Context): String? =
        ServiceState.last(context)
            ?: context.getSharedPreferences("timiao_settings", Context.MODE_PRIVATE)
                .getString("active_network", null)

    private fun callbackIntent(context: Context, requestCode: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AutoEventReceiver::class.java),
            flags,
        )
    }

    private fun notifyOnce(
        context: Context,
        key: String,
        id: Int,
        title: String,
        text: String,
    ) {
        if (!AutoState.shouldNotify(context, key, NOTIFY_GAP_MS)) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.auto_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            val open = PendingIntent.getActivity(
                context, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_idle)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
        }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }
}

/** Process-local cache of what auto started, persisted so receivers agree after a restart. */
internal object AutoState {
    fun startedId(context: Context): String? = prefs(context).getString("started_id", null)

    fun setStarted(context: Context, id: String) {
        prefs(context).edit()
            .putString("started_id", id)
            .putLong("started_at", System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove("started_id").remove("started_at").apply()
    }

    fun withinGrace(context: Context, graceMs: Long): Boolean {
        val at = prefs(context).getLong("started_at", 0L)
        return at > 0 && System.currentTimeMillis() - at < graceMs
    }

    fun markTouched(context: Context) {
        prefs(context).edit().putLong("started_at", System.currentTimeMillis()).apply()
    }

    fun shouldNotify(context: Context, key: String, gapMs: Long): Boolean {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("notify_$key", 0L) < gapMs) return false
        prefs.edit().putLong("notify_$key", now).apply()
        return true
    }

    fun cancel(context: Context, key: String) {
        prefs(context).edit().remove("notify_$key").apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("auto_state", Context.MODE_PRIVATE)
}

internal object VpnRuntime {
    /** getRunningServices is deprecated but still reports the caller's own services. */
    @Suppress("DEPRECATION")
    fun isRunning(context: Context): Boolean = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TimiaoVpnService::class.java.name
        }
    }.getOrDefault(false)
}
