package app.timiao.et.share

import android.content.Context
import androidx.annotation.StringRes
import app.timiao.et.R

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Pure encode/decode for the share link `timiao://import?v=1&d=<base64url(deflate(toml))>`.
 *
 * Rules:
 * - The wire format is versioned by `v`. This code only accepts `v=1`.
 * - Decompression is bounded. A malicious link must not expand into gigabytes
 *   ("compression bomb"); anything above [MAX_INFLATED_BYTES] is rejected.
 * - Secrets are removed by [stripSecrets] before the default share path encodes.
 *   Only the well-known secret-bearing keys are removed.
 */
object ShareCodec {

    const val SCHEME = "timiao"
    const val HOST = "import"
    const val VERSION = "1"
    const val MAX_COMPRESSED_BYTES = 128 * 1024
    const val MAX_INFLATED_BYTES = 256 * 1024
    const val MAX_LINK_CHARS = 192 * 1024

    /** Practical upper bound for QR version 40 with error correction M (2331 bytes). */
    const val MAX_QR_CHARS = 2_300

    private val secretKeys = setOf(
        "network_secret",
        "credential_secret",
        "wireguard_private_key",
        "local_private_key",
    )
    private val assignment = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*$""")

    sealed interface DecodeResult {
        data class Success(val toml: String, val hasSecret: Boolean) : DecodeResult
        data class Invalid(@StringRes val reasonRes: Int, val args: List<String> = emptyList()) : DecodeResult
    }

    /** Localized text of an invalid result. */
    fun message(context: Context, result: DecodeResult.Invalid): String =
        context.getString(result.reasonRes, *result.args.toTypedArray())

    fun encode(toml: String): String {
        val compressed = deflate(toml.toByteArray(StandardCharsets.UTF_8))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$SCHEME://$HOST?v=$VERSION&d=$payload"
    }

    /** Default share: drop secrets unless the user explicitly opts in. */
    fun buildLink(toml: String, includeSecret: Boolean): String =
        encode(if (includeSecret) toml else stripSecrets(toml))

    fun decode(link: String): DecodeResult {
        val text = link.trim()
        if (text.isEmpty()) return DecodeResult.Invalid(R.string.share_err_empty)
        if (text.length > MAX_LINK_CHARS) return DecodeResult.Invalid(R.string.share_err_too_long)

        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return DecodeResult.Invalid(R.string.share_err_bad_format)
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) ||
            !HOST.equals(uri.host, ignoreCase = true)
        ) {
            return DecodeResult.Invalid(R.string.share_err_wrong_scheme)
        }

        val params = parseQuery(uri.rawQuery)
        if (params["v"] != VERSION) return DecodeResult.Invalid(R.string.share_err_bad_version)
        val data = params["d"] ?: return DecodeResult.Invalid(R.string.share_err_missing_data)
        if (data.length > MAX_LINK_CHARS) return DecodeResult.Invalid(R.string.share_err_too_long)

        val compressed = try {
            Base64.getUrlDecoder().decode(data)
        } catch (_: IllegalArgumentException) {
            return DecodeResult.Invalid(R.string.share_err_bad_base64)
        }
        if (compressed.size > MAX_COMPRESSED_BYTES) return DecodeResult.Invalid(R.string.share_err_too_big)

        val toml = try {
            inflateBounded(compressed)
        } catch (e: DataFormatException) {
            return DecodeResult.Invalid(R.string.share_err_corrupt)
        } catch (e: IllegalStateException) {
            return DecodeResult.Invalid(R.string.share_err_invalid)
        }
        if (toml.isBlank()) return DecodeResult.Invalid(R.string.share_err_empty_config)
        return DecodeResult.Success(toml, hasSecret(toml))
    }

    /** Accepts a share link or a raw TOML document (file share / paste). */
    fun parseImportText(raw: String): DecodeResult {
        val text = raw.trim()
        if (text.startsWith("$SCHEME://", ignoreCase = true)) return decode(text)
        if (text.length > MAX_INFLATED_BYTES) return DecodeResult.Invalid(R.string.share_err_inflated_too_big)
        if (looksLikeToml(text)) return DecodeResult.Success(text, hasSecret(text))
        return DecodeResult.Invalid(R.string.share_err_not_recognized)
    }

    fun looksLikeToml(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex("""(?m)^\s*\[[A-Za-z0-9_.-]+]\s*$""").containsMatchIn(text) ||
            Regex("""(?m)^\s*(instance_name|network_identity|hostname)\s*=""")
                .containsMatchIn(text)
    }

    fun hasSecret(toml: String): Boolean =
        toml.lineSequence().any { line ->
            val match = assignment.matchEntire(line) ?: return@any false
            match.groupValues[1] in secretKeys && match.groupValues[2].isNotEmpty()
        }

    /** Removes every known secret assignment line. Other TOML content is kept byte for byte. */
    fun stripSecrets(toml: String): String {
        val kept = toml.lineSequence().filterNot { line ->
            val match = assignment.matchEntire(line) ?: return@filterNot false
            match.groupValues[1] in secretKeys
        }
        val result = kept.joinToString("\n")
        return if (toml.endsWith("\n")) "$result\n" else result
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflateBounded(input: ByteArray): String {
        val inflater = Inflater(true)
        inflater.setInput(input)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw e
                }
                if (n == 0) {
                    if (inflater.needsInput()) throw IllegalStateException("incomplete")
                    if (inflater.needsDictionary()) throw IllegalStateException("invalid")
                    throw IllegalStateException("inflate")
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw IllegalStateException("too-large")
                }
            }
        } finally {
            inflater.end()
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index < 0) return@mapNotNull null
            val key = percentDecode(pair.substring(0, index))
            val value = percentDecode(pair.substring(index + 1))
            key to value
        }.toMap()
    }

    private fun percentDecode(value: String): String =
        if (value.contains('%')) {
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        } else {
            value
        }
}
