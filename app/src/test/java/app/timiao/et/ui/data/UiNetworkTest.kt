package app.timiao.et.ui.data

import app.timiao.et.R
import app.timiao.et.core.TomlCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNetworkTest {

    private val importedToml = """
        instance_name = "phone"
        instance_id = "00000000-0000-4000-8000-000000000009"
        hostname = "pixel"
        dhcp = true
        socks5_proxy = "socks5://0.0.0.0:1080"
        exit_nodes = ["10.126.126.1"]
        mapped_listeners = ["tcp://203.0.113.5:11223"]

        [network_identity]
        network_name = "home"
        network_secret = "unit-test-secret-do-not-use"

        [secure_mode]
        enabled = true
        local_private_key = "dW5pdC10ZXN0LXByaXZhdGUta2V5LTMyLWJ5dGVzISE="

        [flags]
        mtu = 1420
        disable_relay_data = true

        [[managed_credentials]]
        credential_id = "cred-1"
        credential_secret = "cred-secret"
    """.trimIndent()

    @Test
    fun editingNameKeepsEveryFieldFromImport() {
        val imported = TomlCodec.importToml(importedToml).toUi()
        val edited = imported.copy(name = "renamed")
        val saved = edited.toConfig()
        val exported = TomlCodec.exportToml(saved)
        val again = TomlCodec.importToml(exported)

        assertEquals("renamed", again.networkName)
        assertEquals("pixel", again.hostname)
        assertEquals(1420, again.flags.mtu)
        assertEquals("socks5://0.0.0.0:1080", again.socks5Proxy)
        assertEquals(listOf("10.126.126.1"), again.exitNodes)
        assertEquals(listOf("tcp://203.0.113.5:11223"), again.mappedListeners)
        assertEquals("dW5pdC10ZXN0LXByaXZhdGUta2V5LTMyLWJ5dGVzISE=", again.secureMode?.localPrivateKey)
        assertEquals("cred-1", again.managedCredentials.single().credentialId)
        assertEquals("phone", again.instanceName)
        assertEquals("00000000-0000-4000-8000-000000000009", again.instanceId)
        assertTrue(again.flags.disableRelayData)
    }

    @Test
    fun exportWithoutSecretDropsIdentitySecret() {
        val config = TomlCodec.importToml(importedToml)
        val text = TomlCodec.exportToml(config.copy(networkSecret = ""))

        assertFalse(text.contains("network_secret"))
    }

    @Test
    fun hiddenFieldsSurviveWhenBaseIsPreserved() {
        val imported = TomlCodec.importToml(importedToml).toUi()
        val ui = imported.copy(name = "renamed")
        val saved = ui.toConfig()
        val again = TomlCodec.importToml(TomlCodec.exportToml(saved))

        assertEquals(imported.base?.acceptedForTest(), again.acceptedForTest())
    }

    @Test
    fun defaultsMatchOfficialNewNetwork() {
        val network = newNetwork()

        assertEquals("easytier", network.name)
        assertTrue(network.dhcp)
        assertEquals(listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010", "wg://0.0.0.0:11011"), network.listeners)
        assertTrue(network.flags["enable_encryption"] == true)
        assertTrue(network.flags["enable_ipv6"] == true)
        assertTrue(network.flags["multi_thread"] == true)
        assertTrue(network.flags["bind_device"] == true)
        assertFalse(network.flags["disable_p2p"] == true)
        assertEquals("tcp", network.defaultProtocol)
        assertEquals("aes-gcm", network.encryptionAlgorithm)
        assertNull(network.vpnPortal)
    }

    @Test
    fun dhcpDisablesStaticAddressEditing() {
        assertTrue(staticIpEditable(false))
        assertFalse(staticIpEditable(true))
    }

    @Test
    fun savedNewNetworkGetsStableInstanceId() {
        val network = newNetwork().copy(name = "alpha")
        val config = network.toConfig()

        assertEquals(network.id, config.instanceId)
        assertEquals(network.id, config.id)
    }

    @Test
    fun portForwardDraftRoundTripsAndValidates() {
        val draft = PortForwardDraft("udp", "0.0.0.0", "12345", "10.126.126.1", "23456")
        assertNull(draft.error())
        val config = draft.toPortForward()
        assertEquals("0.0.0.0:12345", config.bindAddr)
        assertEquals("10.126.126.1:23456", config.dstAddr)
        assertEquals(draft, config.toDraft())

        assertEquals(R.string.edit_err_port_range, draft.copy(bindPort = "70000").error())
        assertEquals(R.string.edit_err_port_range, draft.copy(dstPort = "x").error())
        assertEquals(R.string.edit_err_addr_required, draft.copy(dstIp = "").error())
        assertEquals(R.string.edit_err_protocol_tcp_udp, draft.copy(proto = "sctp").error())
    }

    @Test
    fun ipv6PortForwardKeepsBrackets() {
        val draft = PortForwardDraft("tcp", "::", "8080", "fd00::2", "80")
        val forward = draft.toPortForward()
        assertEquals("[::]:8080", forward.bindAddr)
        assertEquals("[fd00::2]:80", forward.dstAddr)
        assertEquals("::", forward.toDraft().bindIp)
    }

    @Test
    fun portForwardDraftSurvivesPortFirstAndClearing() {
        // Port typed before the host: the host must stay empty, not ":80".
        val portFirst = PortForwardDraft("tcp", "", "8080", "", "80").toPortForward()
        assertEquals(":8080", portFirst.bindAddr)
        assertEquals(":80", portFirst.dstAddr)
        val draft = portFirst.toDraft()
        assertEquals("", draft.bindIp)
        assertEquals("8080", draft.bindPort)
        assertEquals("", draft.dstIp)
        assertEquals("80", draft.dstPort)

        // Clearing a host must not round-trip a colon back into the field.
        val cleared = PortForwardDraft("tcp", "", "", "", "").toPortForward()
        assertEquals("", cleared.bindAddr)
        assertEquals("", cleared.dstAddr)
        assertEquals(PortForwardDraft("tcp", "", "", "", ""), cleared.toDraft())
        assertEquals("" to "", splitHostPort(":"))
        assertEquals("" to "80", splitHostPort(":80"))
        assertEquals("10.0.0.1" to "", splitHostPort("10.0.0.1:"))
    }

    @Test
    fun proxyNetworkParsesOfficialSyntax() {
        assertEquals("192.168.1.0/24" to null, parseProxyNetwork("192.168.1.0/24")?.let { it.cidr to it.mappedCidr })
        val mapped = parseProxyNetwork("192.168.1.0/24->10.9.0.0/24")
        assertNotNull(mapped)
        assertEquals("10.9.0.0/24", mapped!!.mappedCidr)
        assertNull(parseProxyNetwork("not-a-cidr"))
        assertNull(parseProxyNetwork("10.0.0.0/24->x"))
        assertEquals("192.168.1.0/24->10.9.0.0/24", formatProxyNetwork(mapped))
    }

    @Test
    fun hostPortValidation() {
        assertTrue(isValidHostPort("0.0.0.0:22022"))
        assertTrue(isValidHostPort("[::]:22022"))
        assertFalse(isValidHostPort("0.0.0.0"))
        assertFalse(isValidHostPort("0.0.0.0:70000"))
        assertTrue(isValidCidr("10.126.126.0/24"))
        assertFalse(isValidCidr("10.126.126.0/33"))
    }

    @Test
    fun relayWhitelistSemanticsSurviveRoundTrip() {
        val disabled = newNetwork().copy(relayNetworkWhitelistEnabled = false)
        assertEquals("*", disabled.toConfig().flags.relayNetworkWhitelist)

        val enabledEmpty = newNetwork().copy(
            relayNetworkWhitelistEnabled = true,
            relayNetworkWhitelist = emptyList(),
        )
        assertEquals("", enabledEmpty.toConfig().flags.relayNetworkWhitelist)

        val enabledList = newNetwork().copy(
            relayNetworkWhitelistEnabled = true,
            relayNetworkWhitelist = listOf("net1", "def*"),
        )
        assertEquals("net1 def*", enabledList.toConfig().flags.relayNetworkWhitelist)
    }

    @Test
    fun wireGuardClientKeyDerivationIsDeterministic() {
        val master = WireGuardKeys.generatePrivateKey()
        val first = WireGuardKeys.deriveClientPrivateKey(master, "alice")
        val second = WireGuardKeys.deriveClientPrivateKey(master, "alice")
        val other = WireGuardKeys.deriveClientPrivateKey(master, "bob")

        assertNotNull(first)
        assertEquals(first, second)
        assertTrue(first != other)
        assertEquals(32, java.util.Base64.getDecoder().decode(first).size)
    }

    @Test
    fun wireGuardClientConfigFollowsOfficialTemplate() {
        val master = WireGuardKeys.generatePrivateKey()
        val config = WireGuardKeys.clientConfig(
            masterPrivateKeyBase64 = master,
            listen = "0.0.0.0:22022",
            clientName = "alice",
            virtualIpCidr = "10.126.126.10/24",
            proxyCidrs = listOf("192.168.1.0/24"),
        )
        assertNotNull(config)
        assertTrue(config!!.contains("[Interface]"))
        assertTrue(config.contains("PrivateKey = "))
        assertTrue(config.contains("Address = 10.126.126.10/32"))
        assertTrue(config.contains("[Peer]"))
        assertTrue(config.contains("AllowedIPs = "))
        assertTrue(config.contains("192.168.1.0/24"))
        assertTrue(config.contains("10.126.126.0/24"))
        assertTrue(config.contains("Endpoint = 0.0.0.0:22022"))
        assertTrue(config.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun x25519MatchesRfc7748Vector() {
        val privateBytes = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expected = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val basepoint = ByteArray(32).also { it[0] = 9 }

        val publicBytes = WireGuardKeys.x25519(privateBytes, basepoint)

        assertEquals(expected.toHex(), publicBytes.toHex())
        val encoded = java.util.Base64.getEncoder().encodeToString(privateBytes)
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(expected),
            WireGuardKeys.publicKey(encoded),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun hex(text: String): ByteArray =
    ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun app.timiao.et.core.NetworkConfig.acceptedForTest(): List<Any?> = listOf(
    instanceId, instanceName, hostname, mappedListeners, socks5Proxy, exitNodes,
    secureMode, managedCredentials, flags, listenerUrls, proxyNetworks, portForwards,
)
