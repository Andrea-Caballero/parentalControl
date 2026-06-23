package com.tudominio.parentalcontrol.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.tudominio.parentalcontrol.keystore.SecureStorage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class AuthResult {
    data class Success(
        val deviceId: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long
    ) : AuthResult()

    data class NeedsPairing(
        val message: String
    ) : AuthResult()

    data class Error(
        val message: String
    ) : AuthResult()
}

enum class SessionState {
    NONE,
    ANONYMOUS,
    PAIRED,
    EXPIRED,
    INVALID
}

@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val deviceId: String?,
    val userId: String
)

@Serializable
data class SupabaseAuthResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val expires_at: Long? = null,
    val user: SupabaseUser? = null
)

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    val app_metadata: Map<String, String>? = null,
    val user_metadata: Map<String, String>? = null
)

class DeviceAuthManager private constructor(
    private val context: Context
) {
    companion object {
        const val SUPABASE_URL = "https://your-project.supabase.co"
        const val SUPABASE_ANON_KEY = "your-anon-key"

        @Volatile
        private var instance: DeviceAuthManager? = null

        fun getInstance(context: Context): DeviceAuthManager {
            return instance ?: synchronized(this) {
                instance ?: DeviceAuthManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val secureStorage = SecureStorage.getInstance(context)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 15000
        }
    }

    private val _sessionState = MutableStateFlow(SessionState.NONE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private var currentAccessToken: String? = null
    private var currentRefreshToken: String? = null
    private var sessionExpiresAt: Long = 0

    private val sessionMutex = Mutex()

    init {
        loadPersistedState()
    }

    suspend fun authenticateOrCreate(): AuthResult = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            val restoredSession = restoreSession()
            if (restoredSession != null) {
                currentAccessToken = restoredSession.accessToken
                currentRefreshToken = restoredSession.refreshToken
                sessionExpiresAt = restoredSession.expiresAt
                _deviceId.value = restoredSession.deviceId
                _sessionState.value = if (restoredSession.deviceId != null) SessionState.PAIRED else SessionState.ANONYMOUS

                return@withContext AuthResult.Success(
                    deviceId = restoredSession.deviceId ?: "anonymous",
                    accessToken = restoredSession.accessToken,
                    refreshToken = restoredSession.refreshToken,
                    expiresAt = restoredSession.expiresAt
                )
            }

            return@withContext createAnonymousSession()
        }
    }

    /**
     * Role-aware synthetic anonymous auth.
     *
     * Issues a local JWT-shaped token of the form `anon-${role}-${uuid}` and
     * persists the [role] in `device_auth_prefs` so [getRole] can return it
     * after a process restart. This is the hotfix path described in design
     * §D4 of `openspec/changes/hotfix-parent-auth-session/design.md`: it
     * does NOT call Supabase, so it works even when `SUPABASE_URL` is a
     * placeholder (the current `local.properties` state).
     *
     * The synthetic token is acknowledged throwaway; the eventual
     * `parent-auth-flow` change will replace this with real sign-up/sign-in.
     * The [Role] flag is the seam between the synthetic hotfix and the
     * formal flow.
     *
     * Kept as an overload (alongside the no-arg `authenticateOrCreate()`
     * above) because [com.tudominio.parentalcontrol.network.SupabaseClientProvider]
     * and [DeviceAuthService] still call the no-arg shape.
     */
    suspend fun authenticateOrCreate(role: Role): Result<Unit> = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val token = "anon-${role.name}-${java.util.UUID.randomUUID()}"
                currentAccessToken = token
                currentRefreshToken = ""
                sessionExpiresAt = 0
                _deviceId.value = null
                _sessionState.value = SessionState.ANONYMOUS

                context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("role", role.name)
                    .apply()

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Returns the role persisted by [authenticateOrCreate] (PARENT or CHILD),
     * or `null` if no role has been persisted yet (fresh install, or only
     * the no-arg `authenticateOrCreate()` was used and it has not stored
     * a `role` key). The role is the only key written by the synthetic
     * hotfix path; it survives a process restart via SharedPreferences.
     */
    fun getRole(): Role? {
        val name = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("role", null) ?: return null
        return runCatching { Role.valueOf(name) }.getOrNull()
    }

    suspend fun createAnonymousSession(): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post("${SUPABASE_URL}/auth/v1/token?grant_type=password") {
                header("apikey", SUPABASE_ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        mapOf(
                            "email" to "anonymous_${System.currentTimeMillis()}@placeholder.local",
                            "password" to java.util.UUID.randomUUID().toString()
                        )
                    )
                )
            }

            if (response.status.isSuccess()) {
                val authResponse: SupabaseAuthResponse = response.body()
                return@withContext handleAuthSuccess(authResponse)
            }

            AuthResult.Error("Error creando sesión: ${response.status}")
        } catch (e: Exception) {
            AuthResult.Error("Error creando sesión: ${e.message}")
        }
    }

    private suspend fun handleAuthSuccess(response: SupabaseAuthResponse): AuthResult {
        currentAccessToken = response.access_token
        currentRefreshToken = response.refresh_token
        sessionExpiresAt = response.expires_at ?: (System.currentTimeMillis() / 1000 + response.expires_in)

        val deviceId = response.user?.app_metadata?.get("device_id")
        _deviceId.value = deviceId

        persistSession(
            StoredSession(
                accessToken = response.access_token,
                refreshToken = response.refresh_token,
                expiresAt = sessionExpiresAt,
                deviceId = deviceId,
                userId = response.user?.id ?: ""
            )
        )

        _sessionState.value = SessionState.ANONYMOUS

        return AuthResult.Success(
            deviceId = deviceId ?: "anonymous",
            accessToken = response.access_token,
            refreshToken = response.refresh_token,
            expiresAt = sessionExpiresAt
        )
    }

    suspend fun completePairing(pairingCode: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.post("${SUPABASE_URL}/functions/v1/pairing") {
                header("Authorization", "Bearer $currentAccessToken")
                header("Content-Type", "application/json")
                setBody(json.encodeToString(mapOf("code" to pairingCode)))
            }

            if (!response.status.isSuccess()) {
                return@withContext AuthResult.Error("Emparejamiento fallido: ${response.status}")
            }

            @Serializable
            data class PairingResponse(val device_id: String)

            val responseBody: PairingResponse = response.body()
            val newDeviceId = responseBody.device_id

            _deviceId.value = newDeviceId
            _sessionState.value = SessionState.PAIRED

            context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("is_paired", true)
                .putString("device_id", newDeviceId)
                .apply()

            currentAccessToken?.let { access ->
                currentRefreshToken?.let { refresh ->
                    persistSession(
                        StoredSession(
                            accessToken = access,
                            refreshToken = refresh,
                            expiresAt = sessionExpiresAt,
                            deviceId = newDeviceId,
                            userId = ""
                        )
                    )
                }
            }

            AuthResult.Success(
                deviceId = newDeviceId,
                accessToken = currentAccessToken!!,
                refreshToken = currentRefreshToken!!,
                expiresAt = sessionExpiresAt
            )
        } catch (e: Exception) {
            AuthResult.Error("Error en emparejamiento: ${e.message}")
        }
    }

    suspend fun refreshSession(): AuthResult = withContext(Dispatchers.IO) {
        val refreshToken = currentRefreshToken
            ?: return@withContext AuthResult.NeedsPairing("No hay sesión")

        return@withContext try {
            val response = httpClient.post("${SUPABASE_URL}/auth/v1/token?grant_type=refresh_token") {
                header("apikey", SUPABASE_ANON_KEY)
                header("Content-Type", "application/json")
                setBody(json.encodeToString(mapOf("refresh_token" to refreshToken)))
            }

            if (!response.status.isSuccess()) {
                _sessionState.value = SessionState.INVALID
                return@withContext AuthResult.NeedsPairing("Sesión inválida")
            }

            val authResponse: SupabaseAuthResponse = response.body()
            return@withContext handleAuthSuccess(authResponse)

        } catch (e: Exception) {
            _sessionState.value = SessionState.INVALID
            AuthResult.NeedsPairing("Error refreshing: ${e.message}")
        }
    }

    suspend fun forceReauth(): AuthResult = withContext(Dispatchers.IO) {
        clearSession()
        return@withContext createAnonymousSession()
    }

    suspend fun handleIntegrityFailure(): AuthResult = withContext(Dispatchers.IO) {
        clearSession()
        _sessionState.value = SessionState.EXPIRED
        return@withContext AuthResult.NeedsPairing("Integridad comprometida, re-emparejar requerido")
    }

    suspend fun savePairedSession(deviceId: String, parentId: String?) {
        _deviceId.value = deviceId
        _sessionState.value = SessionState.PAIRED

        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_paired", true)
            .putString("device_id", deviceId)
            .putString("parent_id", parentId)
            .apply()

        currentAccessToken?.let { access ->
            currentRefreshToken?.let { refresh ->
                persistSession(
                    StoredSession(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAt = sessionExpiresAt,
                        deviceId = deviceId,
                        userId = ""
                    )
                )
            }
        }
    }

    fun getAccessToken(): String? = currentAccessToken

    fun isPaired(): Boolean = _sessionState.value == SessionState.PAIRED

    fun isSessionExpiringSoon(): Boolean {
        val fiveMinutesFromNow = System.currentTimeMillis() / 1000 + 300
        return sessionExpiresAt > 0 && sessionExpiresAt < fiveMinutesFromNow
    }

    fun clearSession() {
        runBlocking {
            sessionMutex.withLock {
                currentAccessToken = null
                currentRefreshToken = null
                sessionExpiresAt = 0
                _sessionState.value = SessionState.NONE
                _deviceId.value = null
                context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            }
        }
    }

    suspend fun <T> authenticatedRequest(
        method: HttpMethod,
        path: String,
        body: String? = null
    ): Result<io.ktor.client.statement.HttpResponse> = withContext(Dispatchers.IO) {
        try {
            val token = currentAccessToken ?: return@withContext Result.failure(
                IllegalStateException("No access token")
            )

            val response = httpClient.request("${SUPABASE_URL}$path") {
                this.method = method
                header("Authorization", "Bearer $token")
                header("apikey", SUPABASE_ANON_KEY)
                header("Content-Type", "application/json")
                body?.let { setBody(it) }
            }

            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun persistSession(session: StoredSession) {
        val jsonString = json.encodeToString(session)
        val encrypted = encryptWithKeystore(jsonString)
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", encrypted)
            .apply()
    }

    /**
     * No network, no side effects; returns the stored session if any, null on missing/expired/decryption-failed.
     */
    internal fun restoreSession(): StoredSession? {
        val encrypted = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("encrypted_session", null) ?: return null

        return try {
            val jsonString = decryptWithKeystore(encrypted)
            val stored = json.decodeFromString<StoredSession>(jsonString)

            if (stored.expiresAt > 0 && stored.expiresAt < System.currentTimeMillis() / 1000) {
                return null
            }

            stored
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPersistedState() {
        val deviceId = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getString("device_id", null)
        val isPaired = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .getBoolean("is_paired", false)
        _deviceId.value = deviceId
        _sessionState.value = when {
            isPaired && deviceId != null -> SessionState.PAIRED
            deviceId != null -> SessionState.ANONYMOUS
            else -> SessionState.NONE
        }
    }

    private fun encryptWithKeystore(data: String): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val secretKey = keyStore.getKey(AUTH_KEY_ALIAS, null) as? SecretKey
            ?: createAuthKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptWithKeystore(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val secretKey = keyStore.getKey(AUTH_KEY_ALIAS, null) as? SecretKey
            ?: createAuthKey()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun createAuthKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val keySpec = KeyGenParameterSpec.Builder(
            AUTH_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }
}

private const val AUTH_KEY_ALIAS = "parental_control_auth_key"
