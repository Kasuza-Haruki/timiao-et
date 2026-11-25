package app.timiao.et.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.timiao.et.MainActivity
import app.timiao.et.R
import app.timiao.et.TimiaoApplication
import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Owns the Android TUN device and the foreground notification. */
class TimiaoVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tun: ParcelFileDescriptor? = null
    private val activeIds = linkedSetOf<String>()

    private val engine get() = (application as TimiaoApplication).container.nativeEngine

    override fun onCreate() {
        super.onCreate()
        VpnTunProvider.service = this
        ensureChannel()
        startForegroundCompat(buildNotification(null, 0))
        scope.launch {
            engine.status.collectLatest { map ->
                val live = map.filterValues {
                    it.state == LinkState.Connected || it.state == LinkState.Connecting
                }
                notificationManager().notify(NOTIF_ID, buildNotification(live.values.firstOrNull(), live.size))
                if (live.isEmpty() && activeIds.isEmpty()) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                activeIds += id
                ServiceState.setLast(this, id)
                scope.launch {
                    runCatching { engine.start(id) }.onFailure { activeIds -= id }
                }
            }
            ACTION_STOP -> scope.launch {
                val ids = intent.getStringExtra(EXTRA_ID)?.let { listOf(it) } ?: activeIds.toList()
                ids.forEach { runCatching { engine.stop(it) }; activeIds -= it }
                if (activeIds.isEmpty()) {
                    closeTun()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Called by the engine once the address is known. Returns a detached fd owned by the native side. */
    fun establishTun(addr: String, prefix: Int, routes: List<String>, mtu: Int): Int {
        closeTun()
        val b = Builder()
            .setSession("Timiao")
            .setBlocking(false)
            .setMtu(if (mtu in 576..9000) mtu else 1300)
            .addAddress(addr, prefix)
        b.addRoute(networkAddress(addr, prefix), prefix)
        routes.forEach { cidr ->
            val p = cidr.split('/')
            runCatching { b.addRoute(p[0], p.getOrNull(1)?.toInt() ?: 32) }
        }
        runCatching { b.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val pfd = b.establish() ?: error("VPN permission was revoked")
        tun = pfd
        return pfd.detachFd()
    }

    private fun closeTun() {
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        scope.launch {
            activeIds.toList().forEach { runCatching { engine.stop(it) } }
            activeIds.clear()
            closeTun()
            stopSelf()
        }
    }

    override fun onDestroy() {
        VpnTunProvider.service = null
        closeTun()
        scope.cancel()
        super.onDestroy()
    }

    private fun networkAddress(addr: String, prefix: Int): String {
        val o = addr.split('.').map { it.toInt() }
        val ip = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val n = ip and mask
        return "${n ushr 24 and 255}.${n ushr 16 and 255}.${n ushr 8 and 255}.${n and 255}"
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
        val connected = s?.state == LinkState.Connected
        val text = if (connected) {
            "${s?.myIpv4 ?: ""} · ↑${rate(s?.txBps ?: 0)} ↓${rate(s?.rxBps ?: 0)} · ${s?.peers?.size ?: 0}"
        } else {
            getString(R.string.notif_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (connected) R.drawable.ic_stat_connected else R.drawable.ic_stat_idle)
            .setContentTitle(getString(if (connected) R.string.notif_connected else R.string.app_name))
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
