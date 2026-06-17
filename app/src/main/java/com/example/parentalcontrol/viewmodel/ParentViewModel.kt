package com.example.parentalcontrol.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parentalcontrol.data.repository.ParentRepository
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
class ParentViewModel @Inject constructor() : ViewModel() {

    private val repository = ParentRepository()

    // Estado de dispositivos
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
            try {
                _devices.value = repository.getDevices()
            } catch (e: Exception) {
                _error.value = "Error cargando dispositivos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadPendingRequests() {
        viewModelScope.launch {
            try {
                _pendingRequests.value = repository.getPendingRequests()
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
                repository.approveRequest(requestId, minutes, response)
                loadPendingRequests()
                loadDevices() // Refresh para ver versión actualizada
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
                repository.denyRequest(requestId, reason)
                loadPendingRequests()
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
                _pairingCode.value = repository.createPairingCode(deviceName, ageBand, ttlMinutes)
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
