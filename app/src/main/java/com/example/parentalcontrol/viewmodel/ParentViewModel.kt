package com.example.parentalcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentalcontrol.data.repository.ParentRepository
import com.example.parentalcontrol.domain.model.ApprovalResult
import com.example.parentalcontrol.domain.model.ChildDevice
import com.example.parentalcontrol.domain.model.PolicyTemplate
import com.example.parentalcontrol.domain.model.TimeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel para la UI del padre.
 * 
 * TODO: Agregar @HiltViewModel cuando Hilt esté configurado.
 */
@HiltViewModel
class ParentViewModel @Inject constructor(private val repository: ParentRepository) : ViewModel() {

    // Estado de dispositivos — sealed UI state for the four render branches
    // (Loading / Success / Empty / Error). PR 2 of
    // openspec/changes/wire-pairing-and-approval-end-to-end replaces the
    // simple `List<ChildDevice>` flow with a typed UI state so the
    // DashboardScreen can render loading, error, empty, and success states.
    private val _deviceListState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val deviceListState: StateFlow<DeviceListUiState> = _deviceListState.asStateFlow()

    // Legacy alias — pre-PR 2 callers used `devices`. Kept so existing tests
    // and any UI that hasn't migrated still compile.
    private val _devices = MutableStateFlow<List<ChildDevice>>(emptyList())
    val devices: StateFlow<List<ChildDevice>> = _devices.asStateFlow()

    // Solicitudes pendientes
    private val _pendingRequests = MutableStateFlow<List<TimeRequest>>(emptyList())
    val pendingRequests: StateFlow<List<TimeRequest>> = _pendingRequests.asStateFlow()

    // Plantillas disponibles
    private val _templates = MutableStateFlow<List<PolicyTemplate>>(emptyList())
    val templates: StateFlow<List<PolicyTemplate>> = _templates.asStateFlow()

    // Estado de carga
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Result of the most recent approval call. PR 4 of
    // openspec/changes/wire-pairing-and-approval-end-to-end exposes the
    // ApprovalResult (grant_id, minutes, expires_at) returned by the
    // approve-request edge function so the UI can show a confirmation
    // banner with the granted minutes.
    private val _approvalResult = MutableStateFlow<ApprovalResult?>(null)
    val approvalResult: StateFlow<ApprovalResult?> = _approvalResult.asStateFlow()

    // Código de emparejamiento generado
    private val _pairingCode = MutableStateFlow<PairingCodeResult?>(null)
    val pairingCode: StateFlow<PairingCodeResult?> = _pairingCode.asStateFlow()

    init {
        loadDevices()
        loadPendingRequests()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _deviceListState.value = DeviceListUiState.Loading
            try {
                val result = repository.getDevices()
                val list = result.getOrNull()
                if (list != null) {
                    _devices.value = list
                    _deviceListState.value = if (list.isEmpty()) {
                        DeviceListUiState.Empty
                    } else {
                        DeviceListUiState.Success(list)
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Error cargando dispositivos: $msg"
                    _deviceListState.value = DeviceListUiState.Error(msg)
                }
            } catch (e: Exception) {
                _error.value = "Error cargando dispositivos: ${e.message}"
                _deviceListState.value = DeviceListUiState.Error(e.message ?: "Unknown error")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadPendingRequests() {
        viewModelScope.launch {
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // wires the repository to a real REST query that returns
                // Result<List<TimeRequest>>; map success/failure into the
                // existing StateFlows so the RequestCard UI keeps rendering
                // unchanged.
                val result = repository.getPendingRequests()
                val list = result.getOrNull()
                if (list != null) {
                    _pendingRequests.value = list
                } else {
                    _error.value = "Error cargando solicitudes: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error cargando solicitudes: ${e.message}"
            }
        }
    }

    fun loadTemplates() {
        viewModelScope.launch {
            try {
                _templates.value = repository.getTemplates()
            } catch (e: Exception) {
                _error.value = "Error cargando plantillas: ${e.message}"
            }
        }
    }

    fun approveRequest(requestId: String, minutes: Int, response: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // handles the Result<ApprovalResult> returned by the new
                // edge-function call. On success we surface the ApprovalResult
                // (grant_id, minutes, expires_at) via _approvalResult so the
                // parent UI can show a confirmation banner.
                val result = repository.approveRequest(requestId, minutes, response)
                val approval = result.getOrNull()
                if (approval != null) {
                    _approvalResult.value = approval
                    loadPendingRequests()
                    loadDevices() // Refresh para ver versión actualizada
                } else {
                    _error.value = "Error aprobando solicitud: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error aprobando solicitud: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun denyRequest(requestId: String, reason: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // PR 4 of openspec/changes/wire-pairing-and-approval-end-to-end
                // handles the Result<Boolean> returned by the new
                // edge-function call. On success we reload the pending list
                // so the RequestCard disappears.
                val result = repository.denyRequest(requestId, reason)
                if (result.isSuccess) {
                    loadPendingRequests()
                } else {
                    _error.value = "Error denegando solicitud: " +
                        (result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _error.value = "Error denegando solicitud: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createPairingCode(deviceName: String, ageBand: String, ttlMinutes: Int = 10) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.createPairingCode(deviceName, ageBand, ttlMinutes)
                _pairingCode.value = result.getOrNull()
                if (result.isFailure) {
                    _error.value = "Error creando código: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _error.value = "Error creando código: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun grantReward(deviceId: String, minutes: Int, reason: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.grantReward(deviceId, minutes, reason)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error concediendo recompensa: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateDevicePolicy(deviceId: String, templateId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.applyTemplate(deviceId, templateId)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error actualizando política: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun lockDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                repository.lockDevice(deviceId)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error bloqueando dispositivo: ${e.message}"
            }
        }
    }

    fun unlockDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                repository.unlockDevice(deviceId)
                loadDevices()
            } catch (e: Exception) {
                _error.value = "Error desbloqueando dispositivo: ${e.message}"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearPairingCode() {
        _pairingCode.value = null
    }
}

data class PairingCodeResult(
    val code: String,
    val expiresAt: String,
    val deeplink: String
)

/**
 * Sealed UI state for the parent dashboard's device list (PR 2 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`). The four states
 * correspond to the four render branches in `DashboardScreen`:
 * - [Loading]: the network call is in flight; show a centered spinner.
 * - [Success]: the call returned a non-empty list; render `DeviceCard`s.
 * - [Empty]: the call returned an empty list; show the "Pair a new device"
 *   CTA (per `parent-device-list/spec.md`).
 * - [Error]: the call failed; show the error banner with a Retry action.
 */
sealed interface DeviceListUiState {
    object Loading : DeviceListUiState
    data class Success(val items: List<ChildDevice>) : DeviceListUiState
    object Empty : DeviceListUiState
    data class Error(val message: String) : DeviceListUiState
}
