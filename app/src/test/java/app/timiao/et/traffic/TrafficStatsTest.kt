package app.timiao.et.traffic

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.timiao.et.core.PeerInfo

class TrafficStatsTest {

    @Test
    fun deltaIgnoresFirstSampleAndResets() {
        assertEquals(0L, TrafficMath.delta(null, 100))
        assertEquals(50L, TrafficMath.delta(100, 150))
        assertEquals(0L, TrafficMath.delta(150, 150))
        assertEquals(0L, TrafficMath.delta(150, 20))
    }

    @Test
    fun blockOnMobileWins() {
        val policy = TrafficPolicy(blockOnMobile = true, warnAtMb = 100)
        assertEquals(TrafficDecision.Block, TrafficMath.decide(NetworkKind.MOBILE, 0, policy))
        assertEquals(TrafficDecision.Block, TrafficMath.decide(NetworkKind.MOBILE, 999_999_999, policy))
    }

    @Test
    fun warnAtThresholdInclusive() {
        val policy = TrafficPolicy(warnAtMb = 100)
        assertEquals(TrafficDecision.Allow, TrafficMath.decide(NetworkKind.MOBILE, 99_999_999, policy))
        assertEquals(TrafficDecision.Warn, TrafficMath.decide(NetworkKind.MOBILE, 100_000_000, policy))
        assertEquals(TrafficDecision.Warn, TrafficMath.decide(NetworkKind.MOBILE, 500_000_000, policy))
    }

    @Test
    fun warnIsMobileOnlyAndCanBeDisabled() {
        assertEquals(
            TrafficDecision.Allow,
            TrafficMath.decide(NetworkKind.WIFI, 9_000_000_000, TrafficPolicy(warnAtMb = 100)),
        )
        assertEquals(
            TrafficDecision.Allow,
            TrafficMath.decide(NetworkKind.MOBILE, 9_000_000_000, TrafficPolicy(warnAtMb = 0)),
        )
    }

    @Test
    fun usageFiltersByDateAndKind() {
        val data = TrafficData(
            days = mapOf(
                LocalDate.parse("2026-01-31") to mapOf(
                    NetworkKind.WIFI to BytePair(10, 20),
                    NetworkKind.MOBILE to BytePair(1, 2),
                ),
                LocalDate.parse("2026-02-01") to mapOf(
                    NetworkKind.WIFI to BytePair(100, 200),
                ),
            ),
        )
        val january = data.usage(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))
        assertEquals(11L, january.tx)
        assertEquals(22L, january.rx)
        assertEquals(33L, january.total)
        assertEquals(3L, january.byKind[NetworkKind.MOBILE]?.total)
        val february = data.usage(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-28"))
        assertEquals(300L, february.total)
        assertNull(february.byKind[NetworkKind.MOBILE])
    }

    @Test
    fun lastDaysFillsMissingDaysWithZero() {
        val today = LocalDate.parse("2026-02-03")
        val data = TrafficData(
            days = mapOf(today to mapOf(NetworkKind.WIFI to BytePair(5, 5))),
        )
        val last = data.lastDays(today, 7)
        assertEquals(7, last.size)
        assertEquals(10L, last.last().second)
        assertEquals(0L, last.first().second)
        assertEquals(today.minusDays(6), last.first().first)
    }
}

class TrafficRecorderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun peer(tx: Long, rx: Long) = PeerInfo(
        hostname = "p", ipv4 = "10.0.0.2/24", cost = "p2p", tunnel = "udp",
        latencyMs = 1.0, lossRate = 0.0, natType = null, txBytes = tx, rxBytes = rx,
        version = null,
    )

    private fun recorder(file: File, clock: () -> Long = { 0L }): TrafficRecorder =
        TrafficRecorder(TrafficStore(file), ZoneOffset.UTC, clock)

    @Test
    fun addsNonNegativeDeltasOnly() {
        val file = File(temp.root, "t.txt")
        var now = 1_000L
        val recorder = recorder(file) { now }

        recorder.record("n1", NetworkKind.WIFI, listOf(peer(100, 200)), now)
        var today = recorder.today(now)
        assertEquals(100L, today.tx)
        assertEquals(200L, today.rx)

        // Same counters: no double count.
        recorder.record("n1", NetworkKind.WIFI, listOf(peer(100, 200)), now)
        assertEquals(100L, recorder.today(now).tx)

        now += 1_000
        recorder.record("n1", NetworkKind.WIFI, listOf(peer(300, 500)), now)
        today = recorder.today(now)
        assertEquals(300L, today.tx)
        assertEquals(500L, today.rx)

        // Counter reset: clamp to zero, then count from the new baseline.
        now += 1_000
        recorder.record("n1", NetworkKind.WIFI, listOf(peer(10, 20)), now)
        assertEquals(300L, recorder.today(now).tx)
        now += 1_000
        recorder.record("n1", NetworkKind.WIFI, listOf(peer(60, 80)), now)
        today = recorder.today(now)
        assertEquals(350L, today.tx)
        assertEquals(560L, today.rx)
    }

    @Test
    fun separatesNetworkKindsAndDays() {
        val file = File(temp.root, "t2.txt")
        val fileStore = TrafficStore(file)
        var now = 1_789_000_000_000L // 2026-09-08T...
        val recorder = TrafficRecorder(fileStore, ZoneOffset.UTC) { now }
        recorder.record("wifi", NetworkKind.WIFI, listOf(peer(10, 10)), now)
        recorder.record("mobile", NetworkKind.MOBILE, listOf(peer(5, 5)), now)
        val today = recorder.today(now)
        assertEquals(15L, today.tx)
        assertEquals(20L, today.byKind[NetworkKind.WIFI]?.total)
        assertEquals(10L, today.byKind[NetworkKind.MOBILE]?.total)

        now += 24 * 60 * 60 * 1000L
        recorder.record("wifi", NetworkKind.WIFI, listOf(peer(30, 30)), now)
        assertEquals(40L, recorder.today(now).total)
        assertEquals(70L, recorder.month(now).total)
    }

    @Test
    fun releaseForcesNewBaseline() {
        val file = File(temp.root, "t3.txt")
        val recorder = recorder(file)
        recorder.record("n", NetworkKind.WIFI, listOf(peer(100, 100)), 0)
        recorder.release("n")
        recorder.record("n", NetworkKind.WIFI, listOf(peer(1_000, 1_000)), 0)
        assertEquals(1_100L, recorder.today(0).tx)
    }

    @Test
    fun policyPersistsThroughStore() {
        val file = File(temp.root, "t4.txt")
        val recorder = recorder(file)
        recorder.setPolicy(TrafficPolicy(blockOnMobile = true, warnAtMb = 500))
        val loaded = TrafficStore(file).load()
        assertTrue(loaded.policy.blockOnMobile)
        assertEquals(500, loaded.policy.warnAtMb)
        assertEquals(TrafficDecision.Block, recorder.decision(NetworkKind.MOBILE, 0))
    }

    @Test
    fun dataSurvivesReload() {
        val file = File(temp.root, "t5.txt")
        val recorder = recorder(file)
        recorder.record("n,1", NetworkKind.WIFI, listOf(peer(7, 9)), 0)
        recorder.release("n,1") // forces a save
        val loaded = TrafficStore(file).load()
        assertEquals(7L, loaded.days.values.first().values.first().tx)
        assertEquals(9L, loaded.days.values.first().values.first().rx)
        assertFalse(loaded.days.isEmpty())
    }
}

class TrafficStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun roundTripKeepsIdsWithComma() {
        val file = File(temp.root, "store.txt")
        val store = TrafficStore(file)
        store.save(
            TrafficData(
                days = mapOf(
                    LocalDate.parse("2026-11-26") to mapOf(NetworkKind.MOBILE to BytePair(1, 2)),
                ),
                baselines = mapOf("id,with,commas" to BytePair(3, 4)),
                policy = TrafficPolicy(blockOnMobile = true, warnAtMb = 1_024),
            ),
        )
        val loaded = store.load()
        assertEquals(BytePair(1, 2), loaded.days[LocalDate.parse("2026-11-26")]?.get(NetworkKind.MOBILE))
        assertEquals(BytePair(3, 4), loaded.baselines["id,with,commas"])
        assertTrue(loaded.policy.blockOnMobile)
        assertEquals(1_024, loaded.policy.warnAtMb)
    }

    @Test
    fun malformedLinesAreIgnored() {
        val file = File(temp.root, "bad.txt")
        file.writeText(
            """
            v1
            day,not-a-date,WIFI,1,2
            day,2026-01-01,NOPE,1,2
            day,2026-01-01,WIFI,x,2
            policy,1
            baseline,%%%,1,2
            day,2026-01-02,MOBILE,5,6
            """.trimIndent(),
        )
        val loaded = TrafficStore(file).load()
        assertEquals(1, loaded.days.size)
        assertEquals(BytePair(5, 6), loaded.days[LocalDate.parse("2026-01-02")]?.get(NetworkKind.MOBILE))
        assertEquals(TrafficPolicy(), loaded.policy)
    }

    @Test
    fun missingFileGivesEmptyData() {
        val loaded = TrafficStore(File(temp.root, "none.txt")).load()
        assertTrue(loaded.days.isEmpty())
        assertTrue(loaded.baselines.isEmpty())
    }
}
