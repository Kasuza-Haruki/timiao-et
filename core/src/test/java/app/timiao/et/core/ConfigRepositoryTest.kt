package app.timiao.et.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private class UnreadableCipher : SecretCipher {
        private val delegate = FakeCipher()
        override fun encrypt(plain: ByteArray): ByteArray = delegate.encrypt(plain)
        override fun decrypt(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore key was reset")
    }

    private fun withTempDir(block: suspend (File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("timiao-config").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun seed(directory: File, vararg names: String): Pair<File, SecretCipher> {
        val file = File(directory, "networks.enc")
        val cipher = FakeCipher()
        val repository = FileConfigRepository(file, cipher)
        repository.awaitLoaded()
        names.forEach { name -> repository.save(sampleNetworkConfig().copy(instanceName = name)) }
        return file to cipher
    }

    @Test
    fun `decrypt failure exposes error, completes flow, and blocks writes`() = withTempDir { directory ->
        val (file, _) = seed(directory, "kept")
        val before = file.readBytes()

        val repository = FileConfigRepository(file, UnreadableCipher())
        repository.awaitLoaded()
        assertNotNull(repository.loadError.value)
        // The UI must not hang: the flow still completes with an empty list.
        assertEquals(0, withTimeout(5_000) { repository.networks.first() }.size)

        val blocked = runCatching {
            repository.save(sampleNetworkConfig().copy(instanceName = "new"))
        }.exceptionOrNull()
        assertTrue(blocked is ConfigWriteBlockedException)
        assertTrue("original file must not change", file.readBytes().contentEquals(before))
        assertEquals(0, repository.networks.first().size)
    }

    @Test
    fun `json parse failure is also a blocked, non-destructive state`() = withTempDir { directory ->
        val file = File(directory, "networks.enc")
        val cipher = FakeCipher()
        file.writeBytes(cipher.encrypt("this is not a snapshot".toByteArray()))
        val before = file.readBytes()

        val repository = FileConfigRepository(file, cipher)
        repository.awaitLoaded()
        assertNotNull(repository.loadError.value)
        val blocked = runCatching {
            repository.save(sampleNetworkConfig().copy(instanceName = "new"))
        }.exceptionOrNull()
        assertTrue(blocked is ConfigWriteBlockedException)
        assertTrue(file.readBytes().contentEquals(before))
    }

    @Test
    fun `missing file allows the first save and reload`() = withTempDir { directory ->
        val file = File(directory, "networks.enc")
        val cipher = FakeCipher()
        assertFalse(file.exists())

        val repository = FileConfigRepository(file, cipher)
        repository.awaitLoaded()
        assertNull(repository.loadError.value)
        repository.save(sampleNetworkConfig().copy(instanceName = "first"))
        assertTrue(file.exists())

        val reloaded = FileConfigRepository(file, cipher)
        reloaded.awaitLoaded()
        assertNull(reloaded.loadError.value)
        assertNotNull(reloaded.get("first"))
        assertEquals(1, reloaded.networks.first().size)
    }

    @Test
    fun `unparseable entry survives a later save as raw text`() = withTempDir { directory ->
        val file = File(directory, "networks.enc")
        val cipher = FakeCipher()
        runBlocking {
            val seedRepository = FileConfigRepository(file, cipher)
            seedRepository.awaitLoaded()
            seedRepository.save(sampleNetworkConfig().copy(instanceName = "good"))
            seedRepository.save(sampleNetworkConfig().copy(instanceName = "broken"))
        }

        val brokenToml = "this is not = [ valid toml"
        val plain = cipher.decrypt(file.readBytes()).decodeToString()
        val root = Json.parseToJsonElement(plain).jsonObject
        val entries = root["entries"]!!.jsonArray
        val rewritten = JsonArray(
            entries.map { element ->
                val entry = element.jsonObject
                if (entry["id"]!!.jsonPrimitive.content == "broken") {
                    JsonObject(entry.toMutableMap().apply { put("toml", JsonPrimitive(brokenToml)) })
                } else {
                    entry
                }
            },
        )
        val corrupted = JsonObject(root.toMutableMap().apply { put("entries", rewritten) })
        file.writeBytes(cipher.encrypt(corrupted.toString().toByteArray()))

        val repository = FileConfigRepository(file, cipher)
        repository.awaitLoaded()
        assertNull("one bad entry must not block the rest", repository.loadError.value)
        assertEquals(1, repository.networks.first().size)
        assertNotNull(repository.get("good"))

        repository.save(sampleNetworkConfig().copy(instanceName = "added"))

        val after = Json.parseToJsonElement(cipher.decrypt(file.readBytes()).decodeToString()).jsonObject
        val byId = after["entries"]!!.jsonArray.associate { element ->
            val entry = element.jsonObject
            entry["id"]!!.jsonPrimitive.content to entry["toml"]!!.jsonPrimitive.content
        }
        assertEquals(brokenToml, byId["broken"])
        assertTrue(byId.containsKey("added"))

        val reloaded = FileConfigRepository(file, cipher)
        reloaded.awaitLoaded()
        assertNotNull(reloaded.get("good"))
        assertNotNull(reloaded.get("added"))
    }

    @Test
    fun `failed atomic write leaves the old file intact`() = withTempDir { directory ->
        val (file, _) = seed(directory, "before")
        val before = file.readBytes()
        val repository = FileConfigRepository(file, FakeCipher())
        repository.awaitLoaded()

        // A directory at the temp path makes the write fail before rename.
        val temp = File(file.parentFile, file.name + ".tmp")
        assertTrue(temp.mkdirs())
        try {
            val blocked = runCatching {
                repository.save(sampleNetworkConfig().copy(instanceName = "after"))
            }.exceptionOrNull()
            assertNotNull(blocked)
            assertTrue(file.readBytes().contentEquals(before))
            assertFalse(temp.isFile)
            assertEquals(1, repository.networks.first().size)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `first write backs up the old file only once`() = withTempDir { directory ->
        val (file, _) = seed(directory, "first")
        val repository = FileConfigRepository(file, FakeCipher())
        repository.awaitLoaded()

        val backup = File(file.parentFile, file.name + ".bak")
        assertFalse(backup.exists())
        val original = file.readBytes()

        repository.save(sampleNetworkConfig().copy(instanceName = "second"))
        assertTrue(backup.exists())
        assertTrue(backup.readBytes().contentEquals(original))

        val backupBefore = backup.readBytes()
        repository.save(sampleNetworkConfig().copy(instanceName = "third"))
        assertTrue(backup.readBytes().contentEquals(backupBefore))
    }
}
