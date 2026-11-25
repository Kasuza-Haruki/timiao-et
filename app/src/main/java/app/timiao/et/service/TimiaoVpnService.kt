package app.timiao.et.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import app.timiao.et.MainActivity
import app.timiao.et.R
import app.timiao.et.TimiaoApplication
import app.timiao.et.core.LinkState
import app.timiao.et.core.MergedTun
import app.timiao.et.core.NetworkStatus
import app.timiao.et.split.SplitStore
import app.timiao.et.split.applyAppFilter
import app.timiao.et.core.TunMerge
import app.timiao.et.core.TunSpec
import app.timiao.et.traffic.TrafficDecision
import app.timiao.et.ui.screens.currentNetworkKind
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Owns the Android TUN device and the foreground notification. */
class TimiaoVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fdsLock = Any()

    /** fd number currently handed to the kernel. The kernel's shared NIC may keep it open after stop. */
    @Volatile private var kernelFd: Int = -1

    /** Replaced fds kept open until the engine confirms every instance uses the new one. */
    private val retiredFds = mutableListOf<Int>()
    private val activeIds = linkedSetOf<String>()
    private var splitObserver: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** Set by the first start/stop command so onCreate cannot stop the service too early. */
    @Volatile private var commandSeen = false

    private val engine get() = (application as TimiaoApplication).container.nativeEngine
    private val trafficRecorder get() = (application as TimiaoApplication).container.trafficRecorder

    /** Default-network listener that applies the mobile-data policy while a tunnel runs. */
    private var trafficCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        VpnTunProvider.service = this
        // The engine only reads this. VPN-transport networks are ignored so our own TUN
        // cannot make the device look online after the physical link is gone.
        engine.defaultNetworkAvailable = { hasPhysicalNetwork() }
        ensureChannel()
        startForegroundCompat(buildNotification(null, 0))
        registerTrafficPolicyCallback()
        // Rebuild the running TUN when the split policy changes, so the new app filter
        // takes effect immediately instead of on the next connect.
        splitObserver = SplitStore(this).observe {
            val running = synchronized(activeIds) { activeIds.isNotEmpty() }
            if (running) scope.launch { runCatching { engine.rebuildTun() } }
        }
        scope.launch {
            engine.status.collectLatest { map ->
                val live = map.filterValues {
                    it.state == LinkState.Connected || it.state == LinkState.Connecting
                }
                // Stop before notifying an empty state: a notify after stopForeground()
                // would leave a stale notification behind.
                if (live.isEmpty() && synchronized(activeIds) { activeIds.isEmpty() } && commandSeen) {
                    stopSelf()
                    return@collectLatest
                }
                // The service is still foreground here, so a plain notify() updates it.
                notificationManager().notify(NOTIF_ID, buildNotification(live.values.firstOrNull(), live.size))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandSeen = true
        Log.i(TAG, "command ${intent?.action} id=${intent?.getStringExtra(EXTRA_ID)} active=${synchronized(activeIds) { activeIds.toList() }}")
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                if (!allowTrafficStart()) {
                    if (synchronized(activeIds) { activeIds.isEmpty() }) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return START_NOT_STICKY
                }
                synchronized(activeIds) { activeIds += id }
                ServiceState.setLast(this, id)
                scope.launch {
                    runCatching { engine.start(id) }
                        .onFailure {
                            Log.w(TAG, "start $id failed: ${it.message}")
                            synchronized(activeIds) { activeIds -= id }
                        }
                }
            }
            ACTION_STOP -> scope.launch {
                val ids = synchronized(activeIds) {
                    intent.getStringExtra(EXTRA_ID)?.let { listOf(it) } ?: activeIds.toList()
                }
                ids.forEach { id ->
                    runCatching { engine.stop(id) }.onFailure { Log.w(TAG, "stop $id failed: ${it.message}") }
                    synchronized(activeIds) { activeIds -= id }
                }
                Log.i(TAG, "after stop active=${synchronized(activeIds) { activeIds.toList() }}")
                if (synchronized(activeIds) { activeIds.isEmpty() }) {
                    closeTun()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Builds one TUN that carries every running network. The previous fd stays open until the
     * engine calls [adoptKernelFd] and then [releaseKernelFd] for it.
     */
    fun establishMergedTun(merged: MergedTun): Int {
        require(merged.addresses.isNotEmpty()) { "TUN has no address" }
        val b = Builder()
            .setSession("Timiao")
            .setBlocking(false)
            .setMtu(merged.mtu)
            .allowFamily(android.system.OsConstants.AF_INET6)
        merged.addresses.forEach { b.addAddress(it.address, it.prefix) }
        merged.routes.forEach { b.addRoute(it.network, it.prefix) }
        merged.dns.forEach { b.addDnsServer(it) }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        applyAppFilter(b, SplitStore.policy(this, SplitStore.GLOBAL_ID), packageName)
        val pfd = b.establish() ?: error("VPN permission was revoked")
        return pfd.detachFd()
    }

    /** Single-network entry point kept for the plain TunProvider API. */
    fun establishTun(addr: String, prefix: Int, routes: List<String>, mtu: Int): Int =
        establishMergedTun(TunMerge.merge(listOf(TunSpec("", addr, prefix, routes, mtu))))

    fun adoptKernelFd(fd: Int) {
        if (fd < 0) return
        synchronized(fdsLock) {
            if (kernelFd != fd) {
                if (kernelFd >= 0) retiredFds += kernelFd
                kernelFd = fd
            }
            retiredFds.remove(fd)
        }
    }

    fun releaseKernelFd(fd: Int) {
        synchronized(fdsLock) {
            if (kernelFd == fd) kernelFd = -1
            retiredFds.remove(fd)
        }
        closeIfTun(fd)
    }

    fun closeTun() {
        val fds = synchronized(fdsLock) {
            val all = buildList {
                if (kernelFd >= 0) add(kernelFd)
                addAll(retiredFds)
            }
            kernelFd = -1
            retiredFds.clear()
            all
        }
        fds.forEach(::closeIfTun)
    }

    /** Closes [fd] only if that number still points at /dev/tun, so a reused fd is never touched. */
    private fun closeIfTun(fd: Int) {
        if (fd < 0) return
        val target = runCatching { android.system.Os.readlink("/proc/self/fd/$fd") }.getOrNull()
        if (target == "/dev/tun") {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            Log.i(TAG, "released kernel tun fd=$fd")
        }
    }

    override fun onRevoke() {
        scope.launch {
            val ids = synchronized(activeIds) { activeIds.toList() }
            ids.forEach { runCatching { engine.stop(it) } }
            synchronized(activeIds) { activeIds.clear() }
            closeTun()
            stopSelf()
        }
    }

    override fun onDestroy() {
        trafficCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }
        }
        trafficCallback = null
        VpnTunProvider.service = null
        splitObserver?.let { SplitStore(this).stopObserving(it) }
        splitObserver = null
        closeTun()
        scope.cancel()
        super.onDestroy()
    }

    /** True when the traffic policy allows a new tunnel on the current physical network. */
    private fun allowTrafficStart(): Boolean {
        val kind = currentNetworkKind(getSystemService(ConnectivityManager::class.java))
        return when (trafficRecorder.decision(kind)) {
            TrafficDecision.Block -> {
                notifyPolicyBlocked()
                false
            }
            TrafficDecision.Warn -> {
                notifyWarnOnce()
                true
            }
            TrafficDecision.Allow -> {
                // A previous "paused on mobile" notice is stale once a start is allowed.
                notificationManager().cancel(POLICY_NOTIF_ID)
                true
            }
        }
    }

    /** Applies an already-running tunnel's policy when the default network changes. */
    private fun registerTrafficPolicyCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { enforceTrafficPolicy() }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scope.launch { enforceTrafficPolicy() }
            }

            override fun onLost(network: Network) {
                scope.launch { enforceTrafficPolicy() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { trafficCallback = callback }
    }

    private suspend fun enforceTrafficPolicy() {
        val runningIds = synchronized(activeIds) { activeIds.toList() }
        if (runningIds.isEmpty()) return
        val kind = currentNetworkKind(getSystemService(ConnectivityManager::class.java))
        when (trafficRecorder.decision(kind)) {
            TrafficDecision.Block -> {
                runningIds.forEach { id ->
                    runCatching { engine.stop(id) }
                    synchronized(activeIds) { activeIds -= id }
                }
                closeTun()
                notifyPolicyBlocked()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            TrafficDecision.Warn -> notifyWarnOnce()
            TrafficDecision.Allow -> notificationManager().cancel(POLICY_NOTIF_ID)
        }
    }

    /** Warn at most once per calendar month, as the traffic screen promises. */
    private fun notifyWarnOnce() {
        val prefs = getSharedPreferences(POLICY_PREFS, MODE_PRIVATE)
        val month = YearMonth.now().toString()
        if (prefs.getString(KEY_WARN_MONTH, null) == month) return
        prefs.edit().putString(KEY_WARN_MONTH, month).apply()
        notifyPolicy(getString(R.string.vpn_policy_mobile_title), getString(R.string.vpn_policy_mobile_text))
    }

    private fun notifyPolicyBlocked() {
        notifyPolicy(getString(R.string.vpn_paused_title), getString(R.string.vpn_paused_text))
    }

    private fun notifyPolicy(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notificationManager().notify(POLICY_NOTIF_ID, notification)
    }

    /** True when a physical (non-VPN) network with INTERNET exists. */
    @Suppress("DEPRECATION")
    private fun hasPhysicalNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(true)
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(s: NetworkStatus?, count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TimiaoVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val reconnecting = s?.reconnecting == true
        val connected = s?.state == LinkState.Connected && !reconnecting
        val title = when {
            reconnecting -> getString(R.string.vpn_reconnecting)
            connected -> getString(R.string.notif_connected)
            else -> getString(R.string.app_name)
        }
        val text = when {
            reconnecting -> getString(R.string.vpn_reconnecting)
            connected -> "${s?.myIpv4 ?: ""} · ↑${rate(s?.txBps ?: 0)} ↓${rate(s?.rxBps ?: 0)} · ${s?.peers?.size ?: 0}"
            else -> getString(R.string.notif_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (connected) R.drawable.ic_stat_connected else R.drawable.ic_stat_idle)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .build()
    }

    private fun rate(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_START = "app.timiao.et.START"
        const val ACTION_STOP = "app.timiao.et.STOP"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "connection"
        private const val NOTIF_ID = 1
        private const val POLICY_NOTIF_ID = 2
        private const val POLICY_PREFS = "traffic_policy"
        private const val KEY_WARN_MONTH = "warn_month"
        private const val TAG = "TimiaoVpn"

        /** Intent for the system VPN consent dialog, or null when consent is already granted. */
        fun prepareIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

        fun start(ctx: Context, id: String) {
            ctx.startForegroundService(
                Intent(ctx, TimiaoVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id),
            )
        }

        fun stop(ctx: Context, id: String? = null) {
            ctx.startService(
                Intent(ctx, TimiaoVpnService::class.java).setAction(ACTION_STOP)
                    .apply { id?.let { putExtra(EXTRA_ID, it) } },
            )
        }
    }
}
