package app.timiao.et.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.timiao.et.BuildConfig
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.PillButton
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.TimiaoTheme
import app.timiao.et.update.KernelTag
import app.timiao.et.update.UpdateChecker
import app.timiao.et.update.UpdateResult
import kotlinx.coroutines.launch

/**
 * App + kernel update card. Network failures stay quiet: the row shows
 * "暂无更新信息". The app never downloads or replaces native code.
 */
@Composable
fun UpdateCard(
    modifier: Modifier = Modifier,
    currentVersion: String? = null,
    currentKernel: String? = null,
) {
    val context = LocalContext.current
    val assetKernel = remember(context) { KernelTag.read(context) }
    val version = currentVersion ?: BuildConfig.VERSION_NAME
    val kernel = currentKernel ?: assetKernel
    val checker = remember { UpdateChecker() }
    val scope = rememberCoroutineScope()

    var appResult by remember { mutableStateOf<UpdateResult?>(null) }
    var kernelResult by remember { mutableStateOf<UpdateResult?>(null) }
    var loading by remember { mutableStateOf(false) }

    val refresh: suspend () -> Unit = {
        if (!loading) {
            loading = true
            try {
                appResult = checker.checkApp(version)
                kernelResult = checker.checkKernel(kernel)
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(version, kernel) { refresh() }

    UpdateCardContent(
        appResult = appResult,
        kernelResult = kernelResult,
        currentVersion = version,
        currentKernel = kernel,
        loading = loading,
        onRefresh = { scope.launch { refresh() } },
        modifier = modifier,
    )
}

@Composable
internal fun UpdateCardContent(
    appResult: UpdateResult?,
    kernelResult: UpdateResult?,
    currentVersion: String,
    currentKernel: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGlassColors.current
    val app = summaryOf(appResult, loading, isKernel = false)
    val kernelSummary = summaryOf(kernelResult, loading, isKernel = true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        UpdateRow(
            title = "缇喵 App",
            current = "当前 ${versionLabel(currentVersion)}",
            summary = app,
        )
        Spacer(Modifier.height(10.dp))
        UpdateRow(
            title = "EasyTier 内核",
            current = "当前 ${versionLabel(currentKernel)}",
            summary = kernelSummary,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = g.muted,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (loading) "正在获取版本信息…" else "仅检查版本，不会自动下载或替换内核",
                style = MaterialTheme.typography.labelSmall,
                color = g.muted,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = if (loading) "检查中…" else "检查更新",
                onClick = onRefresh,
                enabled = !loading,
                secondary = true,
            )
        }
    }
}

@Composable
private fun UpdateRow(
    title: String,
    current: String,
    summary: UpdateSummary,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = summary.status,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = summary.color,
                textAlign = TextAlign.End,
            )
        }
        Text(
            text = current,
            style = MaterialTheme.typography.labelSmall,
            color = LocalGlassColors.current.muted,
        )
        summary.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (summary.color == MaterialTheme.colorScheme.primary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalGlassColors.current.muted
                },
            )
        }
    }
}

private data class UpdateSummary(
    val status: String,
    val detail: String?,
    val color: Color,
)

@Composable
private fun summaryOf(result: UpdateResult?, loading: Boolean, isKernel: Boolean): UpdateSummary {
    val g = LocalGlassColors.current
    val primary = MaterialTheme.colorScheme.primary
    return when (result) {
        null -> UpdateSummary(
            status = if (loading) "检查中…" else "未检查",
            detail = null,
            color = g.muted,
        )
        is UpdateResult.Available -> UpdateSummary(
            status = "有新版本",
            detail = if (isKernel) {
                "官方有新版本 v${result.latest}，缇喵会在适配后跟进"
            } else {
                "发现 v${result.latest}，可在 GitHub Releases 查看。"
            },
            color = primary,
        )
        is UpdateResult.UpToDate -> UpdateSummary(
            status = "已是最新",
            detail = if (isKernel) null else null,
            color = g.good,
        )
        UpdateResult.Unknown -> UpdateSummary(
            status = "暂无更新信息",
            detail = null,
            color = g.muted,
        )
    }
}

private fun versionLabel(value: String): String =
    if (value.startsWith("v", ignoreCase = true)) value else "v$value"

@Preview(showBackground = true, widthDp = 390)
@Preview(
    showBackground = true,
    widthDp = 390,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UpdateCardPreview() {
    TimiaoTheme(AppSettings()) {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            UpdateCardContent(
                appResult = UpdateResult.Available("0.2.0"),
                kernelResult = UpdateResult.Available("2.8.0"),
                currentVersion = "0.1.0",
                currentKernel = "v2.7.1",
                loading = false,
                onRefresh = {},
            )
        }
    }
}
