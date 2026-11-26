package app.timiao.et.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlRoundTripTest {

    private val phoneToml = """
        hostname = "phone-a"
        instance_name = "Home-Lab"
        instance_id = "91c76673-b539-5e99-8e7b-5dd20fcec66f"
        dhcp = true

        [network_identity]
        network_name = "Home-Lab"
        network_secret = "unit-test-secret-do-not-use"

        [[peer]]
        uri = "udp://203.0.113.9:11010"

        [[peer]]
        uri = "tcp://203.0.113.9:11010"

        [flags]
        latency_first = true
        disable_relay_data = true
    """.trimIndent()

    private val fullToml = """
        instance_name = "phone"
        instance_id = "88c19b5a-b480-52b0-89f1-fe8b490b0c24"
        hostname = "pixel"
        ipv4 = "10.144.144.10/24"
        listeners = ["tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"]
        mapped_listeners = ["tcp://1.2.3.4:11010"]
        routes = ["192.168.0.0/16"]
        exit_nodes = ["10.144.144.1"]
        tcp_whitelist = ["10.0.0.0/8"]
        udp_whitelist = ["10.1.0.0/16"]
        stun_servers = ["stun.example.com:3478"]
        tcp_stun_servers = ["tcp.example.com:3478"]
        stun_servers_v6 = ["[::1]:3478"]
        socks5_proxy = "socks5://127.0.0.1:1080"
        ipv6 = "fd00::10/64"
        dhcpv6 = false
        dhcp_ipv4_cidr = "10.144.144.0/24"
        ipv6_public_addr_provider = true
        ipv6_public_addr_auto = false
        ipv6_public_addr_prefix = "fd00:1::/48"
        credential_file = "/data/local/tmp/cred"

        [network_identity]
        network_name = "home-net"
        network_secret = "unit-test-secret-do-not-use"

        [[peer]]
        uri = "tcp://public.example.com:11010"
        peer_public_key = "pubkey"

        [[proxy_network]]
        cidr = "192.168.1.0/24"
        mapped_cidr = "10.9.0.0/24"
        allow = ["tcp", "udp"]

        [[port_forward]]
        bind_addr = "127.0.0.1:8080"
        dst_addr = "10.144.144.2:80"
        proto = "tcp"

        [vpn_portal_config]
        enabled = true
        wireguard_listen = "0.0.0.0:51820"
        wireguard_private_key = "wg-private"

        [[vpn_portal_config.clients]]
        name = "alice"
        virtual_ip = "10.144.144.20/24"
        groups = ["staff"]

        [secure_mode]
        enabled = true
        local_private_key = "local-private"
        local_public_key = "local-public"

        [[managed_credentials]]
        credential_id = "cred-1"
        credential_secret = "cred-secret"
        groups = ["ops"]
        allow_relay = true
        allowed_proxy_cidrs = ["10.0.0.0/24"]
        expiry_unix = 2000000000
        reusable = false

        [flags]
        latency_first = true
        disable_relay_data = true
        mtu = 1420
        enable_encryption = false
        data_compress_algo = "Zstd"
        encryption_algorithm = "aes-256-gcm"
        relay_network_whitelist = "*"

        [[acl.acl_v1.chains]]
        name = "protect"
        chain_type = 3
        default_action = 2

        [[acl.acl_v1.chains.rules]]
        name = "allow"
        action = 1
        protocol = 5
        source_ips = ["10.0.0.0/8"]
    """.trimIndent()

    @Test
    fun importsSampleConfig() {
        val config = TomlCodec.importToml(phoneToml)

        assertEquals("Home-Lab", config.instanceName)
        assertEquals("91c76673-b539-5e99-8e7b-5dd20fcec66f", config.instanceId)
        assertEquals("phone-a", config.hostname)
        assertEquals("Home-Lab", config.networkName)
        assertEquals("unit-test-secret-do-not-use", config.networkSecret)
        assertTrue(config.dhcp)
        assertEquals(
            listOf("udp://203.0.113.9:11010", "tcp://203.0.113.9:11010"),
            config.peers.map { it.uri },
        )
        assertTrue(config.flags.latencyFirst)
        assertTrue(config.flags.disableRelayData)
    }

    @Test
    fun phoneConfigRoundTrip() {
        val first = TomlCodec.importToml(phoneToml)
        val exported = TomlCodec.exportToml(first)
        val second = TomlCodec.importToml(exported)

        assertEquals(first, second)
        assertTrue(exported.contains("network_secret = \"unit-test-secret-do-not-use\""))
        assertTrue(exported.contains("[[peer]]"))
        assertTrue(exported.contains("latency_first = true"))
        assertTrue(exported.contains("disable_relay_data = true"))
    }

    @Test
    fun fullConfigRoundTrip() {
        val first = TomlCodec.importToml(fullToml)
        val second = TomlCodec.importToml(TomlCodec.exportToml(first))

        assertEquals(first, second)
        assertEquals("10.144.144.10/24", second.ipv4)
        assertEquals("192.168.1.0/24", second.proxyNetworks.single().cidr)
        assertEquals("10.9.0.0/24", second.proxyNetworks.single().mappedCidr)
        assertEquals(listOf("tcp", "udp"), second.proxyNetworks.single().allow)
        assertEquals("127.0.0.1:8080", second.portForwards.single().bindAddr)
        assertEquals("0.0.0.0:51820", second.vpnPortal?.wireguardListen)
        assertEquals("alice", second.vpnPortal?.clients?.single()?.name)
        assertEquals("local-private", second.secureMode?.localPrivateKey)
        assertEquals("cred-secret", second.managedCredentials.single().credentialSecret)
        assertEquals(1420, second.flags.mtu)
        assertFalse(second.flags.enableEncryption)
        assertEquals("Zstd", second.flags.dataCompressAlgo)
        assertEquals("aes-256-gcm", second.flags.encryptionAlgorithm)
        assertNotNull(second.acl)
        assertTrue(second.acl!!.contains("acl_v1"))
    }

    @Test
    fun aclIsPassedThroughVerbatim() {
        val config = TomlCodec.importToml(fullToml)
        val exported = TomlCodec.exportToml(config)

        assertTrue(exported.contains("[[acl.acl_v1.chains]]"))
        assertTrue(exported.contains("source_ips = [\"10.0.0.0/8\"]"))
        assertTrue(exported.contains("[[acl.acl_v1.chains.rules]]"))
    }

    @Test
    fun unknownFieldsSurviveRoundTrip() {
        val input = """
            instance_name = "keep"
            custom_scalar = 42

            [network_identity]
            network_name = "n"
            network_secret = "s"
            future_identity_field = "keep-me"

            [custom_section]
            answer = "forty-two"
            nested = { a = 1, b = true }
        """.trimIndent()

        val first = TomlCodec.importToml(input)
        assertTrue(first.extras.keys.contains("custom_section"))
        assertTrue(first.extras.keys.contains("custom_scalar"))
        val identity = first.extras.entries["network_identity"] as? TomlValue.Tbl
        assertEquals(
            "keep-me",
            ((identity?.entries?.get("future_identity_field")) as? TomlValue.Str)?.value,
        )

        val exported = TomlCodec.exportToml(first)
        assertTrue(exported.contains("custom_scalar = 42"))
        assertTrue(exported.contains("[custom_section]"))
        assertTrue(exported.contains("answer = \"forty-two\""))
        assertTrue(exported.contains("future_identity_field = \"keep-me\""))

        val second = TomlCodec.importToml(exported)
        assertEquals(first, second)
    }

    @Test
    fun editedValuesOverrideImportedOnes() {
        val first = TomlCodec.importToml(phoneToml)
        val edited = first.copy(
            networkSecret = "new-fake-secret",
            hostname = "new-phone",
            peers = first.peers + PeerConfig("ws://relay.example.com:11011"),
            flags = first.flags.copy(mtu = 1300),
        )

        val second = TomlCodec.importToml(TomlCodec.exportToml(edited))

        assertEquals("new-fake-secret", second.networkSecret)
        assertEquals("new-phone", second.hostname)
        assertEquals(3, second.peers.size)
        assertEquals(1300, second.flags.mtu)
        assertTrue(second.flags.latencyFirst)
    }

    @Test
    fun camelCaseFlagAliasesAreAccepted() {
        val config = TomlCodec.importToml(
            """
            [flags]
            enableEncryption = false
            latencyFirst = true
            mtu = "1420"
            dataCompressAlgo = "Zstd"
            socketMark = "0"
            """.trimIndent(),
        )

        assertFalse(config.flags.enableEncryption)
        assertTrue(config.flags.latencyFirst)
        assertEquals(1420, config.flags.mtu)
        assertEquals("Zstd", config.flags.dataCompressAlgo)
        assertEquals(0, config.flags.socketMark)
        assertTrue(config.flags.enableIpv6)
        assertTrue(config.flags.multiThread)
    }
}
