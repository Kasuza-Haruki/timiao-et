package app.timiao.et.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.BuildConfig
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.AppLanguage
import app.timiao.et.ui.data.LogLevel
import app.timiao.et.ui.data.ThemeMode
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.LocalReducedMotion

const val KERNEL_TAG = "v2.7.1"

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val g = LocalGlassColors.current
    val reduced = LocalReducedMotion.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(title = "设置", subtitle = "外观、语言与关于") }

        item { SectionLabel("外观") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(12.dp),
            ) {
                Text(
                    "主题",
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                SegmentedGlass(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selected = settings.themeMode.ordinal,
                    onSelect = { vm.setThemeMode(ThemeMode.entries[it]) },
                )
            }
        }
        item {
            GlassSwitchRow(
                title = "动态取色",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "使用壁纸配色（Material You）"
                } else {
                    "需要 Android 12 或更高版本"
                },
                checked = settings.dynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { vm.setDynamicColor(it) },
            )
        }
        item {
            GlassDropdownRow(
                label = "语言",
                current = settings.language,
                options = AppLanguage.entries,
                optionLabel = { it.label },
                onSelect = { vm.setLanguage(it) },
            )
        }
        item {
            GlassDropdownRow(
                label = "日志级别",
                current = settings.logLevel,
                options = LogLevel.entries,
                optionLabel = { it.label },
                onSelect = { vm.setLogLevel(it) },
            )
        }

        item { SectionLabel("关于") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow("App 版本", BuildConfig.VERSION_NAME)
                InfoRow("内核版本", "EasyTier $KERNEL_TAG（官方 tag）")
                InfoRow("包名", BuildConfig.APPLICATION_ID)
                InfoRow("动画", if (reduced) "系统已开启减少动画" else "跟随系统")
            }
        }
        item {
            Text(
                text = "缇喵 · 原生 Android EasyTier 客户端。小猫是原创画法，与任何现有 IP 无关。",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
