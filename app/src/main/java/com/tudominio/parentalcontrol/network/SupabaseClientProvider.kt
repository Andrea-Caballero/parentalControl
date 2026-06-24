package com.tudominio.parentalcontrol.network

import android.content.Context
import android.util.Log
import com.tudominio.parentalcontrol.BuildConfig
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.security.network.NetworkSecurityConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Proveedor de cliente Supabase.
 * Provee métodos para hacer peticiones autenticadas al backend.
 *
 * T5 of `hotfix-parent-auth-session` adds a secondary constructor that
 * takes an externally-built `HttpClient` (the `@SupabaseClient` Hilt
 * binding from `NetworkModule`). The Hilt-managed instance uses the
 * injected client — which is a Ktor `MockEngine` when
 * `BuildConfig.USE_MOCK_SUPABASE=true` and the real OkHttp engine
 * otherwise. Callers that still use `getInstance(context)` (e.g.,
 * `PairingManager`, `RealtimeManager`, `PlayIntegrityManager`,
 * `SyncManager`, `FcmPushService`) get the legacy real-engine path.
 */
class SupabaseClientProvider internal constructor(
    private val context: Context,
    private val injectedClient: HttpClient? = null
) {
    companion object {
        const val SUPABASE_URL = "https://your-project.supabase.co"
        const val SUPABASE_ANON_KEY = "your-anon-key"

        @Volatile
        private var instance: SupabaseClientProvider? = null

        fun getInstance(context: Context): SupabaseClientProvider {
            return instance ?: synchronized(this) {
                instance ?: SupabaseClientProvider(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val authManager = DeviceAuthManager.getInstance(context)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // HTTP Client con TLS 1.3 y Certificate Pinning
    val httpClient: HttpClient by lazy {
        injectedClient ?: run {
            // Legacy path — kept for callers that still use getInstance(context).
            // The Hilt-managed instance (RepositoryModule) passes the injected
            // @SupabaseClient client, so this branch is only hit from the
            // non-Hilt callers (PairingManager, RealtimeManager, etc.).
            //
            // Per `fix-supabase-client-provider-legacy-mock-gate`: the legacy
            // path must honor `BuildConfig.USE_MOCK_SUPABASE` (the same flag the
            // Hilt `@SupabaseClient` binding already honors in `NetworkModule`),
            // so debug builds wired to the mock engine don't surface as
            // `NETWORK_ERROR` against the placeholder Supabase URL.
            if (BuildConfig.USE_MOCK_SUPABASE) {
                MockSupabaseEngine(context).httpClient
            } else {
                val okHttpClient = NetworkSecurityConfig.createSecureOkHttpClient(context)
                HttpClient(OkHttp) {
                    engine {
                        preconfigured = okHttpClient
                    }
                    install(ContentNegotiation) {
                        json(this@SupabaseClientProvider.json)
                    }
                    install(HttpTimeout) {
                        requestTimeoutMillis = 30000
                        connectTimeoutMillis = 15000
                    }
                }
            }
        }
    }

    private val _isInitialized = MutableStateFlow(true)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Inicializa el cliente.
     */
    fun initialize() {
        // No-op
    }

    /**
     * Inicializa y autentica el dispositivo.
     */
    suspend fun initializeAndAuthenticate(): Boolean {
        _connectionState.value = ConnectionState.CONNECTING

        val result = authManager.authenticateOrCreate()

        _connectionState.value = when (result) {
            is com.tudominio.parentalcontrol.auth.AuthResult.Success -> ConnectionState.CONNECTED
            is com.tudominio.parentalcontrol.auth.AuthResult.NeedsPairing -> ConnectionState.NEEDS_PAIRING
            is com.tudominio.parentalcontrol.auth.AuthResult.Error -> ConnectionState.ERROR
        }

        return result is com.tudominio.parentalcontrol.auth.AuthResult.Success
    }

    /**
     * Refresca la sesión si es necesario.
     */
    suspend fun refreshIfNeeded(): Boolean {
        val result = authManager.refreshSession()

        _connectionState.value = when (result) {
            is com.tudominio.parentalcontrol.auth.AuthResult.Success -> ConnectionState.CONNECTED
            is com.tudominio.parentalcontrol.auth.AuthResult.NeedsPairing -> ConnectionState.NEEDS_PAIRING
            is com.tudominio.parentalcontrol.auth.AuthResult.Error -> ConnectionState.ERROR
        }

        return result is com.tudominio.parentalcontrol.auth.AuthResult.Success
    }

    /**
     * Completa el emparejamiento.
     */
    suspend fun completePairing(code: String): Boolean {
        _connectionState.value = ConnectionState.CONNECTING

        val result = authManager.completePairing(code)

        _connectionState.value = when (result) {
            is com.tudominio.parentalcontrol.auth.AuthResult.Success -> ConnectionState.CONNECTED
            is com.tudominio.parentalcontrol.auth.AuthResult.NeedsPairing -> ConnectionState.NEEDS_PAIRING
            is com.tudominio.parentalcontrol.auth.AuthResult.Error -> ConnectionState.ERROR
        }

        return result is com.tudominio.parentalcontrol.auth.AuthResult.Success
    }

    /**
     * Maneja falla de integridad (T23).
     */
    suspend fun handleIntegrityFailure(): Boolean {
        val result = authManager.handleIntegrityFailure()

        _connectionState.value = ConnectionState.INTEGRITY_ERROR

        return result is com.tudominio.parentalcontrol.auth.AuthResult.NeedsPairing
    }

    /**
     * Obtiene el token de acceso actual.
     */
    fun getAccessToken(): String? = authManager.getAccessToken()

    /**
     * Verifica si está emparejado.
     */
    fun isPaired(): Boolean = authManager.isPaired()

    /**
     * Obtiene el device ID.
     */
    fun getDeviceId(): String? = authManager.deviceId.value

    /**
     * Cierra la sesión.
     */
    fun logout() {
        authManager.clearSession()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ============ Métodos de API ============

    /**
     * Obtiene la política del dispositivo.
     */
    suspend fun getDevicePolicy(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No access token"))

            val response = httpClient.get("${SUPABASE_URL}/functions/v1/get-policy") {
                header("Authorization", "Bearer $token")
                header("apikey", SUPABASE_ANON_KEY)
            }

            Result.success(response.bodyAsText())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Registra un token FCM.
     */
    suspend fun registerPushToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No access token"))

            val body = "{\"token\":\"$token\"}"
            httpClient.post("${SUPABASE_URL}/functions/v1/register-token") {
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(body)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Envía latido del dispositivo.
     */
    suspend fun sendHeartbeat(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No access token"))

            httpClient.post("${SUPABASE_URL}/functions/v1/heartbeat") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SUPABASE_ANON_KEY)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Solicita tiempo extra.
     */
    suspend fun requestTime(
        minutes: Int,
        packageName: String? = null,
        reason: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val accessToken = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No access token"))

            val body = buildString {
                append("{\"minutes\":$minutes")
                packageName?.let { append(",\"package_name\":\"$it\"") }
                reason?.let { append(",\"reason\":\"$it\"") }
                append("}")
            }

            val response = httpClient.post("${SUPABASE_URL}/rest/v1/time_requests") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SUPABASE_ANON_KEY)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(body)
            }

            Result.success(response.bodyAsText())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Registra uso de tiempo.
     */
    suspend fun recordUsage(
        packageName: String,
        minutes: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val accessToken = authManager.getAccessToken()
                ?: return@withContext Result.failure(IllegalStateException("No access token"))

            val deviceId = authManager.deviceId.value ?: ""
            val body = "{\"package_name\":\"$packageName\",\"minutes_used\":$minutes,\"device_id\":\"$deviceId\"}"

            httpClient.post("${SUPABASE_URL}/rest/v1/usage_logs") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", SUPABASE_ANON_KEY)
                header("Content-Type", "application/json")
                setBody(body)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    NEEDS_PAIRING,
    INTEGRITY_ERROR,
    ERROR
}
