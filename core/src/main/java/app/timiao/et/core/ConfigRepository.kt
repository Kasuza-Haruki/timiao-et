package app.timiao.et.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    /**
     * Non-null when the stored file exists but could not be read (decrypt or
     * JSON failure). While set, every write is refused so the old data stays
     * intact. Null on first run and after a successful load.
     */
    public val loadError: StateFlow<String?>

    /** Throws [ConfigWriteBlockedException]; only successful writes change memory state. */
    public suspend fun save(c: NetworkConfig)
    public suspend fun delete(id: String)
    public suspend fun importToml(text: String): NetworkConfig
    public fun exportToml(c: NetworkConfig): String
    public suspend fun get(id: String): NetworkConfig?
}

/** Raised when the saved file could not be read, so writing would destroy it. */
public class ConfigWriteBlockedException(message: String) : IllegalStateException(message)

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
    private val _loadError = MutableStateFlow<String?>(null)
    override val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** Raw TOML of entries whose own text failed to parse; written back unchanged. */
    private val preserved = LinkedHashMap<String, String>()

    /**
     * Emits only after the first disk read finishes. The initial empty value of
     * [_networks] must not reach collectors: a cold-start caller would see an
     * empty list and conclude that no network exists.
     */
    override val networks: Flow<List<NetworkConfig>> = flow {
        loaded.await()
        emitAll(_networks)
    }
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                reload()
            } catch (error: Exception) {
                // The flow must still complete, but a failed load keeps writes blocked.
                _loadError.value = "load failed: ${error.javaClass.simpleName}"
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /** Waits for the initial disk read. */
    public suspend fun awaitLoaded() {
        loaded.await()
    }

    private sealed interface LoadResult {
        data object Missing : LoadResult
        data class Loaded(val configs: List<NetworkConfig>, val preserved: Map<String, String>) : LoadResult
        data class Failed(val reason: String) : LoadResult
    }

    /**
     * Re-reads the file. Never throws: a missing or empty file is a normal first
     * run, while a decrypt/JSON failure becomes visible through [loadError] and
     * blocks writes.
     */
    public suspend fun reload() {
        mutex.withLock {
            when (val result = withContext(io) { readFromDisk() }) {
                LoadResult.Missing -> {
                    preserved.clear()
                    _networks.value = emptyList()
                    _loadError.value = null
                }
                is LoadResult.Loaded -> {
                    preserved.clear()
                    preserved.putAll(result.preserved)
                    _networks.value = result.configs
                    _loadError.value = null
                }
                is LoadResult.Failed -> {
                    preserved.clear()
                    _networks.value = emptyList()
                    _loadError.value = result.reason
                }
            }
        }
    }

    private fun readFromDisk(): LoadResult {
        if (!file.exists() || file.length() == 0L) return LoadResult.Missing
        val plain = try {
            cipher.decrypt(file.readBytes()).decodeToString()
        } catch (error: Exception) {
            return LoadResult.Failed("decrypt failed: ${error.javaClass.simpleName}")
        }
        val snapshot = try {
            json.decodeFromString<Snapshot>(plain)
        } catch (error: Exception) {
            return LoadResult.Failed("snapshot parse failed: ${error.javaClass.simpleName}")
        }
        val configs = ArrayList<NetworkConfig>(snapshot.entries.size)
        val preserved = LinkedHashMap<String, String>()
        for (entry in snapshot.entries) {
            val parsed = try {
                TomlCodec.importToml(entry.toml)
            } catch (error: Exception) {
                null
            }
            if (parsed != null) configs.add(parsed) else preserved[entry.id] = entry.toml
        }
        return LoadResult.Loaded(configs, preserved)
    }

    override suspend fun save(c: NetworkConfig) {
        loaded.await()
        mutex.withLock {
            ensureWritable()
            val list = _networks.value.toMutableList()
            val index = list.indexOfFirst { it.id == c.id }
            if (index >= 0) list[index] = c else list.add(c)
            persisted(list)
            preserved.remove(c.id)
            _networks.value = list
        }
    }

    override suspend fun delete(id: String) {
        loaded.await()
        mutex.withLock {
            ensureWritable()
            val list = _networks.value.filterNot { it.id == id }
            persisted(list)
            preserved.remove(id)
            _networks.value = list
        }
    }

    override suspend fun importToml(text: String): NetworkConfig = TomlCodec.importToml(text)

    override fun exportToml(c: NetworkConfig): String = TomlCodec.exportToml(c)

    override suspend fun get(id: String): NetworkConfig? {
        loaded.await()
        return _networks.value.firstOrNull { it.id == id }
    }

    private fun ensureWritable() {
        _loadError.value?.let { reason ->
            throw ConfigWriteBlockedException(
                "saved configuration could not be read ($reason); refusing to overwrite it",
            )
        }
    }

    private suspend fun persisted(list: List<NetworkConfig>) {
        withContext(io) {
            ensureWritable()
            val entries = LinkedHashMap<String, String>(preserved)
            list.forEach { config -> entries[config.id] = TomlCodec.exportToml(config) }
            val snapshot = Snapshot(entries = entries.map { Entry(it.key, it.value) })
            val plain = json.encodeToString(snapshot).encodeToByteArray()
            val sealed = cipher.encrypt(plain)
            val directory = file.absoluteFile.parentFile
                ?: throw IOException("configuration file has no parent directory")
            directory.mkdirs()
            backupOnce(directory)
            writeAtomically(directory, sealed)
        }
    }

    /** Keeps the pre-change file once, so a first successful write is reversible. */
    private fun backupOnce(directory: File) {
        if (!file.exists() || file.length() == 0L) return
        val backup = File(directory, file.name + ".bak")
        if (backup.exists()) return
        val temp = File(directory, file.name + ".bak.tmp")
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            moveIntoPlace(temp, backup)
        } catch (error: Exception) {
            temp.delete()
            throw IOException("cannot back up configuration before first write", error)
        }
    }

    /** Write -> fsync -> rename, so a crash never leaves a truncated main file. */
    private fun writeAtomically(directory: File, bytes: ByteArray) {
        val temp = File(directory, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveIntoPlace(temp, file)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    /** rename(2) replaces the target atomically. Windows rejects atomic replace; retry. */
    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (error: FileSystemException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** In-memory repository for Compose previews and tests. */
public class FakeConfigRepository(
    initial: List<NetworkConfig> = listOf(sampleNetworkConfig()),
) : ConfigRepository {
    private val state = MutableStateFlow(initial)
    override val networks: StateFlow<List<NetworkConfig>> = state.asStateFlow()
    private val noError = MutableStateFlow<String?>(null)
    override val loadError: StateFlow<String?> = noError.asStateFlow()

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
