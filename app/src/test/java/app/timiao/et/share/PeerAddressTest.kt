package app.timiao.et.share

import app.timiao.et.core.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAddressTest {

    private fun peer(ipv4: String?) = PeerInfo(
        hostname = "nas home",
        ipv4 = ipv4,
        cost = "p2p",
        tunnel = "udp",
        latencyMs = 1.0,
        lossRate = 0.0,
        natType = null,
        txBytes = 0,
        rxBytes = 0,
        version = null,
    )

    @Test
    fun stripsCidrFromIp() {
        assertEquals("10.144.144.2", peer("10.144.144.2/24").hostAddress())
    }

    @Test
    fun handlesMissingIp() {
        assertNull(peer(null).hostAddress())
        assertNull(peer("").hostAddress())
        assertNull(peer("/24").hostAddress())
    }

    @Test
    fun safeHostsPass() {
        assertTrue(isSafeHost("10.144.144.2"))
        assertTrue(isSafeHost("nas-home.local"))
        assertTrue(isSafeHost("fe80::1"))
    }

    @Test
    fun optionLikeAndShellLikeHostsFail() {
        assertFalse(isSafeHost("-c"))
        assertFalse(isSafeHost("host name"))
        assertFalse(isSafeHost("host;rm -rf /"))
        assertFalse(isSafeHost("host|cat"))
        assertFalse(isSafeHost(""))
        assertFalse(isSafeHost("a".repeat(254)))
    }
}
