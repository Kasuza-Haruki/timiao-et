package app.timiao.et.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public interface ConfigRepository {
    public val networks: Flow<List<NetworkConfig>>
    public suspend fun save(c: NetworkConfig)
    public suspend fun delete(id: String)
    public suspend fun importToml(text: String): NetworkConfig
    public fun exportToml(c: NetworkConfig): String
    public suspend fun get(id: String): NetworkConfig?
}

/** Encrypts the whole config file, so network secrets never touch disk in clear text. */
public interface SecretCipher {
    public fun encrypt(plain: ByteArray): ByteArray
    public fun decrypt(sealed: ByteArray): ByteArray
}

public class AndroidKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return ByteArray(1 + iv.size + encrypted.size).also { output ->
            output[0] = iv.size.toByte()
            iv.copyInto(output, 1)
            encrypted.copyInto(output, 1 + iv.size)
        }
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.isNotEmpty()) { "empty encrypted payload" }
        val ivLength = sealed[0].toInt() and 0xFF
        require(sealed.size > 1 + ivLength) { "truncated encrypted payload" }
        val iv = sealed.copyOfRange(1, 1 + ivLength)
        val encrypted = sealed.copyOfRange(1 + ivLength, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "timiao-et-config"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * Persists networks as encrypted TOML documents, which keeps every field the
 * user wrote even if this app does not model it.
 */
public class FileConfigRepository(
    private val file: File,
    private val cipher: SecretCipher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ConfigRepository {

    @Serializable
    private data class Entry(val id: String, val toml: String)

    @Serializable
    private data class Snapshot(val version: Int = 1, val entries: List<Entry>)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _networks = MutableStateFlow<List<NetworkConfig>>(emptyList())
    override val networks: StateFlow<List<NetworkConfig>> = _networks.asStateFlow()
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching { reload() }
            loaded.complete(Unit)
        }
    }

    /** Waits for the initial disk read. */
    public suspend fun awaitLoaded() {
        loaded.await()
    }

    public suspend fun reload() {
        mutex.withLock {
            val configs = withContext(io) {
                if (!file.exists() || file.length() == 0L) return@withContext emptyList<NetworkConfig>()
                val plain = cipher.decrypt(file.readBytes()).decodeToString()
                json.decodeFromString<Snapshot>(plain).entries.mapNotNull { entry ->
                    runCatching { TomlCodec.importToml(entry.toml) }.getOrNull()
                }
            }
            _networks.value = configs
        }
    }

    override suspend fun save(c: NetworkConfig) {
        loaded.await()
        mutex.withLock {
            val list = _networks.value.toMutableList()
            val index = list.indexOfFirst { it.id == c.id }
            if (index >= 0) list[index] = c else list.add(c)
            _networks.value = list
            persist(list)
        }
    }

    override suspend fun delete(id: String) {
        loaded.await()
        mutex.withLock {
            val list = _networks.value.filterNot { it.id == id }
            _networks.value = list
            persist(list)
        }
    }

    override suspend fun importToml(text: String): NetworkConfig = TomlCodec.importToml(text)

    override fun exportToml(c: NetworkConfig): String = TomlCodec.exportToml(c)

    override suspend fun get(id: String): NetworkConfig? {
        loaded.await()
        return _networks.value.firstOrNull { it.id == id }
    }

    private suspend fun persist(list: List<NetworkConfig>) {
        withContext(io) {
            val snapshot = Snapshot(entries = list.map { Entry(it.id, TomlCodec.exportToml(it)) })
            val plain = json.encodeToString(snapshot).encodeToByteArray()
            file.parentFile?.mkdirs()
            file.writeBytes(cipher.encrypt(plain))
        }
    }
}

/** In-memory repository for Compose previews and tests. */
public class FakeConfigRepository(
    initial: List<NetworkConfig> = listOf(sampleNetworkConfig()),
) : ConfigRepository {
    private val state = MutableStateFlow(initial)
    override val networks: StateFlow<List<NetworkConfig>> = state.asStateFlow()

    override suspend fun save(c: NetworkConfig) {
        val list = state.value.toMutableList()
        val index = list.indexOfFirst { it.id == c.id }
        if (index >= 0) list[index] = c else list.add(c)
        state.value = list
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun importToml(text: String): NetworkConfig = TomlCodec.importToml(text)

    override fun exportToml(c: NetworkConfig): String = TomlCodec.exportToml(c)

    override suspend fun get(id: String): NetworkConfig? = state.value.firstOrNull { it.id == id }
}

public fun sampleNetworkConfig(): NetworkConfig = NetworkConfig(
    instanceName = "home",
    hostname = "nas-home",
    networkName = "timiao",
    networkSecret = "demo-secret",
    dhcp = true,
    listenerUrls = listOf(
        "tcp://0.0.0.0:11010",
        "udp://0.0.0.0:11010",
        "wg://0.0.0.0:11011",
    ),
    peers = listOf(PeerConfig("udp://public.easytier.top:11010")),
    flags = Flags(latencyFirst = true),
)
