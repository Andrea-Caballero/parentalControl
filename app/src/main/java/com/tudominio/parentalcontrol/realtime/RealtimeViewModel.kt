package com.tudominio.parentalcontrol.realtime

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.sync.SyncManager
import android.util.Log
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel que observa cambios de Realtime y refresca la UI.
 * 
 * Se suscribe automáticamente al lifecycle - conecta en foreground,
 * desconecta en background.
 */
class RealtimeViewModel(
    private val context: Context
) : ViewModel() {

    private val realtimeManager = RealtimeManager.getInstance(context)
    private val syncManager = SyncManager.getInstance(context)
    private val database = ParentalDatabase.getInstance(context)

    // Estado de conexión
    val connectionState = realtimeManager.connectionState
    val isConnected = realtimeManager.isConnected

    // Eventos para UI
    private val _uiEvents = MutableSharedFlow<UiRefreshEvent>(replay = 0)
    val uiEvents: SharedFlow<UiRefreshEvent> = _uiEvents.asSharedFlow()

    init {
        observeRealtimeEvents()
    }

    /**
     * Observa eventos de Realtime y dispara refresh de UI.
     */
    private fun observeRealtimeEvents() {
        viewModelScope.launch {
            // Cambio de política
            realtimeManager.policyChange.collect { event ->
                Log.d(TAG, "Política actualizada a v${event.newVersion}")
                
                // Refrescar política desde servidor
                syncManager.pullPolicy()
                
                // Notificar UI
                _uiEvents.emit(UiRefreshEvent.PolicyChanged(event.newVersion))
            }
        }

        viewModelScope.launch {
            // Cambio de grants
            realtimeManager.grantChange.collect { event ->
                Log.d(TAG, "Grant ${event.type}: ${event.grantId}")
                
                // Refrescar grants
                refreshGrants()
                
                // Notificar UI
                _uiEvents.emit(
                    UiRefreshEvent.GrantsChanged(
                        type = when (event.type) {
                            GrantChangeEvent.Type.CREATED -> UiRefreshEvent.GrantsChanged.ChangeType.CREATED
                            GrantChangeEvent.Type.REVOKED -> UiRefreshEvent.GrantsChanged.ChangeType.REVOKED
                        },
                        grantId = event.grantId
                    )
                )
            }
        }

        viewModelScope.launch {
            // Cambio de solicitudes
            realtimeManager.requestChange.collect { event ->
                Log.d(TAG, "Solicitud ${event.type}: ${event.requestId}")
                
                // Refrescar solicitudes
                refreshRequests()
                
                // Notificar UI
                _uiEvents.emit(
                    UiRefreshEvent.RequestChanged(
                        type = when (event.type) {
                            RequestChangeEvent.Type.APPROVED -> UiRefreshEvent.RequestChanged.ChangeType.APPROVED
                            RequestChangeEvent.Type.DENIED -> UiRefreshEvent.RequestChanged.ChangeType.DENIED
                            RequestChangeEvent.Type.UPDATED -> UiRefreshEvent.RequestChanged.ChangeType.UPDATED
                        },
                        requestId = event.requestId
                    )
                )
            }
        }
    }

    /**
     * Refresca grants desde la base de datos local.
     */
    private suspend fun refreshGrants() {
        // Los grants se actualizan cuando syncManager.pullPolicy() se ejecuta
        // Aquí solo invalidate caches si es necesario
    }

    /**
     * Refresca solicitudes desde la base de datos local.
     */
    private suspend fun refreshRequests() {
        // Las solicitudes se guardan localmente
        // Invalidar cache si es necesario
    }

    /**
     * Fuerza un refresh completo de la UI.
     */
    fun forceRefresh() {
        viewModelScope.launch {
            syncManager.sync()
            _uiEvents.emit(UiRefreshEvent.FullRefresh)
        }
    }

    /**
     * Conecta manualmente (útil para testing).
     */
    fun connect() {
        realtimeManager.connect()
    }

    /**
     * Desconecta manualmente.
     */
    fun disconnect() {
        realtimeManager.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        realtimeManager.disconnect()
    }

    companion object {
        private const val TAG = "RealtimeViewModel"
    }
}

/**
 * Factory para crear RealtimeViewModel con contexto.
 */
class RealtimeViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RealtimeViewModel::class.java)) {
            return RealtimeViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/**
 * Eventos de refresh para la UI.
 */
sealed class UiRefreshEvent {
    data class PolicyChanged(val newVersion: Long) : UiRefreshEvent()
    
    data class GrantsChanged(
        val type: ChangeType,
        val grantId: String?
    ) : UiRefreshEvent() {
        enum class ChangeType {
            CREATED,
            REVOKED
        }
    }
    
    data class RequestChanged(
        val type: ChangeType,
        val requestId: String?
    ) : UiRefreshEvent() {
        enum class ChangeType {
            APPROVED,
            DENIED,
            UPDATED
        }
    }
    
    data object FullRefresh : UiRefreshEvent()
}
