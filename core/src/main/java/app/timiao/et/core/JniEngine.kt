package app.timiao.et.core

import com.easytier.jni.EasyTierJNI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Production [NativeBridge] backed by the EasyTier Android JNI library. */
public class JniNativeBridge : NativeBridge {
    override fun parseConfig(config: String): Int = EasyTierJNI.parseConfig(config)
    override fun runNetworkInstance(config: String): Int = EasyTierJNI.runNetworkInstance(config)
    override fun setTunFd(instanceName: String, fd: Int): Int = EasyTierJNI.setTunFd(instanceName, fd)
    override fun collectNetworkInfos(maxLength: Int): String? = EasyTierJNI.collectNetworkInfos(maxLength)
    override fun deleteNetworkInstance(instanceName: String): Int =
        EasyTierJNI.deleteNetworkInstance(instanceName)
    override fun getLastError(): String? = EasyTierJNI.getLastError()
}

/**
 * Starts/stops EasyTier instances through JNI and polls `collectNetworkInfos`
 * once per second to publish [NetworkStatus] values.
 */
public class JniEngine(
    private val configs: ConfigRepository,
    private val tunProvider: TunProvider,
    private val bridge: NativeBridge = JniNativeBridge(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 1_000L,
    private val maxDhcpAttempts: Int = 15,
    private val now: () -> Long = { System.currentTimeMillis() },
) : Engine, AutoCloseable {

    private class Running(val config: NetworkConfig, val startedAt: Long) {
        var previous: NetworkStatus? = null
        var previousAt: Long = startedAt
    }

    private val _status = MutableStateFlow<Map<String, NetworkStatus>>(emptyMap())
    override val status: StateFlow<Map<String, NetworkStatus>> = _status.asStateFlow()
    private val running = ConcurrentHashMap<String, Running>()
    private val jobs = ConcurrentHashMap<String, Job>()

    override suspend fun start(id: String) {
        val config = configs.get(id) ?: throw IllegalArgumentException("unknown network: $id")
        start(config)
    }

    public suspend fun start(config: NetworkConfig) {
        val key = config.id
        if (jobs.containsKey(key)) return
        var nativeStarted = false
        try {
            val toml = TomlCodec.exportToml(config)
            check(bridge.parseConfig(toml), "parseConfig")
            check(bridge.runNetworkInstance(toml), "runNetworkInstance")
            nativeStarted = true

            val (address, prefix) = resolveAddress(config)
            val routes = (config.proxyNetworks.map { it.cidr } + config.routes).distinct()
            val fd = tunProvider.establish(address, prefix, routes, config.flags.mtu)
            if (fd >= 0) check(bridge.setTunFd(config.instanceName, fd), "setTunFd")

            val startedAt = now()
            running[key] = Running(config, startedAt)
            _status.update {
                it + (
                    key to NetworkStatus(
                        state = LinkState.Connecting,
                        myIpv4 = "$address/$prefix",
                        peers = emptyList(),
                        txBps = 0,
                        rxBps = 0,
                        uptimeSec = 0,
                        error = null,
                    )
                    )
            }
            jobs[key] = scope.launch { poll(key) }
        } catch (e: Exception) {
            if (nativeStarted) runCatching { bridge.deleteNetworkInstance(config.instanceName) }
            _status.update {
                it + (
                    key to NetworkStatus(
                        state = LinkState.Error,
                        myIpv4 = null,
                        peers = emptyList(),
                        txBps = 0,
                        rxBps = 0,
                        uptimeSec = 0,
                        error = e.message,
                    )
                    )
            }
            throw e
        }
    }

    override suspend fun stop(id: String) {
        jobs.remove(id)?.cancelAndJoin()
        val instance = running.remove(id) ?: return
        // fd=0 makes the kernel drain and close the TUN it owns. Without it the system keeps
        // the VPN (and our service) alive after the instance is deleted.
        runCatching { bridge.setTunFd(instance.config.instanceName, 0) }
        delay(TUN_DRAIN_MS)
        runCatching { bridge.deleteNetworkInstance(instance.config.instanceName) }
        _status.update { current ->
            val base = current[id] ?: NetworkStatus(
                LinkState.Idle, null, emptyList(), 0, 0, 0, null,
            )
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

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        running.clear()
        scope.cancel()
    }

    private suspend fun resolveAddress(config: NetworkConfig): Pair<String, Int> {
        config.ipv4?.takeIf { it.isNotBlank() }?.let { return parseInet(it) }
        repeat(maxDhcpAttempts) {
            val json = bridge.collectNetworkInfos(MAX_INSTANCES)
            val ipv4 = json?.let { StatusParser.primaryIpv4(it, config.instanceName) }
            if (ipv4 != null) return parseInet(ipv4)
            delay(pollIntervalMs)
        }
        throw IllegalStateException("DHCP did not assign an IPv4 address in time")
    }

    private suspend fun poll(id: String) {
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            val instance = running[id] ?: return
            try {
                val json = bridge.collectNetworkInfos(MAX_INSTANCES) ?: continue
                val time = now()
                val uptime = ((time - instance.startedAt).coerceAtLeast(0) / 1000)
                val parsed = StatusParser.parse(json, instance.config.instanceName, uptime) ?: continue
                val withRates = StatusParser.applyRates(parsed, instance.previous, time - instance.previousAt)
                instance.previous = withRates
                instance.previousAt = time
                _status.update { it + (id to withRates) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { current ->
                    val base = current[id] ?: NetworkStatus(
                        LinkState.Error, null, emptyList(), 0, 0, 0, null,
                    )
                    current + (id to base.copy(state = LinkState.Error, error = e.message))
                }
            }
        }
    }

    private fun check(result: Int, operation: String) {
        if (result == 0) return
        val detail = bridge.getLastError()?.takeIf { it.isNotBlank() }
        throw IllegalStateException(if (detail != null) "$operation failed: $detail" else "$operation failed ($result)")
    }

    private fun parseInet(value: String): Pair<String, Int> {
        val parts = value.split('/', limit = 2)
        val address = parts[0].trim()
        require(address.isNotEmpty()) { "invalid IPv4 address: $value" }
        val prefix = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 24
        return address to prefix
    }

    private companion object {
        const val MAX_INSTANCES = 32
        const val TUN_DRAIN_MS = 300L
    }
}
