package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.domain.model.ApprovalResult
import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceHealth
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.domain.model.PolicyTemplate
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.viewmodel.PairingCodeResult
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Process-level cache of the most recent pending-requests fetch
     * (design D2 of `fix-parent-solicitudes-auto-poll`).
     *
     * Initialized to `emptyList()` so the first UI frame never sees a
     * different value than the VM's `_pendingRequests` initial value.
     * The worker (`SolicitudesPollingWorker`) writes here on every
     * successful 5-min tick; the VM mirrors the value into its own
     * `_pendingRequests` flow via a collector in `init`.
     *
     * Kept as a [StateFlow] (not [kotlinx.coroutines.flow.SharedFlow])
     * because `StateFlow.update {}`-style replacement matches the spec
     * wording at `specs/time-request-approval/spec.md:51` ("post via
     * `StateFlow.update {}`") and gives the VM a single hot replay
     * channel for collectors.
     */
    private val _pendingRequestsFlow = MutableStateFlow<List<TimeRequest>>(emptyList())
    val pendingRequestsFlow: StateFlow<List<TimeRequest>> = _pendingRequestsFlow.asStateFlow()

    /**
     * Public write hook for both the VM's [ParentRepository.getPendingRequests]
     * success path and the worker's `SolicitudesPollingWorker.doWork()`
     * success path. Replaces the value atomically — last write wins, and
     * any active collector on [pendingRequestsFlow] sees the new list on
     * the next emission.
     */
    fun publishPendingRequests(list: List<TimeRequest>) {
        _pendingRequestsFlow.value = list
    }

    /**
     * Obtiene los dispositivos hijos del padre.
     *
     * PR 2 of `openspec/changes/wire-pairing-and-approval-end-to-end`
     * replaces the local mock with a real HTTP call to the
     * `get-devices-for-parent` edge function. The edge function uses the
     * ANON key with the caller's JWT — the RLS policy `devices_parent_select`
     * (`parent_id = auth.uid()`) scopes the rows. We do not need to pass
     * `parent_id` from the client because RLS is the security boundary.
     *
     * Returns [Result.success] with the parsed list on a 2xx response,
     * [Result.failure] with a typed [DeviceListError] otherwise:
     *  - [DeviceListError.AuthMissing] when the parent has no session
     *    (the synthetic hotfix path from `hotfix-parent-auth-session`).
     *  - [DeviceListError.Transient] for any other failure (network,
     *    HTTP non-2xx, parse error).
     */
    suspend fun getDevices(): Result<List<ChildDevice>> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val response = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/get-devices-for-parent"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString<List<DeviceDto>>(response.bodyAsText())
            Result.success(body.map { it.toChildDevice() })
        } catch (e: IllegalStateException) {
            // Preserve the typed AuthMissing contract for any IllegalStateException
            // that bubbles up with the "not authenticated" message (e.g., from
            // the Ktor engine's internal checks).
            if (e.message?.contains("not authenticated") == true) {
                Result.failure(DeviceListError.AuthMissing)
            } else {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }

    /**
     * Obtiene solicitudes de tiempo pendientes para los dispositivos del padre.
     *
     * PR 4 of `openspec/changes/wire-pairing-and-approval-end-to-end`
     * replaces the local mock with a real REST query against
     * `${SUPABASE_URL}/rest/v1/time_requests`. The query is scoped by RLS:
     * the `time_requests_parent_select` policy
     * (`parent_id = auth.uid()`) returns only the parent's own rows. We
     * additionally filter by `status = "PENDING"` as a defense-in-depth so
     * the repository never accidentally surfaces already-decided rows.
     *
     * Returns [Result.success] with the parsed list on a 2xx response,
     * [Result.failure] on a non-2xx response or on any network exception
     * (including the "not authenticated" case).
     */
    suspend fun getPendingRequests(): Result<List<TimeRequest>> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val response = clientProvider.httpClient.get(
                "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/time_requests?" +
                    "select=*,devices(device_name)&status=eq.PENDING&order=created_at.desc"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${response.status}")
                )
            }

            val body = json.decodeFromString<List<TimeRequestDto>>(response.bodyAsText())
            Result.success(body.map { it.toTimeRequest() })
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
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
     * Aprueba una solicitud de tiempo llamando a la edge function
     * `approve-request` (`${SUPABASE_URL}/functions/v1/approve-request`).
     *
     * The wire format sent to the edge function is
     * `{ request_id, minutes, response_text }` — exactly the shape the
     * existing server-side handler at
     * `supabase/functions/approve-request/index.ts:38` already reads. The
     * server inserts a `grants` row with `source = "EXTRA_TIME"` (pre-approved
     * per design §7 Q4 in `openspec/changes/wire-pairing-and-approval-end-to-end/design.md`)
     * and returns `{ success, grant_id, minutes, expires_at }`.
     *
     * PR 4 of `openspec/changes/wire-pairing-and-approval-end-to-end` replaces
     * the local mock with this real HTTP call. Returns [Result.success] with
     * the parsed [ApprovalResult] on a 2xx response, [Result.failure]
     * otherwise.
     */
    suspend fun approveRequest(
        requestId: String,
        minutes: Int,
        response: String?
    ): Result<ApprovalResult> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

            val responseText = jsonStringOrNull(response)
            val body = "{\"request_id\":\"$requestId\",\"minutes\":$minutes,\"response_text\":$responseText}"

            val httpResponse = clientProvider.httpClient.post(
                "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/approve-request"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (!httpResponse.status.isSuccess()) {
                return@withContext Result.failure(
                    DeviceListError.Transient("HTTP ${httpResponse.status}")
                )
            }

            val responseBody = json.decodeFromString<ApproveResponseDto>(httpResponse.bodyAsText())
            Result.success(
                ApprovalResult(
                    success = responseBody.success,
                    grantId = responseBody.grant_id,
                    minutes = responseBody.minutes ?: minutes,
                    expiresAt = responseBody.expires_at
                )
            )
        } catch (e: Exception) {
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
        }
    }

    /**
     * Deniega una solicitud de tiempo.
     *
     * Calls the same `approve-request` edge function with `action = "DENY"`
     * and `minutes = 0`. The current server-side handler does not yet branch
     * on `action` (see `supabase/functions/approve-request/index.ts:38`); the
     * wire format is locked in here and the server-side branch is a tracked
     * follow-up — flagged in PR 4's PR description per design §7 Q5.
     *
     * Returns [Result.success] with `true` on a 2xx response,
     * [Result.failure] otherwise.
     */
    suspend fun denyRequest(requestId: String, reason: String?): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val token = authManager.getAccessToken()
                    ?: return@withContext Result.failure(DeviceListError.AuthMissing)

                val responseText = jsonStringOrNull(reason)
                val body = "{\"request_id\":\"$requestId\",\"minutes\":0,\"action\":\"DENY\"," +
                    "\"response_text\":$responseText}"

                val httpResponse = clientProvider.httpClient.post(
                    "${SupabaseClientProvider.SUPABASE_URL}/functions/v1/approve-request"
                ) {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                if (!httpResponse.status.isSuccess()) {
                    return@withContext Result.failure(
                        DeviceListError.Transient("HTTP ${httpResponse.status}")
                    )
                }

                Result.success(true)
            } catch (e: Exception) {
                Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
            }
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
                ?: return@withContext Result.failure(DeviceListError.AuthMissing)

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
                    DeviceListError.Transient("HTTP ${response.status}")
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
            Result.failure(DeviceListError.Transient(e.message ?: "Unknown error"))
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
     * Serializes a possibly-null string into a JSON string-or-null literal.
     * Used by [approveRequest] and [denyRequest] to embed an optional
     * `response_text` field without writing the JSON by hand. Pulled out to
     * keep the call sites short enough to fit inside ktlint's 120-char
     * max-line-length rule.
     */
    private fun jsonStringOrNull(value: String?): String =
        if (value == null) "null" else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

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
    ): List<com.tudominio.parentalcontrol.domain.model.UsageStats> =
        withContext(Dispatchers.IO) {
            listOf(
                com.tudominio.parentalcontrol.domain.model.UsageStats(
                    deviceId = deviceId,
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    date = date,
                    minutesUsed = 45,
                    limitMinutes = 60,
                    remainingMinutes = 15
                ),
                com.tudominio.parentalcontrol.domain.model.UsageStats(
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

    /**
     * Wire shape returned by `${SUPABASE_URL}/rest/v1/time_requests` when
     * called with `select=*,devices(device_name)`. The `device_name` field
     * is joined from the `devices` table by Supabase's resource embedding.
     * Status values are the [RequestStatus] enum names (`PENDING`,
     * `APPROVED`, `DENIED`).
     */
    @Serializable
    private data class TimeRequestDto(
        val id: String,
        val device_id: String,
        val device_name: String? = null,
        val package_name: String? = null,
        val app_name: String? = null,
        val minutes_requested: Int,
        val reason: String? = null,
        val status: String,
        val created_at: String,
        val responded_at: String? = null,
        val parent_response: String? = null
    ) {
        fun toTimeRequest(): TimeRequest = TimeRequest(
            id = id,
            deviceId = device_id,
            deviceName = device_name,
            packageName = package_name,
            appName = app_name,
            minutesRequested = minutes_requested,
            reason = reason,
            status = when (status.uppercase()) {
                "APPROVED" -> RequestStatus.APPROVED
                "DENIED" -> RequestStatus.DENIED
                else -> RequestStatus.PENDING
            },
            createdAt = created_at,
            respondedAt = responded_at,
            parentResponse = parent_response
        )
    }

    /**
     * Wire shape returned by the `approve-request` edge function on a
     * successful approval. Mirrors the JSON keys in
     * `supabase/functions/approve-request/index.ts:147-156`.
     */
    @Serializable
    private data class ApproveResponseDto(
        val success: Boolean,
        val grant_id: String? = null,
        val minutes: Int? = null,
        val expires_at: String? = null
    )

    /**
     * Wire shape returned by the `get-devices-for-parent` edge function
     * (Supabase REST row). Mirrors the columns selected in
     * `supabase/functions/get-devices-for-parent/index.ts:73-87`. The
     * `device_state` column is stored as the Postgres enum (string) and
     * maps to [DeviceState] via [toChildDevice].
     *
     * Change A of `feat-multi-child-picker` (design §A.6 / §A.8) adds
     * `child_id` + `child_first_name` so the parser can hydrate
     * `ChildDevice.child = Child(...)` when the wire carries a child.
     * Both fields default-null to keep back-compat with devices paired
     * BEFORE the `children` table migration — those rows keep a `null`
     * `child` and the dashboard surfaces "Sin asignar".
     */
    @Serializable
    private data class DeviceDto(
        val id: String,
        val device_name: String,
        val device_model: String? = null,
        val os_version: String? = null,
        val app_version: String = "1.0.0",
        val device_state: String = "ACTIVE",
        val policy_version: Int = 1,
        val last_seen_at: String,
        val child_id: String? = null,
        val child_first_name: String? = null
    ) {
        fun toChildDevice(): ChildDevice = ChildDevice(
            id = id,
            name = device_name,
            model = device_model,
            appVersion = app_version,
            policyVersion = policy_version,
            state = when (device_state.uppercase()) {
                "LOCKED" -> DeviceState.LOCKED
                "DOWNTIME" -> DeviceState.DOWNTIME
                else -> DeviceState.ACTIVE
            },
            lastSeenAt = last_seen_at,
            // Devices are considered "online" if their last heartbeat is
            // within the past 5 minutes. The edge function returns the ISO
            // timestamp from `devices.last_seen_at` (default NOW()). Use a
            // forgiving ISO parser so a malformed value degrades to offline
            // instead of crashing the dashboard.
            isOnline = runCatching {
                val instant = java.time.Instant.parse(last_seen_at)
                val ageMs = System.currentTimeMillis() - instant.toEpochMilli()
                ageMs in 0..(5 * 60 * 1000L)
            }.getOrDefault(false),
            // Hydrate the child only when BOTH wire fields are present;
            // any other shape (both-null, only-id, only-name) leaves
            // `child = null` so the dashboard falls through to the
            // "Sin asignar" surface per design A.6. The `parentId` /
            // `createdAt` / `updatedAt` are empty on the device payload —
            // a future `getChildren()` call hydrates them (design A.6).
            child = if (child_id != null && child_first_name != null) {
                Child(
                    id = child_id,
                    parentId = "",
                    firstName = child_first_name,
                    createdAt = "",
                    updatedAt = ""
                )
            } else {
                null
            }
        )
    }
}
