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

/** Outcome of probing one `[[peer]]` URI. Reasons are text keys, resolved by the app. */
public sealed interface PeerProbeResult {
    public data class Reachable(val latencyMs: Long) : PeerProbeResult
    public data class Unreachable(
        val reasonKey: String,
        val args: List<String> = emptyList(),
    ) : PeerProbeResult
    /** The probe could not decide, e.g. plain UDP without a response. */
    public data class Inconclusive(
        val reasonKey: String,
        val args: List<String> = emptyList(),
    ) : PeerProbeResult
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
            is PeerUri.Invalid -> PeerProbeResult.Unreachable(parsed.reasonKey, parsed.args)
            is PeerUri.Endpoint -> {
                val host = parsed.host
                val port = parsed.port
                if (host == null || port == null) {
                    PeerProbeResult.Inconclusive(
                        parsed.noteKey ?: "diag_probe_cannot_probe",
                        parsed.noteArgs,
                    )
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
            PeerProbeResult.Unreachable("diag_probe_unknown_host")
        } catch (_: SocketTimeoutException) {
            PeerProbeResult.Unreachable("diag_probe_timeout")
        } catch (_: ConnectException) {
            PeerProbeResult.Unreachable("diag_probe_refused")
        } catch (_: NoRouteToHostException) {
            PeerProbeResult.Unreachable("diag_probe_no_route")
        } catch (e: IOException) {
            PeerProbeResult.Unreachable(
                "diag_probe_io",
                listOf(e.message ?: e.javaClass.simpleName),
            )
        } catch (e: SecurityException) {
            PeerProbeResult.Unreachable(
                "diag_probe_security",
                listOf(e.message ?: "SecurityException"),
            )
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
            PeerProbeResult.Inconclusive("diag_probe_udp_no_reply")
        } catch (_: UnknownHostException) {
            PeerProbeResult.Unreachable("diag_probe_unknown_host")
        } catch (e: IOException) {
            PeerProbeResult.Inconclusive(
                "diag_probe_udp_incomplete",
                listOf(e.message ?: "network error"),
            )
        } catch (e: SecurityException) {
            PeerProbeResult.Unreachable(
                "diag_probe_security",
                listOf(e.message ?: "SecurityException"),
            )
        }
    }
}
