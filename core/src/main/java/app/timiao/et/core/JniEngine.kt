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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** One network's share of the shared TUN device. */
public data class TunSpec(
    public val instanceName: String,
    public val address: String,
    public val prefix: Int,
    public val routes: List<String> = emptyList(),
    public val mtu: Int = TunMerge.DEFAULT_MTU,
    /** Static IPv6 CIDRs; host bits are kept for addAddress. */
    public val ipv6Addresses: List<String> = emptyList(),
    /** Proxy CIDRs reported by peers at runtime. */
    public val runtimeRoutes: List<String> = emptyList(),
    /** The kernel runs a magic DNS server for this network. */
    public val magicDns: Boolean = false,
)

public data class TunAddress(public val address: String, public val prefix: Int)
public data class TunRoute(public val network: String, public val prefix: Int)
public data class MergedTun(
    public val addresses: List<TunAddress>,
    public val routes: List<TunRoute>,
    public val mtu: Int,
    public val dns: List<String> = emptyList(),
)

/**
 * A [TunProvider] that can build one TUN for several networks. The kernel shares a single
 * raw fd between all running instances and replaces it with [adopt].
 */
public interface MultiTunProvider : TunProvider {
    public suspend fun establishMerged(tun: MergedTun): Int

    /** Makes [fd] the current kernel fd. Older fds are kept until [release]. */
    public fun adopt(fd: Int)

    /** Closes [fd] when it still points at /dev/tun. */
    public fun release(fd: Int)

    /** Closes the TUN when no running network needs it, but the service stays alive. */
    public fun closeTun()
}

/** Pure merge of several networks onto one TUN. Exact duplicate routes are removed. */
public object TunMerge {
    public const val DEFAULT_MTU: Int = 1300
    public const val MAGIC_DNS: String = "100.100.100.101"

    public fun merge(specs: Collection<TunSpec>): MergedTun {
        val addresses = LinkedHashMap<String, TunAddress>()
        val routes = LinkedHashMap<String, TunRoute>()
        val dns = LinkedHashSet<String>()
        for (spec in specs) {
            addresses.putIfAbsent(
                "${spec.address}/${spec.prefix}",
                TunAddress(spec.address, spec.prefix),
            )
            parseCidr("${spec.address}/${spec.prefix}")?.let { (network, prefix) ->
                routes.putIfAbsent("$network/$prefix", TunRoute(network, prefix))
            }
            for (cidr in spec.ipv6Addresses) {
                val (address, prefix) = parseInet(cidr) ?: continue
                addresses.putIfAbsent("$address/$prefix", TunAddress(address, prefix))
                parseCidr(cidr)?.let { (network, networkPrefix) ->
                    routes.putIfAbsent(
                        "$network/$networkPrefix",
                        TunRoute(network, networkPrefix),
                    )
                }
            }
            for (cidr in spec.routes + spec.runtimeRoutes) {
                parseCidr(cidr)?.let { (network, prefix) ->
                    routes.putIfAbsent("$network/$prefix", TunRoute(network, prefix))
                }
            }
            if (spec.magicDns) {
                dns += MAGIC_DNS
                routes.putIfAbsent("$MAGIC_DNS/32", TunRoute(MAGIC_DNS, 32))
            }
        }
        val mtu = specs.minOfOrNull { it.mtu }?.coerceIn(576, 9000) ?: DEFAULT_MTU
        return MergedTun(addresses.values.toList(), routes.values.toList(), mtu, dns.toList())
    }

    /** Returns the address and prefix for an IPv4 or IPv6 CIDR, or null when invalid. */
    public fun parseInet(cidr: String): Pair<String, Int>? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null
        val address = parts[0].trim()
        val prefix = parts[1].trim().toIntOrNull() ?: return null
        return when (family(address)) {
            4 -> if (prefix in 0..32) address to prefix else null
            6 -> if (prefix in 0..128) address to prefix else null
            else -> null
        }
    }

    /** Returns the network address and prefix, or null when [cidr] is not a CIDR. */
    public fun parseCidr(cidr: String): Pair<String, Int>? {
        val (address, prefix) = parseInet(cidr) ?: return null
        return normalizedRoute(address, prefix)
    }

    private fun family(address: String): Int {
        if (address.contains(':')) {
            val valid = address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            return if (valid) 6 else 0
        }
        val octets = address.split('.')
        if (octets.size != 4) return 0
        if (octets.any { it.toIntOrNull()?.let { v -> v in 0..255 } != true }) return 0
        return 4
    }

    private fun normalizedRoute(address: String, prefix: Int): Pair<String, Int>? {
        return when (family(address)) {
            4 -> {
                val values = address.split('.').map { it.toInt() }
                val ip = (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
                val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
                val network = ip and mask
                "${network ushr 24 and 255}.${network ushr 16 and 255}.${network ushr 8 and 255}.${network and 255}" to prefix
            }
            6 -> {
                val bytes = InetAddress.getByName(address).address
                if (bytes.size != 16) return null
                val masked = ByteArray(bytes.size)
                var bits = prefix
                for (i in bytes.indices) {
                    val take = bits.coerceIn(0, 8)
                    masked[i] = (bytes[i].toInt() and (if (take == 0) 0 else (0xFF shl (8 - take)) and 0xFF)).toByte()
                    bits -= take
                }
                InetAddress.getByAddress(masked).hostAddress to prefix
            }
            else -> null
        }
    }

    /** Peer proxy CIDRs in one instance from `collectNetworkInfos` JSON. */
    public fun peerProxyRoutes(json: String, instanceName: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val instance = root[instanceName] as? JsonObject ?: return emptyList()
        val pairs = instance["peer_route_pairs"] as? JsonArray ?: return emptyList()
        val routes = LinkedHashSet<String>()
        for (pair in pairs) {
            val route = (pair as? JsonObject)?.get("route") as? JsonObject ?: continue
            val cidrs = route["proxy_cidrs"] as? JsonArray ?: continue
            for (cidr in cidrs) {
                when (cidr) {
                    is JsonPrimitive -> cidr.content.takeIf { it.isNotBlank() }?.let(routes::add)
                    is JsonObject -> (cidr["cidr"] as? JsonPrimitive)?.content?.let(routes::add)
                    else -> Unit
                }
            }
        }
        return routes.sorted()
    }
}

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
    /** Public-relay peers can need 20-40 s before they hand out a DHCP address. */
    private val maxDhcpAttempts: Int = 40,
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
    /** Ids whose start sequence is in flight; a second start must not race the first. */
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val multiTun: MultiTunProvider? = tunProvider as? MultiTunProvider
    private val tunMutex = Mutex()
    private val specs = LinkedHashMap<String, TunSpec>()
    private var tunFd: Int = -1

    override suspend fun start(id: String) {
        val config = configs.get(id) ?: throw IllegalArgumentException("unknown network: $id")
        start(config)
    }

    public suspend fun start(config: NetworkConfig) {
        val key = config.id
        if (jobs.containsKey(key) || running.containsKey(key)) return
        // A duplicate start intent (for example a redelivered broadcast) must not run a
        // second parse/run sequence on the same kernel instance name.
        if (!starting.add(key)) return
        try {
            startInternal(config)
        } finally {
            starting.remove(key)
        }
    }

    private suspend fun startInternal(config: NetworkConfig) {
        val key = config.id
        var nativeStarted = false
        try {
            val toml = TomlCodec.exportToml(config)
            check(bridge.parseConfig(toml), "parseConfig")
            check(bridge.runNetworkInstance(toml), "runNetworkInstance")
            nativeStarted = true

            val noTun = config.flags.noTun
            val addressInfo = when {
                noTun -> null
                config.dhcp -> try {
                    resolveAddress(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalStateException) {
                    // DHCP may arrive after this attempt; poll creates the TUN when it does.
                    null
                }
                else -> resolveAddress(config)
            }
            if (!noTun && addressInfo != null) {
                val (address, prefix) = addressInfo!!
                val spec = specFor(config, address, prefix)
                tunMutex.withLock {
                    specs[key] = spec
                    try {
                        rebuildTunLocked(requiredInstance = config.instanceName)
                    } catch (e: Exception) {
                        specs.remove(key)
                        // Android replaces the previous interface on establish, so keep the
                        // remaining networks on a TUN that excludes the failed network.
                        if (specs.isNotEmpty()) runCatching { rebuildTunLocked(requiredInstance = null) }
                        throw e
                    }
                }
            }

            val startedAt = now()
            running[key] = Running(config, startedAt)
            _status.update {
                it + (
                    key to NetworkStatus(
                        state = LinkState.Connecting,
                        myIpv4 = addressInfo?.let { "${it.first}/${it.second}" }
                            ?: config.ipv4?.takeIf { it.isNotBlank() },
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
        if (!instance.config.flags.noTun) {
            // fd=0 makes the kernel drain and close the TUN it owns. Without it the system keeps
            // the VPN (and our service) alive after the instance is deleted.
            runCatching { bridge.setTunFd(instance.config.instanceName, 0) }
            delay(TUN_DRAIN_MS)
        }
        runCatching { bridge.deleteNetworkInstance(instance.config.instanceName) }
        tunMutex.withLock {
            if (specs.remove(id) != null) {
                if (specs.isEmpty()) {
                    // Keep the service alive for no_tun networks, but drop the unused TUN.
                    if (tunFd >= 0) multiTun?.closeTun()
                    tunFd = -1
                } else {
                    // Remaining networks keep running on a TUN that no longer carries the stopped routes.
                    runCatching { rebuildTunLocked(requiredInstance = null) }
                }
            }
        }
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

    /**
     * Rebuilds the shared TUN from the current specs. The service calls this when the
     * per-app split policy changes, so a change applies without a reconnect.
     */
    public suspend fun rebuildTun() {
        tunMutex.withLock {
            if (specs.isEmpty()) return
            // The old fd stays current when establish fails; the next rebuild retries.
            runCatching { rebuildTunLocked(requiredInstance = null) }
        }
    }

    /** Reconciles address and peer proxy routes reported by the kernel into the TUN spec. */
    private suspend fun reconcileSpec(id: String, reportedIp: String?, runtimeRoutes: List<String>) {
        val config = running[id]?.config ?: return
        tunMutex.withLock {
            val spec = specs[id]
            if (spec == null) {
                // A DHCP address can arrive after the start attempt timed out.
                val (address, prefix) = reportedIp?.let { runCatching { parseInet(it) }.getOrNull() }
                    ?: return@withLock
                specs[id] = specFor(config, address, prefix)
                try {
                    rebuildTunLocked(requiredInstance = config.instanceName)
                } catch (e: Exception) {
                    specs.remove(id)
                    throw e
                }
                return@withLock
            }
            var next = spec
            if (reportedIp != null) {
                val (address, prefix) = runCatching { parseInet(reportedIp) }.getOrNull()
                    ?: return@withLock
                if (address != spec.address || prefix != spec.prefix) {
                    next = next.copy(address = address, prefix = prefix)
                }
            }
            if (runtimeRoutes != spec.runtimeRoutes) {
                next = next.copy(runtimeRoutes = runtimeRoutes)
            }
            if (next == spec) return@withLock
            specs[id] = next
            try {
                rebuildTunLocked(requiredInstance = spec.instanceName)
            } catch (e: Exception) {
                specs[id] = spec
                throw e
            }
        }
    }

    private fun specFor(config: NetworkConfig, address: String, prefix: Int): TunSpec = TunSpec(
        instanceName = config.instanceName,
        address = address,
        prefix = prefix,
        routes = config.proxyNetworks.map { it.cidr } + config.routes,
        mtu = tunMtu(config),
        ipv6Addresses = listOfNotNull(config.ipv6?.takeIf { it.isNotBlank() }),
        magicDns = config.flags.acceptDns,
    )

    /** Android TUN default is 1300; only an explicit non-default kernel MTU overrides it. */
    private fun tunMtu(config: NetworkConfig): Int =
        if (config.flags.mtu == DEFAULT_KERNEL_MTU) TunMerge.DEFAULT_MTU else config.flags.mtu

    /**
     * Rebuilds the shared TUN from [specs] and attaches it to every running instance.
     * Must be called with [tunMutex] held. A failed required attach leaves the old fd current.
     */
    private suspend fun rebuildTunLocked(requiredInstance: String?) {
        val merged = TunMerge.merge(specs.values)
        val previous = tunFd
        if (multiTun != null) {
            val fd = multiTun.establishMerged(merged)
            check(fd >= 0) { "establishMerged returned $fd" }
            try {
                attachAll(fd, requiredInstance)
            } finally {
                // establish() replaces the previous interface; the new fd must stay current
                // even when a member rejected it, or the kernel would hold a dead fd.
                multiTun.adopt(fd)
                tunFd = fd
            }
            if (previous >= 0 && previous != fd) multiTun.release(previous)
        } else {
            // Single-network fallback for engines backed by a plain TunProvider.
            val spec = specs.values.firstOrNull { it.instanceName == requiredInstance }
                ?: specs.values.lastOrNull()
                ?: throw IllegalStateException("no network to attach")
            val fd = tunProvider.establish(
                spec.address,
                spec.prefix,
                spec.routes + spec.runtimeRoutes,
                spec.mtu,
            )
            attachAll(fd, requiredInstance)
            tunFd = fd
        }
    }

    private fun attachAll(fd: Int, requiredInstance: String?) {
        for (spec in specs.values) {
            val result = bridge.setTunFd(spec.instanceName, fd)
            if (result != 0 && spec.instanceName == requiredInstance) {
                check(result, "setTunFd")
            }
            // A failed secondary attach is retried by the next rebuild.
        }
    }

    private suspend fun resolveAddress(config: NetworkConfig): Pair<String, Int> {
        if (!config.dhcp) {
            val static = config.ipv4?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("dhcp = false but ipv4 is not configured")
            return parseInet(static)
        }
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
                if (!instance.config.flags.noTun) {
                    val runtimeRoutes = TunMerge.peerProxyRoutes(json, instance.config.instanceName)
                    try {
                        reconcileSpec(id, withRates.myIpv4, runtimeRoutes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the old spec; the next poll retries.
                    }
                }
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

        /** `Flags.mtu` default; a config that never set mtu keeps this value. */
        const val DEFAULT_KERNEL_MTU = 1380
    }
}
