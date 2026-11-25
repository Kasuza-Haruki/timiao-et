package app.timiao.et.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the network the user actually uses for internet.
 *
 * Reading the SSID needs ACCESS_FINE_LOCATION on Android 10+. When the
 * permission is missing, or the system redacts the name in the background, the
 * snapshot has `ssid = null` and SSID rules skip instead of guessing.
 */
object AutoNetwork {
    private const val TAG = "TimiaoAutoNet"
    private const val SSID_CACHE_TTL_MS = 5 * 60_000L

    @Volatile private var cachedSsid: String? = null
    @Volatile private var cachedAt: Long = 0L

    /**
     * Feeds SSID data from a callback registered with
     * [android.net.ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]
     * (API 31+). A plain getNetworkCapabilities() query can return a redacted name.
     */
    fun offerWifiCapabilities(caps: NetworkCapabilities?) {
        val info = caps
            ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.transportInfo as? WifiInfo else null }
            ?: return
        val ssid = info.ssid?.normalizeSsid()?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        if (ssid != null) {
            val changed = ssid != cachedSsid
            cachedSsid = ssid
            cachedAt = System.currentTimeMillis()
            if (changed) Log.d(TAG, "SSID cached")
        }
    }

    fun hasLocationPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun snapshot(ctx: Context): NetworkSnapshot {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return NetworkSnapshot()
        val locationGranted = hasLocationPermission(ctx)
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        // While this app's VPN is up the active network can be the VPN. The app is
        // added to the VPN's disallowed list, so prefer the physical network below.
        // During a Wi-Fi handover the default can still be cellular; prefer a
        // connected Wi-Fi as soon as one exists so the rule sees the change.
        val caps = when {
            activeCaps == null -> physicalCaps(cm)
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> physicalCaps(cm)
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                wifiCaps(cm) ?: activeCaps
            else -> activeCaps
        }

        if (caps == null) return NetworkSnapshot(locationGranted = locationGranted)

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.Cellular
            else -> NetType.Other
        }
        val ssid = if (type == NetType.Wifi && locationGranted) readSsid(ctx, caps) else null
        return NetworkSnapshot(type = type, ssid = ssid, locationGranted = locationGranted)
    }

    /** Validated Wi-Fi first, then any Wi-Fi, then cellular. */
    @Suppress("DEPRECATION")
    private fun physicalCaps(cm: ConnectivityManager): NetworkCapabilities? {
        val candidates = allCaps(cm)

        fun pick(transport: Int, validated: Boolean) = candidates.firstOrNull {
            it.hasTransport(transport) &&
                (!validated || it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        return pick(NetworkCapabilities.TRANSPORT_WIFI, true)
            ?: pick(NetworkCapabilities.TRANSPORT_WIFI, false)
            ?: pick(NetworkCapabilities.TRANSPORT_CELLULAR, true)
            ?: pick(NetworkCapabilities.TRANSPORT_CELLULAR, false)
            ?: candidates.firstOrNull()
    }

    private fun wifiCaps(cm: ConnectivityManager): NetworkCapabilities? = allCaps(cm).let { all ->
        all.firstOrNull {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: all.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    }

    @Suppress("DEPRECATION")
    private fun allCaps(cm: ConnectivityManager): List<NetworkCapabilities> = runCatching {
        cm.allNetworks.mapNotNull { network ->
            cm.getNetworkCapabilities(network)?.takeIf {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun readSsid(ctx: Context, caps: NetworkCapabilities): String? {
        val fromCaps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        val cached = cachedSsid
            ?.takeIf { System.currentTimeMillis() - cachedAt < SSID_CACHE_TTL_MS }
        val fromWifiManager = runCatching {
            ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }.getOrNull()
        val ssid = sequenceOf(fromCaps, cached, fromWifiManager)
            .mapNotNull { it?.normalizeSsid() }
            .firstOrNull { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
        return ssid
    }
}
