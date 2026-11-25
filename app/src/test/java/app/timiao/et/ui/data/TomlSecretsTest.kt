package app.timiao.et.ui.data

import app.timiao.et.core.ManagedCredential
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.SecureMode
import app.timiao.et.core.TomlCodec
import app.timiao.et.core.VpnPortal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlSecretsTest {

    private val config = NetworkConfig(
        instanceName = "net",
        networkName = "mesh",
        networkSecret = "network-secret-value",
        secureMode = SecureMode(
            enabled = true,
            localPrivateKey = "secure-private-value",
            localPublicKey = "secure-public-value",
        ),
        vpnPortal = VpnPortal(
            wireguardListen = "0.0.0.0:22022",
            enabled = true,
            wireguardPrivateKey = "portal-private-value",
        ),
        managedCredentials = listOf(
            ManagedCredential("cred-a", "credential-secret-a"),
            ManagedCredential("cred-b", "credential-secret-b"),
        ),
    )

    private val realToml = TomlCodec.exportToml(config)
    private val maskedToml = maskTomlSecrets(realToml)

    private fun pristine(): NetworkConfig = TomlCodec.importToml(realToml)

    @Test
    fun `mask hides every sensitive value and keeps keys`() {
        listOf(
            "network-secret-value",
            "secure-private-value",
            "portal-private-value",
            "credential-secret-a",
            "credential-secret-b",
        ).forEach { assertFalse("leaked $it", maskedToml.contains(it)) }
        assertTrue(maskedToml.contains("network_secret = \"$HIDDEN_SECRET\""))
        assertTrue(maskedToml.contains("local_private_key = \"$HIDDEN_SECRET\""))
        assertTrue(maskedToml.contains("wireguard_private_key = \"$HIDDEN_SECRET\""))
        assertEquals(2, Regex("""credential_secret = "$HIDDEN_SECRET"""").findAll(maskedToml).count())
        // Public and non-secret values stay readable.
        assertTrue(maskedToml.contains("secure-public-value"))
        assertTrue(maskedToml.contains("network_name = \"mesh\""))
        assertEquals(realToml.lines().size, maskedToml.lines().size)
    }

    @Test
    fun `untouched placeholders keep the original values`() {
        val restored = restoreHiddenSecrets(TomlCodec.importToml(maskedToml), pristine())
        assertEquals("network-secret-value", restored.networkSecret)
        assertEquals("secure-private-value", restored.secureMode?.localPrivateKey)
        assertEquals("portal-private-value", restored.vpnPortal?.wireguardPrivateKey)
        assertEquals(
            listOf("credential-secret-a", "credential-secret-b"),
            restored.managedCredentials.map { it.credentialSecret },
        )
    }

    @Test
    fun `changed value wins over the original`() {
        val edited = maskedToml.replace(
            "network_secret = \"$HIDDEN_SECRET\"",
            "network_secret = \"brand-new-secret\"",
        )
        val restored = restoreHiddenSecrets(TomlCodec.importToml(edited), pristine())
        assertEquals("brand-new-secret", restored.networkSecret)
        // Other placeholders still replay their originals.
        assertEquals("secure-private-value", restored.secureMode?.localPrivateKey)
    }

    @Test
    fun `deleted line clears the field`() {
        val edited = maskedToml.lines()
            .filterNot { it.trimStart().startsWith("local_private_key") }
            .filterNot { it.trimStart().startsWith("network_secret") }
            .joinToString("\n")
        val restored = restoreHiddenSecrets(TomlCodec.importToml(edited), pristine())
        assertEquals("", restored.networkSecret)
        assertNull(restored.secureMode?.localPrivateKey)
        // Untouched secret lines are still restored.
        assertEquals("portal-private-value", restored.vpnPortal?.wireguardPrivateKey)
    }

    @Test
    fun `deleted credential row is dropped not restored`() {
        val lines = maskedToml.lines()
        val start = lines.indexOfFirst { it.contains("credential_id = \"cred-b\"") }
        // The exporter writes credential_id right before credential_secret.
        val withoutB = lines.filterIndexed { index, _ -> index != start && index != start + 1 }
            .joinToString("\n")
        val restored = restoreHiddenSecrets(TomlCodec.importToml(withoutB), pristine())
        assertEquals(listOf("cred-a"), restored.managedCredentials.map { it.credentialId })
    }

    @Test
    fun `reveal puts real values back and masks nothing else`() {
        val revealed = revealTomlSecrets(maskedToml, pristine())
        listOf(
            "network-secret-value",
            "secure-private-value",
            "portal-private-value",
            "credential-secret-a",
            "credential-secret-b",
        ).forEach { assertTrue("missing $it", revealed.contains(it)) }
        assertFalse(revealed.contains(HIDDEN_SECRET))
        // Round-trip through hide again gives the same masked document.
        assertEquals(maskedToml, maskTomlSecrets(revealed))
    }
}
