package com.tudominio.parentalcontrol.sync

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.AppPolicyEntity
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.data.model.WindowEntity
import com.tudominio.parentalcontrol.network.ConnectionState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

enum class OutboxEventType {
    USAGE_LOG,
    DEVICE_ALERT,
    BEHAVIORAL_EVENT,
    TIME_REQUEST,
    HEARTBEAT
}

sealed class SyncResult {
    data object Success : SyncResult()
    data class PartialSuccess(val failedItems: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
    data object Offline : SyncResult()
}

/**
 * Result of a single outbox-item send attempt.
 *
 * - [Success]: the receiver accepted the row.
 * - [RetryableFailure]: a transient error (5xx, 408, 429, IOException). The
 *   row is still in the outbox and will be retried on the next drain cycle.
 * - [PermanentFailure]: a non-recoverable error (4xx other than 408/429).
 *   The caller decides whether to mark the row processed (so the worker does
 *   not loop forever) or to keep it pending for a manual fix-up.
 */
sealed class OutboxSendResult {
    data object Success : OutboxSendResult()
    data class RetryableFailure(val cause: Throwable) : OutboxSendResult()
    data class PermanentFailure(val statusCode: Int) : OutboxSendResult()
}

enum class SyncStatus {
    IDLE,
    SYNCING,
    OFFLINE,
    PENDING_ITEMS,
    ERROR
}

@Serializable
data class PolicyPullResponse(
    val policy_id: String? = null,
    val version: Long,
    val category_assignments: Map<String, String>? = null,
    val app_policies: List<AppPolicyResponse>? = null,
    val server_time: Long? = null
)

@Serializable
data class AppPolicyResponse(
    val package_name: String,
    val state: String,
    val daily_limit_minutes: Int? = null,
    val allowed_windows: List<String>? = null
)

@Singleton
class SyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private var database: ParentalDatabase
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5000L
        private const val SYNC_INTERVAL_MS = 60_000L

        /**
         * Convenience accessor for non-Hilt call sites (legacy Workers
         * constructed by `WorkManager` outside the `@HiltWorker` graph).
         * Production code MUST inject the manager directly.
         */
        fun getInstance(context: Context): SyncManager {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncManagerEntryPoint::class.java
            )
            return entryPoint.syncManager()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _serverTimeOffset = MutableStateFlow(0L)
    val serverTimeOffset: StateFlow<Long> = _serverTimeOffset.asStateFlow()

    private var syncJob: Job? = null
    private var periodicSyncJob: Job? = null

    /**
     * The Ktor HTTP client used to talk to Supabase. Public so the
     * [com.tudominio.parentalcontrol.workers.OutboxDrainer] can guard against
     * a not-yet-initialized client and return `Result.retry()` instead of
     * silently no-op'ing the send.
     *
     * The `setHttpClient` accessor is auto-generated from this property;
     * callers that previously used the explicit setter should now write to
     * the property directly (`syncManager.httpClient = client`).
     */
    var httpClient: HttpClient? = null

    init {
        scope.launch { updatePendingCount() }
    }

    fun setDatabase(db: ParentalDatabase) {
        database = db
    }

    fun startPeriodicSync() {
        if (periodicSyncJob?.isActive == true) return

        periodicSyncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (isActive) {
                    sync()
                }
            }
        }
    }

    fun stopPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = null
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        _syncStatus.value = SyncStatus.SYNCING

        if (clientProvider.connectionState.value != ConnectionState.CONNECTED) {
            _syncStatus.value = SyncStatus.OFFLINE
            return@withContext SyncResult.Offline
        }

        var pullSuccess = false
        var pushFailedCount = 0

        val pullResult = pullPolicy()
        pullSuccess = pullResult is SyncResult.Success

        val pushResult = drainOutbox()
        pushFailedCount = when (pushResult) {
            is SyncResult.Success -> 0
            is SyncResult.PartialSuccess -> pushResult.failedItems
            is SyncResult.Error -> -1
            is SyncResult.Offline -> -1
        }

        refreshFcmTokenIfNeeded()

        _lastSyncTime.value = System.currentTimeMillis()
        updatePendingCount()

        _syncStatus.value = if (pushFailedCount == 0) SyncStatus.IDLE else SyncStatus.PENDING_ITEMS

        return@withContext when {
            pushFailedCount == 0 -> SyncResult.Success
            pushFailedCount > 0 -> SyncResult.PartialSuccess(pushFailedCount)
            else -> SyncResult.Error("Sincronización parcial")
        }
    }

    suspend fun pullPolicy(): SyncResult = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext SyncResult.Offline

        val deviceId = authManager.deviceId.value ?: "default"

        return@withContext try {
            val localVersion = database.policyDao().getLocalVersion(deviceId) ?: 0L

            val response = httpClient?.get("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/get-policy") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
            } ?: return@withContext SyncResult.Error("HTTP client no configurado")

            when {
                response.status == HttpStatusCode.NotModified -> {
                    SyncResult.Success
                }
                response.status.isSuccess() -> {
                    val body = response.bodyAsText()
                    val policyResponse = Json.decodeFromString<PolicyPullResponse>(body)

                    if (policyResponse.version > localVersion) {
                        applyPolicy(policyResponse, deviceId)
                    }

                    policyResponse.server_time?.let { serverTime ->
                        val localTime = System.currentTimeMillis() / 1000
                        _serverTimeOffset.value = serverTime - localTime
                    }

                    SyncResult.Success
                }
                response.status == HttpStatusCode.Unauthorized -> {
                    SyncResult.Offline
                }
                else -> {
                    SyncResult.Error("Error ${response.status}")
                }
            }
        } catch (e: Exception) {
            SyncResult.Offline
        }
    }

    suspend fun drainOutbox(): SyncResult = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext SyncResult.Offline

        var failedCount = 0

        while (true) {
            val pendingItems = database.outboxDao().getPendingItems(MAX_RETRY_ATTEMPTS, 50)
            if (pendingItems.isEmpty()) break

            for (item in pendingItems) {
                when (sendOutboxItem(item, accessToken)) {
                    is OutboxSendResult.Success ->
                        database.outboxDao().deleteItem(item.id)
                    is OutboxSendResult.RetryableFailure -> {
                        database.outboxDao().incrementRetries(item.id)
                        failedCount++
                    }
                    is OutboxSendResult.PermanentFailure -> {
                        // Legacy drain path: leave the row in place and count
                        // it as a failure. The new [OutboxDrainer] worker is
                        // the canonical path that marks permanent failures as
                        // processed (PR 3).
                        failedCount++
                    }
                }
            }
        }

        return@withContext if (failedCount == 0) {
            SyncResult.Success
        } else {
            SyncResult.PartialSuccess(failedCount)
        }
    }

    suspend fun enqueue(
        eventType: OutboxEventType,
        payload: Map<String, Any>,
        dedupKey: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val key = dedupKey ?: UUID.randomUUID().toString()

        val existing = database.outboxDao().findByDedupKey(key)
        if (existing != null) {
            return@withContext false
        }

        val outboxEntity = OutboxEntity(
            tipo = eventType.name,
            payload_json = Json.encodeToString(payload),
            dedup_key = key,
            created_at = java.time.Instant.now().toString(),
            server_date = java.time.LocalDate.now(ZoneOffset.UTC).toString()
        )

        database.outboxDao().insertOutboxItem(outboxEntity)
        updatePendingCount()

        scope.launch {
            if (clientProvider.connectionState.value == ConnectionState.CONNECTED) {
                drainOutbox()
            }
        }

        return@withContext true
    }

    suspend fun sendHeartbeat(enforcementLevel: String? = null): Boolean = withContext(Dispatchers.IO) {
        val accessToken = authManager.getAccessToken()
            ?: return@withContext false

        return@withContext try {
            val body = buildJsonObject {
                enforcementLevel?.let { put("enforcement_level", it) }
                put("client_ts", java.time.Instant.now().toString())
            }

            val response = httpClient?.post("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/heartbeat") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }

            response?.status?.isSuccess() == true
        } catch (e: Exception) {
            false
        }
    }

    fun getServerTime(): Long {
        return System.currentTimeMillis() / 1000 + _serverTimeOffset.value
    }

    private suspend fun applyPolicy(response: PolicyPullResponse, deviceId: String) {
        val policyEntity = PolicyEntity(
            device_id = deviceId,
            version = response.version,
            category_assignments = response.category_assignments ?: emptyMap()
        )
        database.policyDao().upsertPolicyIfNewer(policyEntity)

        response.app_policies?.forEach { appPolicy ->
            val windows = appPolicy.allowed_windows?.map { windowStr ->
                WindowEntity(days = emptyList(), from = windowStr, to = windowStr)
            } ?: emptyList()
            val entity = AppPolicyEntity(
                package_name = appPolicy.package_name,
                device_id = deviceId,
                state = appPolicy.state,
                daily_limit_minutes = appPolicy.daily_limit_minutes,
                allowed_windows = windows,
                category = null
            )
            database.appPolicyDao().upsertAppPolicy(entity)
        }
    }

    /**
     * Sends a single outbox row to Supabase. Returns a structured result so
     * callers (notably [com.tudominio.parentalcontrol.workers.OutboxDrainer])
     * can branch on retryable vs permanent failure.
     *
     * **Null [httpClient] contract**: if the client has not been initialized
     * (e.g., the app is starting up and `setHttpClient` has not been called
     * yet), this method returns [OutboxSendResult.RetryableFailure] wrapping
     * an [IllegalStateException] so the worker can map it to
     * `Result.retry()`. It does NOT silently no-op (the previous
     * implementation returned `false`, which looked like a real failure but
     * was indistinguishable from a network drop).
     */
    suspend fun sendOutboxItem(item: OutboxEntity, accessToken: String? = null): OutboxSendResult {
        val client = httpClient
        if (client == null) {
            return OutboxSendResult.RetryableFailure(
                IllegalStateException("httpClient not initialized")
            )
        }

        val endpoint = when (item.tipo) {
            "USAGE_LOG" -> "/rest/v1/usage_logs"
            "DEVICE_ALERT" -> "/rest/v1/device_alerts"
            "BEHAVIORAL_EVENT" -> "/rest/v1/behavioral_events"
            "TIME_REQUEST" -> "/rest/v1/time_requests"
            "HEARTBEAT" -> "/functions/v1/heartbeat"
            else -> return OutboxSendResult.PermanentFailure(statusCode = 0)
        }

        val token = accessToken ?: authManager.getAccessToken()
            ?: return OutboxSendResult.RetryableFailure(
                IllegalStateException("no access token")
            )

        return try {
            val isFunction = endpoint.startsWith("/functions")

            val response = if (isFunction) {
                client.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $token")
                    header("Content-Type", "application/json")
                    setBody(item.payload_json)
                }
            } else {
                client.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $token")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=minimal")
                    setBody(item.payload_json)
                }
            }

            classifyResponse(response.status)
        } catch (e: java.io.IOException) {
            OutboxSendResult.RetryableFailure(e)
        } catch (e: Exception) {
            OutboxSendResult.RetryableFailure(e)
        }
    }

    private fun classifyResponse(status: HttpStatusCode): OutboxSendResult = when {
        status.isSuccess() || status == HttpStatusCode.Conflict -> OutboxSendResult.Success
        status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests ->
            OutboxSendResult.RetryableFailure(
                IllegalStateException("HTTP $status")
            )
        status.value in 400..499 ->
            OutboxSendResult.PermanentFailure(statusCode = status.value)
        else ->
            // 5xx and anything else not in the 2xx/4xx buckets above.
            OutboxSendResult.RetryableFailure(
                IllegalStateException("HTTP $status")
            )
    }

    private suspend fun refreshFcmTokenIfNeeded() {
    }

    private suspend fun updatePendingCount() {
        _pendingCount.value = database.outboxDao().getPendingCountFlow().first()
    }
}

/**
 * Hilt [EntryPoint] that exposes [SyncManager] from the
 * `SingletonComponent` to non-Hilt call sites.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncManagerEntryPoint {
    fun syncManager(): SyncManager
}
