package com.example.parentalcontrol.pairing

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.auth.AuthResult
import com.example.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manager para el emparejamiento de dispositivos.
 * 
 * §0.5: Emparejamiento vía tabla pairing_codes
 * §0.9: La sesión se guarda tras emparejar exitosamente
 */
class PairingManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PairingManager"
        
        // Longitud del código de emparejamiento
        const val CODE_LENGTH = 8
        
        // Timeout del código en minutos
        const val CODE_TTL_MINUTES = 10

        @Volatile
        private var instance: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return instance ?: synchronized(this) {
                instance ?: PairingManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val authManager = DeviceAuthManager.getInstance(context)
    private val clientProvider = SupabaseClientProvider.getInstance(context)

    /**
     * Empareja el dispositivo con el código proporcionado.
     * 
     * @param code Código de emparejamiento (QR o manual)
     * @return Resultado del emparejamiento
     */
    suspend fun pairWithCode(code: String): PairingResult {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "Intentando emparejar con código: ${code.take(4)}...")
            
            // MODO MOCK PARA PRUEBAS LOCALES
            // En producción, esto llamaría al backend de Supabase
            try {
                // Generar IDs simulados para el emparejamiento local
                val mockDeviceId = "child-device-${System.currentTimeMillis()}"
                val mockParentId = "parent-${code.uppercase()}"
                
                // Guardar sesión de emparejamiento
                authManager.savePairedSession(mockDeviceId, mockParentId)
                
                // Guardar dispositivo emparejado en almacenamiento del padre (mock)
                // En producción, el backend guardaría esto
                savePairedDeviceToParentStorage(code, mockDeviceId, mockParentId)
                
                Log.d(TAG, "Emparejamiento local exitoso con código: $code")
                PairingResult.Success(mockDeviceId, mockParentId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en emparejamiento local: ${e.message}")
                PairingResult.Error(
                    PairingErrorType.NETWORK_ERROR,
                    "Error de conexión: ${e.message}"
                )
            }
        }
    }
    
    private fun savePairedDeviceToParentStorage(code: String, deviceId: String, parentId: String) {
        // Este es un mock: guarda el dispositivo emparejado en SharedPreferences
        // que el padre puede leer. En producción, el backend manejaría esto.
        val prefs = context.getSharedPreferences("parent_paired_devices", Context.MODE_PRIVATE)
        val devicesJson = prefs.getString("devices", "[]")
        
        // Agregar nuevo dispositivo a la lista
        val devices = org.json.JSONArray(devicesJson)
        val newDevice = org.json.JSONObject().apply {
            put("id", deviceId)
            put("name", "Dispositivo emparejado")
            put("model", android.os.Build.MODEL)
            put("appVersion", "1.0.0")
            put("policyVersion", 1)
            put("state", "ACTIVE")
            put("lastSeenAt", java.time.Instant.now().toString())
            put("isOnline", true)
            put("pairingCode", code)
        }
        devices.put(newDevice)
        
        prefs.edit().putString("devices", devices.toString()).apply()
        Log.d(TAG, "Dispositivo guardado para el padre: $deviceId")
    }

    /**
     * Empareja el dispositivo usando el contenido de un QR.
     * Extrae el código del payload del QR.
     * 
     * @param qrContent Contenido del código QR
     * @return Resultado del emparejamiento
     */
    suspend fun pairWithQr(qrContent: String): PairingResult {
        Log.d(TAG, "Procesando QR content")
        
        // El QR puede contener:
        // 1. Solo el código (ej: "PC-ABCD1234")
        // 2. URL con código (ej: "https://app.com/pair/ABCD1234")
        // 3. JSON con código
        
        val code = extractCodeFromQr(qrContent)
        
        if (code == null) {
            return PairingResult.Error(
                PairingErrorType.INVALID_QR,
                "Código QR no reconocido"
            )
        }
        
        return pairWithCode(code)
    }

    /**
     * Extrae el código del contenido del QR.
     */
    private fun extractCodeFromQr(content: String): String? {
        return when {
            // URL: extraer de path
            content.startsWith("http://") || content.startsWith("https://") -> {
                val path = content.substringAfter("/pair/").substringBefore("?")
                if (path.length >= CODE_LENGTH) path else null
            }
            
            // Código directo (puede tener prefijo como "PC-")
            content.contains("-") -> {
                content.substringAfterLast("-")
            }
            
            // Código directo de longitud correcta
            content.length >= CODE_LENGTH -> {
                content.takeLast(CODE_LENGTH)
            }
            
            else -> null
        }
    }

    /**
     * Obtiene información del dispositivo para el emparejamiento.
     */
    private fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            deviceModel = Build.MODEL,
            osVersion = Build.VERSION.SDK_INT.toString(),
            appVersion = getAppVersion(),
            ageBand = null // El padre selecciona la banda de edad desde el panel
        )
    }

    /**
     * Obtiene la versión de la app.
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Construye el body del request de emparejamiento.
     */
    private fun buildPairingRequest(code: String, deviceInfo: DeviceInfo): String {
        return buildString {
            append("{")
            append("\"code\":\"$code\",")
            append("\"device_name\":\"${deviceInfo.deviceName}\",")
            append("\"device_model\":\"${deviceInfo.deviceModel}\",")
            append("\"os_version\":\"${deviceInfo.osVersion}\",")
            append("\"app_version\":\"${deviceInfo.appVersion}\"")
            deviceInfo.ageBand?.let {
                append(",\"age_band\":\"$it\"")
            }
            append("}")
        }
    }

    /**
     * Parsea la respuesta del servidor de emparejamiento.
     */
    private suspend fun parsePairingResponse(statusCode: Int, responseBody: String): PairingResult {
        return when (statusCode) {
            200, 201 -> {
                // Éxito
                val deviceId = extractDeviceId(responseBody)
                val parentId = extractParentId(responseBody)
                
                if (deviceId != null) {
                    // Guardar la sesión emparejada
                    authManager.savePairedSession(deviceId, parentId)
                    
                    Log.d(TAG, "Emparejamiento exitoso: deviceId=$deviceId")
                    PairingResult.Success(deviceId, parentId)
                } else {
                    PairingResult.Error(
                        PairingErrorType.INVALID_RESPONSE,
                        "Respuesta del servidor inválida"
                    )
                }
            }
            
            404 -> {
                Log.w(TAG, "Código inválido o no encontrado")
                PairingResult.Error(
                    PairingErrorType.INVALID_CODE,
                    "El código no es válido"
                )
            }
            
            410 -> {
                Log.w(TAG, "Código expirado")
                PairingResult.Error(
                    PairingErrorType.EXPIRED_CODE,
                    "El código ha expirado. Solicita uno nuevo desde el panel parental."
                )
            }
            
            409 -> {
                Log.w(TAG, "Código ya usado")
                PairingResult.Error(
                    PairingErrorType.ALREADY_USED,
                    "Este código ya fue utilizado"
                )
            }
            
            else -> {
                val errorMessage = extractErrorMessage(responseBody)
                Log.e(TAG, "Error de emparejamiento: $statusCode - $errorMessage")
                PairingResult.Error(
                    PairingErrorType.SERVER_ERROR,
                    errorMessage ?: "Error del servidor"
                )
            }
        }
    }

    /**
     * Extrae el device_id de la respuesta JSON.
     */
    private fun extractDeviceId(json: String): String? {
        return try {
            // Simple extraction
            val pattern = "\"device_id\":\"([^\"]+)\"".toRegex()
            pattern.find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrae el parent_id de la respuesta JSON.
     */
    private fun extractParentId(json: String): String? {
        return try {
            val pattern = "\"parent_id\":\"([^\"]+)\"".toRegex()
            pattern.find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extrae el mensaje de error de la respuesta JSON.
     */
    private fun extractErrorMessage(json: String): String? {
        return try {
            val pattern = "\"error\":\"([^\"]+)\"".toRegex()
            pattern.find(json)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Información del dispositivo para emparejamiento.
 */
data class DeviceInfo(
    val deviceName: String,
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String,
    val ageBand: String?
)

/**
 * Resultado del emparejamiento.
 */
sealed class PairingResult {
    data class Success(
        val deviceId: String,
        val parentId: String?
    ) : PairingResult()
    
    data class Error(
        val type: PairingErrorType,
        val message: String
    ) : PairingResult()
}

/**
 * Tipos de error de emparejamiento.
 */
enum class PairingErrorType {
    /** El código no es válido */
    INVALID_CODE,
    
    /** El código ha expirado */
    EXPIRED_CODE,
    
    /** El código ya fue usado */
    ALREADY_USED,
    
    /** El QR no contiene un código reconocido */
    INVALID_QR,
    
    /** Error de sesión */
    SESSION_ERROR,
    
    /** Error de red */
    NETWORK_ERROR,
    
    /** Respuesta del servidor inválida */
    INVALID_RESPONSE,
    
    /** Error genérico del servidor */
    SERVER_ERROR
}
