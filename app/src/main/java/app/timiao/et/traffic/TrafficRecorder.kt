package app.timiao.et.traffic

import app.timiao.et.core.PeerInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Integrates cumulative peer byte counters into per-day totals.
 *
 * Why counters, not rates: [app.timiao.et.core.StatusParser] builds `txBps`/`rxBps`
 * from the delta of `peer.conns[].stats.tx_bytes/rx_bytes` over one poll interval.
 * Multiplying a 1-second rate by elapsed time accumulates sampling error and drops
 * bytes when the UI is not polling. The underlying `txBytes`/`rxBytes` are cumulative
 * since the connection started, so differencing them here is stable. Non-monotonic
 * resets (reconnect) count as 0 for that tick and re-baseline.
 */
class TrafficRecorder(
    private val store: TrafficStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var data = store.load()
    private val _state = MutableStateFlow(data)
    val state: StateFlow<TrafficData> = _state.asStateFlow()

    private var lastSaveMs = -SAVE_INTERVAL_MS

    @Synchronized
    fun record(
        networkId: String,
        kind: NetworkKind,
        peers: List<PeerInfo>,
        nowMs: Long = clock(),
    ) {
        val tx = peers.sumOf { it.txBytes }
        val rx = peers.sumOf { it.rxBytes }
        val previous = data.baselines[networkId] ?: BytePair(0, 0)
        val delta = BytePair(
            tx = TrafficMath.delta(previous.tx, tx),
            rx = TrafficMath.delta(previous.rx, rx),
        )
        val newBaselines = data.baselines + (networkId to BytePair(tx, rx))
        if (delta.total <= 0) {
            if (previous.tx != tx || previous.rx != rx) {
                data = data.copy(baselines = newBaselines)
                publish(nowMs, force = false)
            }
            return
        }

        val today = localDate(nowMs)
        val kindMap = (data.days[today] ?: emptyMap()).toMutableMap()
        kindMap[kind] = (kindMap[kind] ?: BytePair()) + delta
        val days = (data.days + (today to kindMap))
            .filterKeys { it >= today.minusDays(PRUNE_DAYS) }
        data = data.copy(days = days, baselines = newBaselines)
        publish(nowMs, force = false)
    }

    /** Called when a network stops, so a reconnect starts from its new counters. */
    @Synchronized
    fun release(networkId: String) {
        if (networkId !in data.baselines) return
        data = data.copy(baselines = data.baselines - networkId)
        publish(clock(), force = true)
    }

    @Synchronized
    fun setPolicy(policy: TrafficPolicy) {
        if (data.policy == policy) return
        data = data.copy(policy = policy)
        publish(clock(), force = true)
    }

    fun snapshot(): TrafficData = data

    fun today(nowMs: Long = clock()): TrafficUsage {
        val date = localDate(nowMs)
        return data.usage(date, date)
    }

    fun month(nowMs: Long = clock()): TrafficUsage {
        val date = localDate(nowMs)
        return data.usage(date.withDayOfMonth(1), date)
    }

    fun lastDays(count: Int = 7, nowMs: Long = clock()): List<Pair<LocalDate, Long>> =
        data.lastDays(localDate(nowMs), count)

    fun decision(kind: NetworkKind, nowMs: Long = clock()): TrafficDecision {
        val month = month(nowMs)
        return TrafficMath.decide(
            kind = kind,
            monthBytes = TrafficMath.monthBytesForDecision(month, kind),
            policy = data.policy,
        )
    }

    private fun publish(nowMs: Long, force: Boolean) {
        _state.value = data
        if (force || nowMs - lastSaveMs >= SAVE_INTERVAL_MS) {
            store.save(data)
            lastSaveMs = nowMs
        }
    }

    private fun localDate(nowMs: Long): LocalDate =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    private companion object {
        const val SAVE_INTERVAL_MS = 3_000L
        const val PRUNE_DAYS = 90L
    }
}
