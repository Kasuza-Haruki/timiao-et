package app.timiao.et.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.timiao.et.MainActivity
import app.timiao.et.R
import app.timiao.et.TimiaoApplication
import app.timiao.et.core.LinkState
import app.timiao.et.service.ServiceState
import app.timiao.et.service.TimiaoVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget. Status comes from the same engine as [app.timiao.et.service.ConnectionTile].
 * A dead process has an empty status map, so the widget then shows "not connected".
 */
object TimiaoWidget {
    private const val PREFS = "widget_state"
    private const val NAME_PREFIX = "name_"
    private const val NAME_RETRY_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nameAttemptAt = mutableMapOf<String, Long>()

    @Volatile
    private var lastSignature: String? = null

    /** Updates every placed widget. [force] skips the "nothing changed" check. */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val ui = buildUi(app)
        // Text colours are baked into RemoteViews, so a light/dark switch needs a re-render.
        val night = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val signature = listOf(ui.networkId, ui.name, ui.statusText, ui.ip, ui.active, night)
            .joinToString("|")
        if (!force && signature == lastSignature) return
        lastSignature = signature

        val manager = AppWidgetManager.getInstance(app)
        val ids = runCatching {
            manager.getAppWidgetIds(ComponentName(app, TimiaoWidgetProvider::class.java))
        }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return

        val views = RemoteViews(app.packageName, R.layout.widget_main).apply {
            setTextViewText(R.id.widget_name, ui.name)
            setTextViewText(R.id.widget_status, ui.statusText)
            setTextViewText(R.id.widget_ip, ui.ip.orEmpty())
            setViewVisibility(R.id.widget_ip, if (ui.ip == null) View.GONE else View.VISIBLE)
            setTextColor(R.id.widget_status, ui.statusColor)
            setImageViewResource(
                R.id.widget_switch,
                if (ui.active) R.drawable.widget_switch_on else R.drawable.widget_switch_off,
            )
            setContentDescription(
                R.id.widget_switch,
                app.getString(
                    if (ui.active) R.string.widget_switch_desc_disconnect
                    else R.string.widget_switch_desc_connect,
                ),
            )
            setOnClickPendingIntent(R.id.widget_switch, toggleIntent(app))
            setOnClickPendingIntent(R.id.widget_root, openIntent(app))
        }
        runCatching { manager.updateAppWidget(ids, views) }
    }

    /** Same behaviour as the QS tile: disconnect while running, else connect the last network. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        val statuses = runCatching {
            (app as TimiaoApplication).container.engine.status.value
        }.getOrDefault(emptyMap())
        val running = statuses.values.any {
            it.state == LinkState.Connected || it.state == LinkState.Connecting
        }
        if (running) {
            TimiaoVpnService.stop(app)
            return
        }
        val id = ServiceState.last(app)
        val consentMissing = runCatching { VpnService.prepare(app) != null }.getOrDefault(true)
        if (id == null || consentMissing || !hasInternet(app)) {
            openApp(app)
            return
        }
        TimiaoVpnService.start(app, id)
    }

    /** Called when the last widget is removed, so a re-added widget renders from scratch. */
    internal fun forget() {
        lastSignature = null
    }

    private data class Ui(
        val networkId: String?,
        val name: String,
        val statusText: String,
        val ip: String?,
        val active: Boolean,
        val statusColor: Int,
    )

    private fun buildUi(context: Context): Ui {
        val statuses = runCatching {
            (context.applicationContext as TimiaoApplication).container.engine.status.value
        }.getOrDefault(emptyMap())
        val connected = statuses.entries.firstOrNull { it.value.state == LinkState.Connected }
        val connecting = statuses.entries.firstOrNull { it.value.state == LinkState.Connecting }
        val live = connected ?: connecting
        val id = live?.key ?: ServiceState.last(context)
        val cached = id?.let { cachedName(context, it) }
        if (id != null && cached == null) loadName(context, id)
        val status = live?.value
        val statusText = when {
            status == null -> context.getString(R.string.widget_not_connected)
            status.state == LinkState.Connected ->
                context.getString(R.string.widget_connected_nodes, status.peers.size)
            status.reconnecting -> context.getString(R.string.widget_reconnecting)
            else -> context.getString(R.string.widget_connecting)
        }
        val colorName = when {
            status == null -> R.color.widget_muted
            status.state == LinkState.Connected -> R.color.widget_ok
            else -> R.color.widget_warn
        }
        val ipv4 = status?.myIpv4
            ?.takeIf { status.state == LinkState.Connected && it.isNotBlank() }
        return Ui(
            networkId = id,
            name = cached ?: id ?: context.getString(R.string.widget_no_network),
            statusText = statusText,
            ip = ipv4,
            active = live != null,
            statusColor = ContextCompat.getColor(context, colorName),
        )
    }

    private fun cachedName(context: Context, id: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(NAME_PREFIX + id, null)
            ?.takeIf { it.isNotBlank() }

    /** Loads the display name once; the id is only a placeholder until the config is read. */
    private fun loadName(context: Context, id: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(nameAttemptAt) {
            val last = nameAttemptAt[id] ?: 0L
            if (now - last < NAME_RETRY_MS) return
            nameAttemptAt[id] = now
        }
        scope.launch {
            val app = context.applicationContext as? TimiaoApplication ?: return@launch
            val name = runCatching {
                app.container.configRepository.get(id)?.networkName?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: return@launch
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(NAME_PREFIX + id, name)
                .apply()
            refresh(app, force = true)
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun toggleIntent(context: Context): PendingIntent {
        val intent = Intent(context, TimiaoWidgetProvider::class.java)
            .setAction(TimiaoWidgetProvider.ACTION_TOGGLE)
        return PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
