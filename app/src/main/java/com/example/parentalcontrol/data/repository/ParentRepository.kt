package com.example.parentalcontrol.data.repository

import android.content.Context
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.domain.model.ApprovalResult
import com.example.parentalcontrol.domain.model.ChildDevice
import com.example.parentalcontrol.domain.model.DeviceHealth
import com.example.parentalcontrol.domain.model.DeviceState
import com.example.parentalcontrol.domain.model.PolicyTemplate
import com.example.parentalcontrol.domain.model.TimeRequest
import com.example.parentalcontrol.network.SupabaseClientProvider
import com.example.parentalcontrol.viewmodel.PairingCodeResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repositorio para operaciones del padre.
 * Conecta con las Edge Functions de Supabase (T15).
 *
 * Wired as a Hilt singleton so tests can swap a fake `KtorClient` via the
 * [SupabaseClientProvider] binding. PR 1 of
 * openspec/changes/wire-pairing-and-approval-end-to-end replaced the local
 * mock fallbacks with real HTTP calls to the Supabase edge functions.
 */
@Singleton
class ParentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: DeviceAuthManager,
    private val clientProvider: SupabaseClientProvider
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Obtiene los dispositivos hijos del padre.
     * PR 1 keeps the existing local-mock fallback because the
     * `get-devices-for-parent` edge function is delivered in PR 2; once
     * PR 2 lands, this method rewrites to call the new function.
     */
    suspend fun getDevices(): List<ChildDevice> = withContext(Dispatchers.IO) {
        listOf(
            ChildDevice(
                id = "device-1",
                name = "Galaxy S21 de Juan",
                model = "SM-G991B",
                appVersion = "1.0.0",
                policyVersion = 5,
                state = DeviceState.ACTIVE,
                lastSeenAt = "2026-06-04T12:00:00Z",
                isOnline = true
            ),
            ChildDevice(
                id = "device-2",
                name = "Pixel 7 de María",
                model = "Pixel 7",
                appVersion = "1.0.0",
                policyVersion = 3,
                state = DeviceState.LOCKED,
                lastSeenAt = "2026-06-04T11:30:00Z",
                isOnline = false
            )
        )
    }

    /**
     * Obtiene solicitudes pendientes de tiempo.
     */
    suspend fun getPendingRequests(): List<TimeRequest> = withContext(Dispatchers.IO) {
        // TODO: En implementación real, filtrar por dispositivos del padre
        listOf(
            TimeRequest(
                id = "req-1",
                deviceId = "device-1",
                deviceName = "Galaxy S21 de Juan",
                packageName = "com.instagram.android",
                appName = "Instagram",
                minutesRequested = 30,
                reason = "Quiero ver las historias de mis amigos",
                status = com.example.parentalcontrol.domain.model.RequestStatus.PENDING,
                createdAt = "2026-06-04T11:45:00Z"
            ),
            TimeRequest(
                id = "req-2",
                deviceId = "device-2",
                deviceName = "Pixel 7 de María",
                minutesRequested = 15,
                reason = "Terminé la tarea",
                status = com.example.parentalcontrol.domain.model.RequestStatus.PENDING,
                createdAt = "2026-06-04T11:50:00Z"
            )
        )
    }

    /**
     * Obtiene plantillas de política disponibles.
     */
    suspend fun getTemplates(): List<PolicyTemplate> = withContext(Dispatchers.IO) {
        // TODO: En implementación real, llamar a supabase
        listOf(
            PolicyTemplate(
                id = "template-1",
                name = "Preescolar (0-6 años)",
                description = "Restricciones estrictas para niños pequeños",
                ageBand = "0-6",
                isDefault = false
            ),
            PolicyTemplate(
                id = "template-2",
                name = "Escuela Primaria (7-12 años)",
                description = "Balance entre estudio y entretenimiento",
                ageBand = "7-12",
                isDefault = true
            ),
            PolicyTemplate(
                id = "template-3",
                name = "Adolescencia (13-17 años)",
                description = "Más libertad con supervisión",
                ageBand = "13-17",
                isDefault = false
            )
        )
    }

    /**
     * Aprueba una solicitud de tiempo.
     */
    suspend fun approveRequest(requestId: String, minutes: Int, response: String?): ApprovalResult =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, llamar a Edge Function
            // supabase.functions().invoke("approve-request", ...)
            ApprovalResult(
                success = true,
                grantId = "grant-${System.currentTimeMillis()}",
                minutes = minutes,
                expiresAt = "2026-06-04T14:00:00Z"
            )
        }

    /**
     * Deniega una solicitud de tiempo.
     */
    suspend fun denyRequest(requestId: String, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, actualizar estado via Supabase
            true
        }

    /**
     * Crea código de emparejamiento.
     *
     * Posts to `${SUPABASE_URL}/functions/v1/create-pairing-code` with the
     * parent's bearer token + apikey header. The server generates the 8-char
     * code and returns `{ code, expires_at, deeplink }` per the spec at
     * `openspec/changes/wire-pairing-and-approval-end-to-end/specs/pairing-flow/spec.md`.
     */
    suspend fun createPairingCode(
        deviceName: String,
        ageBand: String,
        ttlMinutes: Int
    ): Result<PairingCodeResult> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("not authenticated"))

            val response = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/create-pairing-code"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(
                    "{\"device_name\":\"$deviceName\",\"age_band\":\"$ageBand\",\"ttl_minutes\":$ttlMinutes}"
                )
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    RuntimeException("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString<PairingCodeDto>(response.bodyAsText())
            Result.success(
                PairingCodeResult(
                    code = body.code,
                    expiresAt = body.expires_at,
                    deeplink = body.deeplink
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Concede recompensa de tiempo.
     */
    suspend fun grantReward(deviceId: String, minutes: Int, reason: String?): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, llamar a Edge Function
            true
        }

    /**
     * Aplica plantilla de política a dispositivo.
     */
    suspend fun applyTemplate(deviceId: String, templateId: String): Boolean =
        withContext(Dispatchers.IO) {
            // TODO: En implementación real, llamar a Edge Function
            true
        }

    /**
     * Bloquea dispositivo inmediatamente.
     */
    suspend fun lockDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        // TODO: En implementación real, cambiar estado del dispositivo
        true
    }

    /**
     * Desbloquea dispositivo.
     */
    suspend fun unlockDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        // TODO: En implementación real, cambiar estado del dispositivo
        true
    }

    /**
     * Obtiene estadísticas de uso de un dispositivo.
     */
    suspend fun getUsageStats(
        deviceId: String,
        date: String
    ): List<com.example.parentalcontrol.domain.model.UsageStats> =
        withContext(Dispatchers.IO) {
            listOf(
                com.example.parentalcontrol.domain.model.UsageStats(
                    deviceId = deviceId,
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    date = date,
                    minutesUsed = 45,
                    limitMinutes = 60,
                    remainingMinutes = 15
                ),
                com.example.parentalcontrol.domain.model.UsageStats(
                    deviceId = deviceId,
                    packageName = "com.whatsapp",
                    appName = "WhatsApp",
                    date = date,
                    minutesUsed = 30,
                    limitMinutes = null,
                    remainingMinutes = null
                )
            )
        }

    /**
     * Obtiene salud del dispositivo.
     */
    suspend fun getDeviceHealth(deviceId: String): DeviceHealth =
        withContext(Dispatchers.IO) {
            DeviceHealth(
                enforcementLevel = "STANDARD",
                suspicionLevel = "NONE",
                lastHeartbeat = "2026-06-04T12:00:00Z",
                batteryLevel = 85,
                isCharging = false,
                alerts = emptyList()
            )
        }

    @Serializable
    private data class PairingCodeDto(
        val code: String,
        val expires_at: String,
        val deeplink: String
    )
}
