package app.timiao.et.service

import app.timiao.et.core.TunProvider

/** Bridges the engine to the running [TimiaoVpnService], which owns the TUN device. */
object VpnTunProvider : TunProvider {
    @Volatile
    var service: TimiaoVpnService? = null

    override suspend fun establish(addr: String, prefix: Int, routes: List<String>, mtu: Int): Int {
        val svc = service ?: error("VPN service is not running")
        return svc.establishTun(addr, prefix, routes, mtu)
    }
}
