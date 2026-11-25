package app.timiao.et.ui.data

import android.content.Context
import androidx.annotation.StringRes
import app.timiao.et.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

enum class LogLevel(val key: String, @StringRes val labelRes: Int) {
    Off("off", R.string.log_off),
    Error("error", R.string.log_error),
    Warn("warn", R.string.log_warn),
    Info("info", R.string.log_info),
    Debug("debug", R.string.log_debug),
    Trace("trace", R.string.log_trace);

    companion object {
        fun fromKey(key: String?): LogLevel = entries.firstOrNull { it.key == key } ?: Info
    }
}

enum class AppLanguage(val key: String, @StringRes val labelRes: Int) {
    System("system", R.string.settings_language_system),
    Chinese("zh", R.string.settings_language_zh),
    English("en", R.string.settings_language_en);

    companion object {
        fun fromKey(key: String?): AppLanguage = entries.firstOrNull { it.key == key } ?: System
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = false,
    val language: AppLanguage = AppLanguage.System,
    val logLevel: LogLevel = LogLevel.Info,
    val activeNetworkId: String? = null,
)

/** Small SharedPreferences-backed store; no Hilt, no DataStore. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME, null)),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        language = AppLanguage.fromKey(prefs.getString(KEY_LANG, null)),
        logLevel = LogLevel.fromKey(prefs.getString(KEY_LOG, null)),
        activeNetworkId = prefs.getString(KEY_ACTIVE, null),
    )

    fun updateTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.key).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun updateDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }

    fun updateLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANG, language.key).apply()
        _settings.value = _settings.value.copy(language = language)
    }

    fun updateLogLevel(level: LogLevel) {
        prefs.edit().putString(KEY_LOG, level.key).apply()
        _settings.value = _settings.value.copy(logLevel = level)
    }

    fun updateActiveNetwork(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        _settings.value = _settings.value.copy(activeNetworkId = id)
    }

    private companion object {
        const val PREFS = "timiao_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_LANG = "language"
        const val KEY_LOG = "log_level"
        const val KEY_ACTIVE = "active_network"
    }
}
