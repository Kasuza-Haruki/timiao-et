package app.timiao.et.split

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/** Reads launcher apps. Manifest declares the <queries> launcher intent, not QUERY_ALL_PACKAGES. */
class InstalledAppsLoader(private val pm: PackageManager) {

    fun load(includeSystem: Boolean, excludePackage: String? = null): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }
            .onFailure { android.util.Log.w("TimiaoSplit", "query launcher apps failed: ${it.message}") }
            .getOrDefault(emptyList())
        return resolved
            .mapNotNull { ri ->
                val info = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
                if (info.packageName == excludePackage) return@mapNotNull null
                val system = (info.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                if (!includeSystem && system) return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString().ifBlank { info.packageName },
                    isSystem = system,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase(Locale.getDefault()) }, { it.packageName }))
    }
}

/** Lazily decoded app icons. Only visible list rows call [load]; results are cached by package. */
class AppIconCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    fun cached(packageName: String): ImageBitmap? = cache[packageName]

    suspend fun load(pm: PackageManager, packageName: String): ImageBitmap? =
        withContext(Dispatchers.IO) {
            cache[packageName] ?: runCatching {
                val d = pm.getApplicationIcon(packageName)
                d.toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { cache[packageName] = it }
        }
}
