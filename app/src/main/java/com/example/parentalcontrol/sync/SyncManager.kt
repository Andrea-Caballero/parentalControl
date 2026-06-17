package com.example.parentalcontrol.sync

import android.content.Context
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.data.local.*
import com.example.parentalcontrol.network.ConnectionState
import com.example.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.ZoneOffset
import java.util.UUID

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

class SyncManager private constructor(
    private val context: Context,
    private var database: AppDatabase
) {
    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 5000L
        private const val SYNC_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext, AppDatabase.getInstance(context)).also {
                    instance = it
                }
            }
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

    private var httpClient: HttpClient? = null

    init {
        scope.launch { updatePendingCount() }
    }

    fun setDatabase(db: AppDatabase) {
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
                val result = sendOutboxItem(item, accessToken)
                if (result) {
                    database.outboxDao().deleteItem(item.id)
                } else {
                    database.outboxDao().incrementAttempts(item.id)
                    failedCount++
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

    fun setHttpClient(client: HttpClient) {
        httpClient = client
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

    private suspend fun sendOutboxItem(item: OutboxEntity, accessToken: String): Boolean {
        val endpoint = when (item.tipo) {
            "USAGE_LOG" -> "/rest/v1/usage_logs"
            "DEVICE_ALERT" -> "/rest/v1/device_alerts"
            "BEHAVIORAL_EVENT" -> "/rest/v1/behavioral_events"
            "TIME_REQUEST" -> "/rest/v1/time_requests"
            "HEARTBEAT" -> "/functions/v1/heartbeat"
            else -> return false
        }

        return try {
            val isFunction = endpoint.startsWith("/functions")

            val response = if (isFunction) {
                httpClient?.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $accessToken")
                    header("Content-Type", "application/json")
                    setBody(item.payload_json)
                }
            } else {
                httpClient?.post("${SupabaseClientProvider.SUPABASE_URL}$endpoint") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=minimal")
                    setBody(item.payload_json)
                }
            }

            response?.status?.isSuccess() == true ||
            response?.status == HttpStatusCode.Conflict
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun refreshFcmTokenIfNeeded() {
    }

    private suspend fun updatePendingCount() {
        _pendingCount.value = database.outboxDao().getPendingCountFlow().first()
    }
}
