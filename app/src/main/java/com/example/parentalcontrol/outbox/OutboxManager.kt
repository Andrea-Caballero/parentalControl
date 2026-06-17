package com.example.parentalcontrol.outbox

import android.content.Context
import android.util.Log
import com.example.parentalcontrol.data.local.AppDatabase
import com.example.parentalcontrol.data.local.OutboxEntity
import com.example.parentalcontrol.data.local.TimeRequestEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manager para la outbox de solicitudes offline.
 * 
 * Encola solicitudes cuando no hay conexión y las sincroniza cuando se recupera.
 */
class OutboxManager private constructor(context: Context) {

    companion object {
        private const val TAG = "OutboxManager"
        
        private const val MAX_RETRIES = 3

        @Volatile
        private var instance: OutboxManager? = null

        fun getInstance(context: Context): OutboxManager {
            return instance ?: synchronized(this) {
                instance ?: OutboxManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val database = AppDatabase.getInstance(context)
    private val outboxDao = database.outboxDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Encola una solicitud de tiempo extra para envío posterior.
     */
    suspend fun enqueueTimeRequest(request: TimeRequestEntity): Boolean {
        return try {
            val payload = """
                {
                    "request_id": "${request.request_id}",
                    "device_id": "${request.device_id}",
                    "requested_minutes": ${request.minutes_requested},
                    "reason": "${request.reason}",
                    "created_at": ${request.created_at}
                }
            """.trimIndent()

            val dedupKey = "time_request_${request.request_id}"

            val outboxItem = OutboxEntity(
                tipo = "time_request",
                payload_json = payload,
                dedup_key = dedupKey,
                created_at = java.time.Instant.now().toString(),
                server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
            )

            outboxDao.insertOutboxItem(outboxItem)

            Log.d(TAG, "Time request enqueued: ${request.request_id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueueing time request: ${e.message}")
            false
        }
    }

    /**
     * Encola un evento genérico para envío posterior.
     */
    suspend fun enqueueEvent(eventType: String, payload: Map<String, Any>): Boolean {
        return try {
            val jsonPayload = payload.entries.joinToString(",") { (k, v) ->
                "\"$k\": ${if (v is String) "\"$v\"" else v}"
            }
            
            val dedupKey = "${eventType}_${System.currentTimeMillis()}"
            
            val outboxItem = OutboxEntity(
                tipo = eventType,
                payload_json = "{ $jsonPayload }",
                dedup_key = dedupKey,
                created_at = java.time.Instant.now().toString(),
                server_date = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
            )
            
            outboxDao.insertOutboxItem(outboxItem)
            
            Log.d(TAG, "Event enqueued: $eventType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enqueueing event: ${e.message}")
            false
        }
    }

    /**
     * Obtiene el número de elementos pendientes.
     */
    suspend fun getPendingCount(): Int {
        return try {
            outboxDao.getPendingCountFlow().first()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting pending count: ${e.message}")
            0
        }
    }

    /**
     * Limpia items fallidos.
     */
    suspend fun cleanupFailedItems() {
        try {
            outboxDao.deleteFailedItems(MAX_RETRIES)
            Log.d(TAG, "Cleaned up failed items")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up: ${e.message}")
        }
    }
}
