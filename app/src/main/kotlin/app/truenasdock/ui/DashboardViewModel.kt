package app.truenasdock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.truenasdock.api.model.Alert
import app.truenasdock.api.model.Pool
import app.truenasdock.api.model.RealtimeStats
import app.truenasdock.api.model.Service
import app.truenasdock.api.model.ServiceVerb
import app.truenasdock.api.model.SystemInfo
import app.truenasdock.session.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = true,
    val systemInfo: SystemInfo? = null,
    val pools: List<Pool> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val services: List<Service> = emptyList(),
    val realtime: RealtimeStats? = null,
    val error: String? = null,
)

class DashboardViewModel(private val session: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()
    private var realtimeJob: Job? = null

    init {
        refresh()
        observeRealtime()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val api = session.requireApi()
                val info = api.systemInfo()
                val pools = api.pools()
                val alerts = api.alerts()
                val services = api.services()
                _state.value = _state.value.copy(
                    isLoading = false,
                    systemInfo = info,
                    pools = pools,
                    alerts = alerts.filter { !it.dismissed },
                    services = services,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Refresh failed")
            }
        }
    }

    fun dismissAlert(uuid: String) {
        viewModelScope.launch {
            try {
                session.requireApi().dismissAlert(uuid)
                _state.value = _state.value.copy(alerts = _state.value.alerts.filter { it.uuid != uuid })
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not dismiss alert")
            }
        }
    }

    fun controlService(verb: ServiceVerb, service: String) {
        viewModelScope.launch {
            try {
                session.requireApi().controlService(verb, service)
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Service control failed")
            }
        }
    }

    fun logout() {
        realtimeJob?.cancel()
        session.logout()
    }

    private fun observeRealtime() {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            try {
                session.requireApi().realtimeStats(2)
                    .catch { e ->
                        _state.value = _state.value.copy(error = e.message ?: "Live stats failed")
                    }
                    .collect { stats ->
                        _state.value = _state.value.copy(realtime = stats)
                    }
            } catch (_: Exception) {
                // Session torn down (logout) — stop quietly.
            }
        }
    }

    override fun onCleared() {
        realtimeJob?.cancel()
    }

    companion object {
        fun factory(session: SessionManager): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DashboardViewModel(session) as T
                }
            }
    }
}
