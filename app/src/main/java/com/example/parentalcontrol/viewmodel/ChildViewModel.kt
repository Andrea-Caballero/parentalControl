package com.example.parentalcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentalcontrol.data.repository.ChildRepository
import com.example.parentalcontrol.domain.model.AppPolicy
import com.example.parentalcontrol.domain.model.BlockedState
import com.example.parentalcontrol.domain.model.Policy
import com.example.parentalcontrol.domain.model.TimeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel para la UI del niño.
 */
class ChildViewModel : ViewModel() {

    private val repository = ChildRepository()

    // Política actual
    private val _policy = MutableStateFlow<Policy?>(null)
    val policy: StateFlow<Policy?> = _policy.asStateFlow()

    // Estado de bloqueo
    private val _blockedState = MutableStateFlow(BlockedState.NOT_BLOCKED)
    val blockedState: StateFlow<BlockedState> = _blockedState.asStateFlow()

    // Tiempo usado hoy
    private val _dailyUsageMinutes = MutableStateFlow(0)
    val dailyUsageMinutes: StateFlow<Int> = _dailyUsageMinutes.asStateFlow()

    // Límite diario
    private val _dailyLimitMinutes = MutableStateFlow(120)
    val dailyLimitMinutes: StateFlow<Int> = _dailyLimitMinutes.asStateFlow()

    // Solicitudes pendientes
    private val _pendingRequests = MutableStateFlow<List<TimeRequest>>(emptyList())
    val pendingRequests: StateFlow<List<TimeRequest>> = _pendingRequests.asStateFlow()

    // Tiempo restante
    private val _remainingMinutes = MutableStateFlow(0)
    val remainingMinutes: StateFlow<Int> = _remainingMinutes.asStateFlow()

    // Uso por app
    private val _appsUsage = MutableStateFlow<List<AppUsage>>(emptyList())
    val appsUsage: StateFlow<List<AppUsage>> = _appsUsage.asStateFlow()

    // Solicitudes activas (ya aprobadas, grants vigentes)
    private val _activeGrants = MutableStateFlow<List<ActiveGrant>>(emptyList())
    val activeGrants: StateFlow<List<ActiveGrant>> = _activeGrants.asStateFlow()

    // Loading
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Mensaje del padre
    private val _parentMessage = MutableStateFlow<String?>(null)
    val parentMessage: StateFlow<String?> = _parentMessage.asStateFlow()

    init {
        syncPolicy()
    }

    fun syncPolicy() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val fetchedPolicy = repository.getPolicy()
                _policy.value = fetchedPolicy
                _dailyLimitMinutes.value = fetchedPolicy.dailyScreenTimeMinutes
                
                // Calcular tiempo restante
                updateRemainingTime()
                refreshAppsUsage()
            } catch (e: Exception) {
                _error.value = "Error sincronizando política: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun checkBlocked(packageName: String) {
        viewModelScope.launch {
            val state = repository.checkBlocked(packageName, _policy.value)
            _blockedState.value = state
            
            if (state != BlockedState.NOT_BLOCKED) {
                // Sincronizar mensaje del padre
                repository.getParentMessage()?.let {
                    _parentMessage.value = it
                }
            }
        }
    }

    fun requestTime(packageName: String?, minutesRequested: Int, reason: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.requestTime(packageName, minutesRequested, reason)
                // Recargar solicitudes pendientes
                _pendingRequests.value = repository.getPendingRequests()
            } catch (e: Exception) {
                _error.value = "Error enviando solicitud: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun recordUsage(packageName: String, minutes: Int) {
        viewModelScope.launch {
            repository.recordUsage(packageName, minutes)
            _dailyUsageMinutes.value += minutes
            updateRemainingTime()
            refreshAppsUsage()
        }
    }

    fun getAppsUsage(): List<AppUsage> {
        return _appsUsage.value
    }

    private fun refreshAppsUsage() {
        viewModelScope.launch {
            val usage = repository.getAppsUsage()
            _appsUsage.value = usage.map { (pkg, mins) ->
                val appPolicy = _policy.value?.appPolicies?.find { it.packageName == pkg }
                AppUsage(
                    packageName = pkg,
                    appName = pkg.substringAfterLast("."),
                    minutesUsed = mins,
                    limitMinutes = appPolicy?.dailyLimitMinutes,
                    remainingMinutes = appPolicy?.dailyLimitMinutes?.let { maxOf(0, it - mins) }
                )
            }
        }
    }

    private fun updateRemainingTime() {
        val used = _dailyUsageMinutes.value
        val limit = _dailyLimitMinutes.value
        val grantsMinutes = _activeGrants.value.sumOf { it.minutesRemaining }
        
        _remainingMinutes.value = maxOf(0, limit - used + grantsMinutes)
    }

    fun sendHeartbeat() {
        viewModelScope.launch {
            repository.sendHeartbeat(
                batteryLevel = null,
                isCharging = false,
                appInForeground = null
            )
        }
    }

    fun clearError() {
        _error.value = null
    }
}

data class ActiveGrant(
    val minutes: Int,
    val minutesRemaining: Int,
    val expiresAt: String
)

data class AppUsage(
    val packageName: String,
    val appName: String,
    val minutesUsed: Int,
    val limitMinutes: Int?,
    val remainingMinutes: Int?
)
