package app.timiao.et.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

public sealed interface UpdateResult {
    public data class Available(
        val latest: String,
        val url: String = "",
        val notes: String? = null,
    ) : UpdateResult

    public data class UpToDate(val latest: String) : UpdateResult

    /** 404 (private repo), timeout or any other failure. The UI shows "暂无更新信息". */
    public data object Unknown : UpdateResult
}

/** Injectable HTTP seam for tests. */
public sealed interface HttpResponse {
    public data class Ok(val body: String) : HttpResponse
    public data class Status(val code: Int) : HttpResponse
    public data object Failed : HttpResponse
}

/**
 * Checks GitHub releases only. It never downloads or replaces native code:
 * Android must not load native libraries downloaded at runtime.
 */
public class UpdateChecker(
    private val http: (String) -> HttpResponse = HttpGet::fetch,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public suspend fun checkApp(currentVersion: String): UpdateResult =
        check(APP_RELEASES_URL, currentVersion)

    public suspend fun checkKernel(currentKernel: String): UpdateResult =
        check(KERNEL_RELEASES_URL, currentKernel)

    private suspend fun check(url: String, currentVersion: String): UpdateResult =
        withContext(dispatcher) {
            val response = try {
                http(url)
            } catch (_: Throwable) {
                HttpResponse.Failed
            }
            val body = when (response) {
                is HttpResponse.Ok -> response.body
                else -> return@withContext UpdateResult.Unknown
            }
            val release = GitHubReleaseParser.parse(body) ?: return@withContext UpdateResult.Unknown
            if (release.draft || release.prerelease) return@withContext UpdateResult.Unknown
            val latest = normalizeVersion(release.tagName)
            if (latest.isBlank()) return@withContext UpdateResult.Unknown
            if (isNewerVersion(latest, currentVersion)) {
                UpdateResult.Available(
                    latest = latest,
                    url = release.htmlUrl,
                    notes = release.body?.trim()?.take(600)?.takeIf { it.isNotBlank() },
                )
            } else {
                UpdateResult.UpToDate(latest)
            }
        }

    public companion object {
        public const val APP_RELEASES_URL: String =
            "https://api.github.com/repos/Kasuza-Haruki/timiao-et/releases/latest"
        public const val KERNEL_RELEASES_URL: String =
            "https://api.github.com/repos/EasyTier/EasyTier/releases/latest"
        public const val TIMEOUT_MS: Int = 8_000
    }
}

internal object HttpGet {
    fun fetch(address: String): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateChecker.TIMEOUT_MS
                readTimeout = UpdateChecker.TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "timiao-et-android")
            }
            if (connection.responseCode in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                HttpResponse.Ok(body)
            } else {
                HttpResponse.Status(connection.responseCode)
            }
        } catch (_: Exception) {
            HttpResponse.Failed
        } finally {
            connection?.disconnect()
        }
    }
}
