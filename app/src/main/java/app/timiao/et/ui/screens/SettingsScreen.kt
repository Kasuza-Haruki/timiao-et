package app.timiao.et.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.timiao.et.BuildConfig
import app.timiao.et.R
import app.timiao.et.ui.AppViewModel
import app.timiao.et.ui.components.DockInset
import app.timiao.et.ui.components.GlassDropdownRow
import app.timiao.et.ui.components.ClickableGlassRow
import app.timiao.et.ui.components.GlassLevel
import app.timiao.et.ui.components.GlassSwitchRow
import app.timiao.et.ui.components.InfoRow
import app.timiao.et.ui.components.ScreenHeader
import app.timiao.et.ui.components.SectionLabel
import app.timiao.et.ui.components.SegmentedGlass
import app.timiao.et.ui.components.glass
import app.timiao.et.ui.data.AppLanguage
import app.timiao.et.ui.data.ThemeMode
import app.timiao.et.ui.navigation.Routes
import app.timiao.et.ui.theme.LocalGlassColors
import app.timiao.et.ui.theme.LocalReducedMotion

const val KERNEL_TAG = "v2.7.1"

@Composable
fun SettingsScreen(vm: AppViewModel, onOpen: (String) -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val g = LocalGlassColors.current
    val reduced = LocalReducedMotion.current
    var language by remember { mutableStateOf(currentAppLanguage()) }
    val languageLabels = AppLanguage.entries.associateWith { stringResource(it.labelRes) }

    fun applyLanguage(next: AppLanguage) {
        val locales = when (next) {
            AppLanguage.System -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.Chinese -> LocaleListCompat.forLanguageTags("zh-CN")
            AppLanguage.English -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
        vm.setLanguage(next)
        language = next
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = DockInset,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader(title = stringResource(R.string.settings_title), subtitle = stringResource(R.string.settings_subtitle)) }

        item { SectionLabel(stringResource(R.string.settings_section_connection)) }
        item { AutoRulesEntryRow { onOpen(AUTO_RULES_ROUTE) } }
        item { NavRow(stringResource(R.string.settings_split), stringResource(R.string.settings_split_sub)) { onOpen(SplitRoutes.of(app.timiao.et.split.SplitStore.GLOBAL_ID)) } }
        item { NavRow(stringResource(R.string.settings_diagnostics), stringResource(R.string.settings_diagnostics_sub)) { onOpen("diagnostics") } }
        item { NavRow(stringResource(R.string.settings_traffic), stringResource(R.string.settings_traffic_sub)) { onOpen("traffic") } }

        item { SectionLabel(stringResource(R.string.settings_section_appearance)) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.labelSmall,
                    color = g.muted,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                )
                SegmentedGlass(
                    options = listOf(
                        stringResource(R.string.settings_theme_system),
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                    ),
                    selected = settings.themeMode.ordinal,
                    onSelect = { vm.setThemeMode(ThemeMode.entries[it]) },
                )
            }
        }
        item {
            GlassSwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    stringResource(R.string.settings_dynamic_color_sub)
                } else {
                    stringResource(R.string.settings_dynamic_color_requires)
                },
                checked = settings.dynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { vm.setDynamicColor(it) },
            )
        }

        item { SectionLabel(stringResource(R.string.settings_section_language)) }
        item {
            GlassDropdownRow(
                label = stringResource(R.string.settings_language),
                current = language,
                options = AppLanguage.entries,
                optionLabel = { languageLabels[it].orEmpty() },
                onSelect = ::applyLanguage,
            )
        }

        item { SectionLabel(stringResource(R.string.settings_section_about)) }
        item { UpdateCard(currentVersion = BuildConfig.VERSION_NAME, currentKernel = KERNEL_TAG) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(22.dp), GlassLevel.Regular)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                InfoRow(stringResource(R.string.settings_app_version), BuildConfig.VERSION_NAME)
                InfoRow(stringResource(R.string.settings_kernel_version), stringResource(R.string.settings_kernel_value, KERNEL_TAG))
                InfoRow(stringResource(R.string.settings_package), BuildConfig.APPLICATION_ID)
                InfoRow(stringResource(R.string.settings_animation), stringResource(if (reduced) R.string.settings_animation_reduced else R.string.settings_animation_system))
            }
        }
        item {
            NavRow(
                stringResource(R.string.settings_licenses),
                stringResource(R.string.settings_licenses_sub),
            ) { onOpen(Routes.LICENSES) }
        }
        item {
            Text(
                text = stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                color = g.muted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

private fun currentAppLanguage(): AppLanguage {
    val locales = AppCompatDelegate.getApplicationLocales()
    val tag = if (locales.isEmpty) null else locales[0]?.language
    return when {
        tag == null -> AppLanguage.System
        tag.startsWith("zh") -> AppLanguage.Chinese
        tag.startsWith("en") -> AppLanguage.English
        else -> AppLanguage.System
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    val g = LocalGlassColors.current
    ClickableGlassRow(onClick = onClick) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = g.muted)
            }
            androidx.compose.material3.Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = g.muted,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp)
                    .rotate(-90f),
            )
        }
    }
}
