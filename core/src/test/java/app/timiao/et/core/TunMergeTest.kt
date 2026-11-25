package app.timiao.et.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunMergeTest {

    @Test
    fun mergesAddressesAndDeduplicatesRoutes() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec("a", "10.0.0.1", 24, listOf("192.168.1.0/24")),
                TunSpec("b", "10.1.0.1", 24, listOf("192.168.1.0/24", "172.16.0.0/16")),
            ),
        )

        assertEquals(
            listOf(TunAddress("10.0.0.1", 24), TunAddress("10.1.0.1", 24)),
            merged.addresses,
        )
        val routes = merged.routes.map { "${it.network}/${it.prefix}" }
        assertTrue(routes.contains("10.0.0.0/24"))
        assertTrue(routes.contains("10.1.0.0/24"))
        assertTrue(routes.contains("192.168.1.0/24"))
        assertTrue(routes.contains("172.16.0.0/16"))
        assertEquals(1, routes.count { it == "192.168.1.0/24" })
        assertEquals(4, routes.size)
    }

    @Test
    fun normalizesRoutesBeforeDeduplicating() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec("a", "10.0.0.1", 24, listOf("10.9.0.7/24")),
                TunSpec("b", "10.0.1.1", 24, listOf("10.9.0.0/24")),
            ),
        )

        val routes = merged.routes.map { "${it.network}/${it.prefix}" }
        assertEquals(1, routes.count { it == "10.9.0.0/24" })
    }

    @Test
    fun skipsInvalidRoutesAndUsesSmallestMtu() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec("a", "10.0.0.1", 24, listOf("not-a-cidr", "10.0.0.1/33"), mtu = 1400),
                TunSpec("b", "10.0.1.1", 24, listOf("300.1.1.0/24"), mtu = 1280),
            ),
        )

        assertEquals(2, merged.routes.size)
        assertEquals(1280, merged.mtu)
    }

    @Test
    fun emptyInputIsSafe() {
        val merged = TunMerge.merge(emptyList())
        assertTrue(merged.addresses.isEmpty())
        assertTrue(merged.routes.isEmpty())
        assertEquals(TunMerge.DEFAULT_MTU, merged.mtu)
    }

    @Test
    fun magicDnsAddsServerAndRoute() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec("a", "10.0.0.1", 24, magicDns = true),
                TunSpec("b", "10.0.1.1", 24, magicDns = true),
            ),
        )

        assertEquals(listOf(TunMerge.MAGIC_DNS), merged.dns)
        assertEquals(1, merged.routes.count { "${it.network}/${it.prefix}" == "100.100.100.101/32" })
    }

    @Test
    fun ipv6AddressesAndRoutesKeepTheirPrefixes() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec(
                    "a",
                    "10.0.0.1",
                    24,
                    routes = listOf("2001:db8:2::/48", "2001:db8:2::/48"),
                    ipv6Addresses = listOf("2001:db8::1/64"),
                ),
            ),
        )

        assertTrue(merged.addresses.contains(TunAddress("2001:db8::1", 64)))
        assertEquals(3, merged.routes.size)
        assertEquals(1, merged.routes.count { it.prefix == 48 })
        assertEquals(1, merged.routes.count { it.prefix == 64 })
        assertEquals(64, TunMerge.parseCidr("2001:db8::1/64")?.second)
    }

    @Test
    fun runtimeRoutesJoinTheMerge() {
        val merged = TunMerge.merge(
            listOf(
                TunSpec("a", "10.0.0.1", 24, runtimeRoutes = listOf("172.20.0.0/16")),
                TunSpec("b", "10.0.1.1", 24, runtimeRoutes = listOf("172.20.0.0/16")),
            ),
        )

        assertEquals(1, merged.routes.count { "${it.network}/${it.prefix}" == "172.20.0.0/16" })
    }

    @Test
    fun peerProxyRoutesAreReadFromStatusJson() {
        val json = """
            {
              "a": {
                "peer_route_pairs": [
                  {"route": {"proxy_cidrs": ["192.168.50.0/24", "10.9.0.1/24"]}},
                  {"route": {"proxy_cidrs": []}}
                ]
              }
            }
        """.trimIndent()

        assertEquals(
            listOf("10.9.0.1/24", "192.168.50.0/24"),
            TunMerge.peerProxyRoutes(json, "a"),
        )
        assertTrue(TunMerge.peerProxyRoutes(json, "missing").isEmpty())
    }

    @Test
    fun ipv4CidrsOverlapInsideTheSameSubnet() {
        assertTrue(TunMerge.cidrOverlap("10.126.126.0/24", "10.126.126.7/24"))
        assertTrue(TunMerge.cidrOverlap("10.126.126.5/24", "10.126.126.0/24"))
        assertTrue(TunMerge.cidrOverlap("10.126.126.0/23", "10.126.127.0/24"))
        assertTrue(TunMerge.cidrOverlap("0.0.0.0/0", "10.126.126.0/24"))
    }

    @Test
    fun ipv4CidrsDoNotOverlapAcrossSubnets() {
        assertTrue(!TunMerge.cidrOverlap("10.126.126.0/24", "10.126.127.0/24"))
        assertTrue(!TunMerge.cidrOverlap("10.0.0.0/24", "11.0.0.0/24"))
        assertTrue(!TunMerge.cidrOverlap("10.0.0.0/25", "10.0.0.128/25"))
    }

    @Test
    fun ipv6CidrsOverlapByPrefix() {
        assertTrue(TunMerge.cidrOverlap("2001:db8::/32", "2001:db8:1::/48"))
        assertTrue(TunMerge.cidrOverlap("2001:db8::1/64", "2001:db8::2/64"))
        assertTrue(!TunMerge.cidrOverlap("2001:db8::/32", "2001:db9::/32"))
        assertTrue(!TunMerge.cidrOverlap("2001:db8::/64", "2001:db8:0:1::/64"))
    }

    @Test
    fun differentFamiliesAndInvalidInputNeverOverlap() {
        assertTrue(!TunMerge.cidrOverlap("10.126.126.0/24", "2001:db8::/32"))
        assertTrue(!TunMerge.cidrOverlap("not-a-cidr", "10.126.126.0/24"))
        assertTrue(!TunMerge.cidrOverlap("10.126.126.0/24", ""))
        assertEquals("10.126.126.0/24", TunMerge.normalizedCidr("10.126.126.7/24"))
        assertEquals("2001:db8::/32", TunMerge.normalizedCidr("2001:db8::1/32"))
    }
}
