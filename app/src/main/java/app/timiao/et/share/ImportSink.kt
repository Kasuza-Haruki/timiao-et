package app.timiao.et.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.timiao.et.R
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Receives an imported TOML document. The host app wires this to its config repository. */
fun interface ImportSink {
    suspend fun import(toml: String, hasSecret: Boolean)
}

data class ImportRequest(
    val toml: String,
    val hasSecret: Boolean,
    val source: Source,
) {
    enum class Source { DeepLink, Text, File }
}

sealed interface ImportIntentResult {
    data class Ready(val request: ImportRequest) : ImportIntentResult
    data class Failure(val message: String) : ImportIntentResult
    data object Ignored : ImportIntentResult
}

/**
 * Turns the intents declared in the manifest into an [ImportRequest].
 *
 * Accepted shapes:
 * - `timiao://import?v=1&d=...` (custom scheme deep link)
 * - `ACTION_VIEW` of a `.toml` file (`content://` or `file://`)
 * - `ACTION_SEND` with text (share link or raw TOML) or a file stream
 */
object ImportIntents {

    suspend fun handle(context: Context, intent: Intent?, sink: ImportSink): ImportIntentResult {
        val result = read(context, intent)
        if (result is ImportIntentResult.Ready) {
            return try {
                sink.import(result.request.toml, result.request.hasSecret)
                result
            } catch (e: Exception) {
                ImportIntentResult.Failure(e.message ?: context.getString(R.string.import_failed))
            }
        }
        return result
    }

    fun read(context: Context, intent: Intent?): ImportIntentResult {
        if (intent == null) return ImportIntentResult.Ignored
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) {
            return ImportIntentResult.Ignored
        }

        intent.data?.let { uri ->
            if (uri.scheme.equals(ShareCodec.SCHEME, ignoreCase = true)) {
                return fromShareText(context, uri.toString(), ImportRequest.Source.DeepLink)
            }
            if (action == Intent.ACTION_VIEW) {
                val text = readUriText(context, uri)
                    ?: return ImportIntentResult.Failure(context.getString(R.string.import_read_failed))
                return fromShareText(context, text, ImportRequest.Source.File)
            }
        }

        val sentText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!sentText.isNullOrBlank()) {
            return fromShareText(context, sentText, ImportRequest.Source.Text)
        }

        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) {
            val text = readUriText(context, stream)
                ?: return ImportIntentResult.Failure(context.getString(R.string.import_read_failed))
            return fromShareText(context, text, ImportRequest.Source.File)
        }
        return ImportIntentResult.Failure(context.getString(R.string.import_nothing))
    }

    private fun fromShareText(context: Context, raw: String, source: ImportRequest.Source): ImportIntentResult =
        when (val decoded = ShareCodec.parseImportText(raw)) {
            is ShareCodec.DecodeResult.Success ->
                ImportIntentResult.Ready(ImportRequest(decoded.toml, decoded.hasSecret, source))
            is ShareCodec.DecodeResult.Invalid -> ImportIntentResult.Failure(ShareCodec.message(context, decoded))
        }

    private fun readUriText(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            val file = uri.path?.let { java.io.File(it) } ?: return null
            if (!file.isFile || file.length() > ShareCodec.MAX_INFLATED_BYTES) return null
            return runCatching { file.readText() }.getOrNull()
        }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > ShareCodec.MAX_INFLATED_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toString(StandardCharsets.UTF_8.name())
            }
        }.getOrNull()
    }
}
