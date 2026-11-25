package app.timiao.et.ui.data

import android.content.Context
import android.net.Uri
import app.timiao.et.R
import app.timiao.et.core.NetworkConfig
import app.timiao.et.core.TomlParseException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Upper bound for a TOML file picked from storage. */
const val MAX_TOML_BYTES: Int = 1024 * 1024

/** Decodes a TOML file as UTF-8 and strips an optional BOM. */
fun decodeTomlBytes(bytes: ByteArray): Result<String> {
    if (bytes.size > MAX_TOML_BYTES) {
        return Result.failure(IllegalArgumentException("File exceeds 1 MB"))
    }
    val offset = if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        3
    } else {
        0
    }
    return runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }.recoverCatching { throw IllegalArgumentException("File is not valid UTF-8 text") }
}

/** Reads a content URI with a 1 MB hard limit, then decodes it as UTF-8. */
fun readTomlUri(context: Context, uri: Uri): Result<String> {
    val bytes = runCatching {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.file_cannot_open))
        stream.use { input ->
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_TOML_BYTES) {
                    throw IllegalArgumentException(context.getString(R.string.file_too_large))
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrElse { return Result.failure(it) }
    if (bytes.size > MAX_TOML_BYTES) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.file_too_large)))
    }
    return decodeTomlBytes(bytes).recoverCatching {
        throw IllegalArgumentException(context.getString(R.string.file_not_utf8))
    }
}

/** Localized TOML parse message, with line and column when available. */
fun tomlErrorMessage(context: Context, error: Throwable?): String? = when (error) {
    null -> null
    is TomlParseException -> context.getString(
        R.string.toml_error_at_line,
        error.line,
        error.column,
        error.reason,
    )
    else -> error.message
}

/** True when the imported document reuses an instance ID that is already saved. */
fun hasInstanceIdConflict(importedId: String?, existingIds: Set<String>): Boolean =
    !importedId.isNullOrBlank() && importedId in existingIds

/**
 * Picks the ID to save an import under. Save-as-new never reuses an existing
 * ID, because a copied instance_id collides with the original instance. A file
 * without an explicit instance_id also gets a fresh one.
 */
fun resolveImportId(
    importedId: String?,
    existingIds: Set<String>,
    saveAsNew: Boolean,
    generatedId: String = UUID.randomUUID().toString(),
): String {
    if (!saveAsNew && !importedId.isNullOrBlank()) return importedId
    return if (generatedId !in existingIds) generatedId else UUID.randomUUID().toString()
}

/** Default export file name for a network. */
fun exportFileName(networkName: String): String {
    val safe = networkName.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), "-")
        .ifBlank { "easytier" }
    return "$safe.toml"
}

/**
 * One-shot handoff from the network list to the editor: the file import opens
 * the editor with this config as an unsaved draft, so nothing is stored before
 * the user confirms.
 */
object PendingImport {
    var config: NetworkConfig? = null
}
