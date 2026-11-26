package app.timiao.et.debug

import app.timiao.et.core.Engine
import app.timiao.et.core.LinkState
import app.timiao.et.core.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Offline engine for screenshot builds: connected with a small live-rate wobble, no kernel. */
class DemoEngine : Engine {
    private val _status = MutableStateFlow(mapOf(DemoData.ID to DemoData.status()))
    override val status: StateFlow<Map<String, NetworkStatus>> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tick = 0

    init {
        scope.launch {
            while (true) {
                delay(1_000)
                tick++
                _status.update { current ->
                    current.mapValues { (_, status) ->
                        if (status.state != LinkState.Connected) {
                            status
                        } else {
                            status.copy(
                                txBps = 18_400L + (tick * 137L) % 900L,
                                rxBps = 42_100L + (tick * 211L) % 1_600L,
                                uptimeSec = status.uptimeSec + 1,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun start(id: String) {
        val base = _status.value[id] ?: DemoData.status()
        _status.update { it + (id to base.copy(state = LinkState.Connected, error = null)) }
    }

    override suspend fun stop(id: String) {
        val base = _status.value[id] ?: DemoData.status()
        _status.update {
            it + (id to base.copy(state = LinkState.Idle, peers = emptyList(), txBps = 0, rxBps = 0, uptimeSec = 0))
        }
    }
}
