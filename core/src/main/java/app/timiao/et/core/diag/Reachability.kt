package app.timiao.et.core.diag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Outcome of probing one `[[peer]]` URI. */
public sealed interface PeerProbeResult {
    public data class Reachable(val latencyMs: Long) : PeerProbeResult
    public data class Unreachable(val reason: String) : PeerProbeResult
    /** The probe could not decide, e.g. plain UDP without a response. */
    public data class Inconclusive(val reason: String) : PeerProbeResult
}

/** Injectable so unit tests never touch the network. */
public fun interface PeerReachabilityProbe {
    public suspend fun probe(uri: String): PeerProbeResult
}

/**
 * TCP connect for tcp/ws/wss/http(s); one UDP packet for udp/wg/quic/faketcp.
 * Runs on [Dispatchers.IO] with a 3 s timeout.
 */
public object DefaultPeerReachabilityProbe : PeerReachabilityProbe {
    private const val TIMEOUT_MS = 3_000
    private val probePacket = byteArrayOf(0x45, 0x54, 0x51, 0x44) // "ETQD"

    override suspend fun probe(uri: String): PeerProbeResult = withContext(Dispatchers.IO) {
        when (val parsed = parsePeerUri(uri)) {
            is PeerUri.Invalid -> PeerProbeResult.Unreachable(parsed.reason)
            is PeerUri.Endpoint -> {
                val host = parsed.host
                val port = parsed.port
                if (host == null || port == null) {
                    PeerProbeResult.Inconclusive(parsed.note ?: "无法直接探测该地址")
                } else if (parsed.tcp) {
                    tcpProbe(host, port)
                } else {
                    udpProbe(host, port)
                }
            }
        }
    }

    private fun tcpProbe(host: String, port: Int): PeerProbeResult {
        val started = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
            PeerProbeResult.Reachable(elapsed)
        } catch (_: UnknownHostException) {
            PeerProbeResult.Unreachable("域名无法解析")
        } catch (_: SocketTimeoutException) {
            PeerProbeResult.Unreachable("连接超时（3 秒），服务器不可达或被防火墙丢弃")
        } catch (_: ConnectException) {
            PeerProbeResult.Unreachable("连接被拒绝，端口未开放或被防火墙拦截")
        } catch (_: NoRouteToHostException) {
            PeerProbeResult.Unreachable("主机不可达，地址或路由错误")
        } catch (e: IOException) {
            PeerProbeResult.Unreachable("网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            PeerProbeResult.Unreachable("没有网络访问权限：${e.message ?: "SecurityException"}")
        }
    }

    private fun udpProbe(host: String, port: Int): PeerProbeResult {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val address = InetAddress.getByName(host)
                socket.send(DatagramPacket(probePacket, probePacket.size, address, port))
                val buffer = ByteArray(64)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                PeerProbeResult.Reachable(0)
            }
        } catch (_: SocketTimeoutException) {
            PeerProbeResult.Inconclusive("已发送 UDP 探测包，但没有收到回应；UDP 无回应也可能正常")
        } catch (_: UnknownHostException) {
            PeerProbeResult.Unreachable("域名无法解析")
        } catch (e: IOException) {
            PeerProbeResult.Inconclusive("UDP 探测未能完成：${e.message ?: "网络错误"}")
        } catch (e: SecurityException) {
            PeerProbeResult.Unreachable("没有网络访问权限：${e.message ?: "SecurityException"}")
        }
    }
}
