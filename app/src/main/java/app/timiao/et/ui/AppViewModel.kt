package app.timiao.et.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.timiao.et.core.ConfigRepository
import app.timiao.et.core.Engine
import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkStatus
import app.timiao.et.di.AppContainer
import app.timiao.et.ui.data.AppLanguage
import app.timiao.et.ui.data.AppSettings
import app.timiao.et.ui.data.LogLevel
import app.timiao.et.ui.data.SettingsStore
import app.timiao.et.ui.data.ThemeMode
import app.timiao.et.ui.data.UiNetwork
import app.timiao.et.ui.data.toConfig
import app.timiao.et.ui.data.toUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(
    private val engine: Engine,
    private val repository: ConfigRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings

    val networks: StateFlow<List<UiNetwork>> = repository.networks
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val statuses: StateFlow<Map<String, NetworkStatus>> = engine.status

    private val _activeNetworkId = MutableStateFlow(settingsStore.settings.value.activeNetworkId)
    val activeNetworkId: StateFlow<String?> = _activeNetworkId

    val activeNetwork: StateFlow<UiNetwork?> = combine(networks, _activeNetworkId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeStatus: StateFlow<NetworkStatus> = combine(activeNetwork, statuses) { net, map ->
        map[net?.id] ?: IdleStatus
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IdleStatus)

    init {
        viewModelScope.launch {
            repository.networks.collect { raw ->
                val ids = raw.map { it.id }
                if (_activeNetworkId.value !in ids) {
                    selectNetwork(ids.firstOrNull())
                }
            }
        }
    }

    fun statusOf(id: String): NetworkStatus = statuses.value[id] ?: IdleStatus

    fun selectNetwork(id: String?) {
        _activeNetworkId.value = id
        settingsStore.updateActiveNetwork(id)
    }

    fun toggleActiveNetwork() {
        val net = activeNetwork.value ?: return
        val state = statusOf(net.id).state
        viewModelScope.launch {
            when (state) {
                LinkState.Connected, LinkState.Connecting -> engine.stop(net.id)
                LinkState.Idle, LinkState.Error -> engine.start(net.id)
            }
        }
    }

    fun startNetwork(id: String) {
        viewModelScope.launch { engine.start(id) }
    }

    fun stopNetwork(id: String) {
        viewModelScope.launch { engine.stop(id) }
    }

    fun saveNetwork(network: UiNetwork) {
        viewModelScope.launch { repository.save(network.toConfig()) }
    }

    fun deleteNetwork(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (_activeNetworkId.value == id) selectNetwork(networks.value.firstOrNull { it.id != id }?.id)
        }
    }

    suspend fun importToml(text: String): Result<UiNetwork> = runCatching {
        repository.importToml(text).toUi()
    }

    fun exportToml(network: UiNetwork): String = repository.exportToml(network.toConfig())

    fun setThemeMode(mode: ThemeMode) = settingsStore.updateTheme(mode)

    fun setDynamicColor(enabled: Boolean) = settingsStore.updateDynamicColor(enabled)

    fun setLanguage(language: AppLanguage) = settingsStore.updateLanguage(language)

    fun setLogLevel(level: LogLevel) = settingsStore.updateLogLevel(level)

    companion object {
        val IdleStatus = NetworkStatus(
            state = LinkState.Idle,
            myIpv4 = null,
            peers = emptyList(),
            txBps = 0,
            rxBps = 0,
            uptimeSec = 0,
            error = null,
        )

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container.engine, container.configRepository, container.settingsStore) as T
            }
    }
}
