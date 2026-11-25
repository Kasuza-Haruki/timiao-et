package app.timiao.et.core

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryTest {

    /** Reverses bytes and adds a marker so plaintext must not appear on disk. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            byteArrayOf(0x7F) + plain.reversedArray()

        override fun decrypt(sealed: ByteArray): ByteArray {
            require(sealed.first() == 0x7F.toByte())
            return sealed.copyOfRange(1, sealed.size).reversedArray()
        }
    }

    @Test
    fun savesAndReloadsConfigsEncrypted() = runBlocking {
        val file = File.createTempFile("timiao-config", ".bin")
        file.deleteOnExit()
        try {
            val cipher = FakeCipher()
            val repository = FileConfigRepository(file, cipher)
            repository.awaitLoaded()

            val config = sampleNetworkConfig().copy(
                instanceName = "phone",
                networkSecret = "super-secret-value",
                vpnPortal = VpnPortal(
                    wireguardListen = "0.0.0.0:51820",
                    wireguardPrivateKey = "wg-super-secret",
                ),
            )
            repository.save(config)

            val raw = file.readBytes().decodeToString()
            assertFalse(raw.contains("super-secret-value"))
            assertFalse(raw.contains("wg-super-secret"))

            val reloaded = FileConfigRepository(file, cipher)
            reloaded.awaitLoaded()
            val loaded = reloaded.get("phone")

            assertNotNull(loaded)
            assertEquals("super-secret-value", loaded!!.networkSecret)
            assertEquals("wg-super-secret", loaded.vpnPortal?.wireguardPrivateKey)
            assertEquals(config.peers, loaded.peers)
            assertEquals(1, reloaded.networks.first().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun deletesAndImports() = runBlocking {
        val file = File.createTempFile("timiao-config", ".bin")
        file.deleteOnExit()
        try {
            val repository = FileConfigRepository(file, FakeCipher())
            repository.awaitLoaded()

            val imported = repository.importToml(
                """
                instance_name = "lab"
                [network_identity]
                network_name = "lab-net"
                network_secret = "lab-secret"
                [[peer]]
                uri = "udp://127.0.0.1:11010"
                """.trimIndent(),
            )
            assertEquals("lab", imported.instanceName)
            repository.save(imported)
            assertNotNull(repository.get("lab"))

            val exported = repository.exportToml(imported)
            assertTrue(exported.contains("network_name = \"lab-net\""))

            repository.delete("lab")
            assertEquals(null, repository.get("lab"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun networksWaitsForInitialLoad() = runBlocking {
        val file = File.createTempFile("timiao-config", ".bin")
        file.deleteOnExit()
        try {
            val seed = FileConfigRepository(file, FakeCipher())
            seed.awaitLoaded()
            seed.save(sampleNetworkConfig().copy(instanceName = "cold"))

            // A slow decrypt keeps the initial empty state visible long enough for a
            // collector to see it if the flow emitted before the first read finished.
            val slowCipher = object : SecretCipher {
                private val delegate = FakeCipher()
                override fun encrypt(plain: ByteArray): ByteArray = delegate.encrypt(plain)
                override fun decrypt(sealed: ByteArray): ByteArray {
                    Thread.sleep(200)
                    return delegate.decrypt(sealed)
                }
            }
            val repository = FileConfigRepository(file, slowCipher)
            // No awaitLoaded(): the first emission itself must already be loaded.
            assertEquals(1, repository.networks.first().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fakeRepositoryKeepsSampleData() = runBlocking {
        val repository = FakeConfigRepository()
        assertEquals(1, repository.networks.first().size)
        assertNotNull(repository.get("home"))
        assertEquals("home", repository.get("home")?.instanceName)
    }
}
