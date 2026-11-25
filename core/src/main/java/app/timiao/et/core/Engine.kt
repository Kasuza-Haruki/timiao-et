package app.timiao.et.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public interface Engine {
    public val status: StateFlow<Map<String, NetworkStatus>>
    public suspend fun start(id: String)
    public suspend fun stop(id: String)
}

/** Supplies the TUN file descriptor to the native engine. Implemented in the service layer. */
public interface TunProvider {
    public suspend fun establish(addr: String, prefix: Int, routes: List<String>, mtu: Int): Int
}

/** All JNI calls behind one seam so JVM unit tests can use a fake. */
public interface NativeBridge {
    public fun parseConfig(config: String): Int
    public fun runNetworkInstance(config: String): Int
    public fun setTunFd(instanceName: String, fd: Int): Int
    public fun collectNetworkInfos(maxLength: Int): String?
    public fun deleteNetworkInstance(instanceName: String): Int
    public fun getLastError(): String?
}

/** In-memory engine for Compose previews. */
public class FakeEngine(
    initial: Map<String, NetworkStatus> = mapOf(DEFAULT_ID to sampleStatus()),
) : Engine {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<Map<String, NetworkStatus>> = _status.asStateFlow()

    override suspend fun start(id: String) {
        _status.update { current ->
            val base = current[id] ?: sampleStatus()
            current + (id to base.copy(state = LinkState.Connected, error = null))
        }
    }

    override suspend fun stop(id: String) {
        _status.update { current ->
            val base = current[id] ?: sampleStatus()
            current + (
                id to base.copy(
                    state = LinkState.Idle,
                    peers = emptyList(),
                    txBps = 0,
                    rxBps = 0,
                    error = null,
                )
                )
        }
    }

    public fun setStatus(id: String, status: NetworkStatus) {
        _status.update { it + (id to status) }
    }

    public companion object {
        public const val DEFAULT_ID: String = "home"

        public fun sampleStatus(): NetworkStatus = NetworkStatus(
            state = LinkState.Connected,
            myIpv4 = "10.144.144.10/24",
            peers = listOf(
                PeerInfo(
                    hostname = "nas-home",
                    ipv4 = "10.144.144.2/24",
                    cost = "p2p",
                    tunnel = "udp",
                    latencyMs = 8.0,
                    lossRate = 0.0,
                    natType = "FullCone",
                    txBytes = 1_024_000,
                    rxBytes = 2_048_000,
                    version = "2.7.1",
                ),
                PeerInfo(
                    hostname = "laptop",
                    ipv4 = "10.144.144.3/24",
                    cost = "p2p",
                    tunnel = "udp",
                    latencyMs = 17.0,
                    lossRate = 0.01,
                    natType = "PortRestricted",
                    txBytes = 512_000,
                    rxBytes = 768_000,
                    version = "2.7.1",
                ),
                PeerInfo(
                    hostname = "office-pc",
                    ipv4 = "10.144.144.4/24",
                    cost = "p2p",
                    tunnel = "tcp",
                    latencyMs = 24.0,
                    lossRate = 0.0,
                    natType = "NoPAT",
                    txBytes = 256_000,
                    rxBytes = 384_000,
                    version = "2.7.1",
                ),
                PeerInfo(
                    hostname = "relay-hk",
                    ipv4 = "10.144.144.5/24",
                    cost = "relay(2)",
                    tunnel = "tcp",
                    latencyMs = 62.0,
                    lossRate = 0.02,
                    natType = "Symmetric",
                    txBytes = 128_000,
                    rxBytes = 192_000,
                    version = "2.7.1",
                ),
            ),
            txBps = 18_400,
            rxBps = 42_100,
            uptimeSec = 3_600,
            error = null,
        )
    }
}
