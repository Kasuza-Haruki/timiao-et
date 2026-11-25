package app.timiao.et.traffic

import java.time.LocalDate

/** Where the traffic went. Mobile means the system's active network is cellular. */
enum class NetworkKind(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("移动数据"),
    OTHER("其他"),
}

data class BytePair(val tx: Long = 0, val rx: Long = 0) {
    val total: Long get() = tx + rx
    operator fun plus(other: BytePair): BytePair = BytePair(tx + other.tx, rx + other.rx)
}

data class TrafficUsage(
    val tx: Long = 0,
    val rx: Long = 0,
    val byKind: Map<NetworkKind, BytePair> = emptyMap(),
) {
    val total: Long get() = tx + rx

    companion object {
        fun of(buckets: Collection<BytePair>): TrafficUsage {
            var tx = 0L
            var rx = 0L
            buckets.forEach {
                tx += it.tx
                rx += it.rx
            }
            return TrafficUsage(tx, rx)
        }
    }
}

data class TrafficPolicy(
    /** Refuse to start a tunnel while the active network is cellular. */
    val blockOnMobile: Boolean = false,
    /** 0 disables the reminder. Otherwise remind when this month's mobile bytes exceed it. */
    val warnAtMb: Int = 0,
)

enum class TrafficDecision { Allow, Warn, Block }

/**
 * Pure policy math. The host app calls this from its auto-connect/service path:
 * - Block: do not connect on mobile data.
 * - Warn: connect, but show the reminder once.
 * - Allow: no action.
 */
object TrafficMath {

    fun delta(previous: Long?, current: Long): Long {
        if (previous == null || current <= previous) return 0
        return current - previous
    }

    fun decide(kind: NetworkKind, monthBytes: Long, policy: TrafficPolicy): TrafficDecision = when {
        kind == NetworkKind.MOBILE && policy.blockOnMobile -> TrafficDecision.Block
        kind == NetworkKind.MOBILE &&
            policy.warnAtMb > 0 &&
            monthBytes >= policy.warnAtMb.toLong() * MB -> TrafficDecision.Warn
        else -> TrafficDecision.Allow
    }

    fun monthBytesForDecision(month: TrafficUsage, kind: NetworkKind): Long = when (kind) {
        NetworkKind.MOBILE -> month.byKind[NetworkKind.MOBILE]?.total ?: 0L
        NetworkKind.WIFI -> month.byKind[NetworkKind.WIFI]?.total ?: 0L
        NetworkKind.OTHER -> month.byKind[NetworkKind.OTHER]?.total ?: 0L
    }

    const val MB: Long = 1_000_000L
}

data class TrafficData(
    val days: Map<LocalDate, Map<NetworkKind, BytePair>> = emptyMap(),
    /** Last cumulative peer counters per network, used to compute deltas. */
    val baselines: Map<String, BytePair> = emptyMap(),
    val policy: TrafficPolicy = TrafficPolicy(),
) {
    fun usage(from: LocalDate, to: LocalDate): TrafficUsage {
        val buckets = mutableListOf<BytePair>()
        val byKind = mutableMapOf<NetworkKind, BytePair>()
        days.forEach { (date, kinds) ->
            if (date < from || date > to) return@forEach
            kinds.forEach { (kind, bytes) ->
                buckets += bytes
                byKind[kind] = (byKind[kind] ?: BytePair()) + bytes
            }
        }
        val total = TrafficUsage.of(buckets)
        return total.copy(byKind = byKind)
    }

    fun lastDays(today: LocalDate, count: Int): List<Pair<LocalDate, Long>> {
        val start = today.minusDays((count - 1).toLong())
        return (0 until count).map { offset ->
            val date = start.plusDays(offset.toLong())
            date to (days[date]?.values?.fold(0L) { acc, pair -> acc + pair.total } ?: 0L)
        }
    }
}
