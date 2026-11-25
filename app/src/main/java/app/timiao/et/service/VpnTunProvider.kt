package app.timiao.et.service

import app.timiao.et.core.MergedTun
import app.timiao.et.core.MultiTunProvider
import app.timiao.et.core.TunMerge
import app.timiao.et.core.TunSpec
import android.util.Log

/** Bridges the engine to the running [TimiaoVpnService], which owns the TUN device. */
object VpnTunProvider : MultiTunProvider {
    @Volatile
    var service: TimiaoVpnService? = null

    override suspend fun establish(addr: String, prefix: Int, routes: List<String>, mtu: Int): Int {
        val svc = service ?: error("VPN service is not running")
        return svc.establishMergedTun(
            TunMerge.merge(listOf(TunSpec("", addr, prefix, routes, mtu))),
        )
    }

    override suspend fun establishMerged(tun: MergedTun): Int {
        val svc = service ?: error("VPN service is not running")
        return try {
            svc.establishMergedTun(tun)
        } catch (e: Exception) {
            Log.e(TAG, "establish failed for ${tun.addresses}", e)
            throw e
        }
    }

    override fun adopt(fd: Int) {
        Log.i(TAG, "adopt fd=$fd")
        service?.adoptKernelFd(fd)
    }

    override fun release(fd: Int) {
        Log.i(TAG, "release fd=$fd")
        service?.releaseKernelFd(fd)
    }

    override fun closeTun() {
        Log.i(TAG, "close tun")
        service?.closeTun()
    }

    private const val TAG = "TimiaoTun"
}
