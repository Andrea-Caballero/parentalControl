package com.example.parentalcontrol.data.repository

import com.example.parentalcontrol.domain.model.*
import com.example.parentalcontrol.viewmodel.PairingCodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repositorio para operaciones del padre.
 * Conecta con las Edge Functions de Supabase (T15).
 * 
 * TODO: Integrar con Supabase client real cuando esté configurado.
 */
class ParentRepository {

    /**
     * Obtiene los dispositivos hijos del padre.
     */
    suspend fun getDevices(): List<ChildDevice> = withContext(Dispatchers.IO) {
        // Primero intentar leer dispositivos emparejados localmente (mock)
        val localDevices = getLocalPairedDevices()
        if (localDevices.isNotEmpty()) {
            return@withContext localDevices
        }
        
        // Si no hay locales, usar datos mock para demo
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
    
    private fun getLocalPairedDevices(): List<ChildDevice> {
        return try {
            val prefs = android.content.Context::class.java.getMethod(
                "getSharedPreferences", String::class.java, Int::class.java
            ).invoke(null, "parent_paired_devices", android.content.Context.MODE_PRIVATE) as android.content.SharedPreferences
            
            val devicesJson = prefs.getString("devices", "[]") ?: "[]"
            val devices = org.json.JSONArray(devicesJson)
            val result = mutableListOf<ChildDevice>()
            
            for (i in 0 until devices.length()) {
                val obj = devices.getJSONObject(i)
                result.add(
                    ChildDevice(
                        id = obj.getString("id"),
                        name = obj.optString("name", "Dispositivo sin nombre"),
                        model = obj.optString("model", ""),
                        appVersion = obj.optString("appVersion", "1.0.0"),
                        policyVersion = obj.optLong("policyVersion", 1).toInt(),
                        state = try { DeviceState.valueOf(obj.optString("state", "ACTIVE")) } catch (e: Exception) { DeviceState.ACTIVE },
                        lastSeenAt = obj.optString("lastSeenAt", ""),
                        isOnline = obj.optBoolean("isOnline", false)
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
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
                status = RequestStatus.PENDING,
                createdAt = "2026-06-04T11:45:00Z"
            ),
            TimeRequest(
                id = "req-2",
                deviceId = "device-2",
                deviceName = "Pixel 7 de María",
                minutesRequested = 15,
                reason = "Terminé la tarea",
                status = RequestStatus.PENDING,
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
     */
    suspend fun createPairingCode(
        deviceName: String,
        ageBand: String,
        ttlMinutes: Int
    ): PairingCodeResult = withContext(Dispatchers.IO) {
        val code = generateCode()
        
        // Guardar dispositivo pendiente para que aparezca en la lista del padre
        savePendingDevice(code, deviceName, ageBand)
        
        PairingCodeResult(
            code = code,
            expiresAt = "2026-06-04T12:10:00Z",
            deeplink = "parentalcontrol://pair?code=$code"
        )
    }
    
    private fun savePendingDevice(code: String, deviceName: String, ageBand: String) {
        val prefs = android.app.Application().getSharedPreferences("parent_paired_devices", android.content.Context.MODE_PRIVATE)
        val devicesJson = prefs.getString("devices", "[]") ?: "[]"
        val devices = org.json.JSONArray(devicesJson)
        
        val newDevice = org.json.JSONObject().apply {
            put("id", "pending-$code")
            put("name", deviceName.ifBlank { "Dispositivo nuevo" })
            put("model", android.os.Build.MODEL)
            put("appVersion", "1.0.0")
            put("policyVersion", 1)
            put("state", "PENDING")
            put("lastSeenAt", java.time.Instant.now().toString())
            put("isOnline", false)
            put("pairingCode", code)
            put("ageBand", ageBand)
        }
        devices.put(newDevice)
        
        prefs.edit().putString("devices", devices.toString()).apply()
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
    suspend fun getUsageStats(deviceId: String, date: String): List<UsageStats> =
        withContext(Dispatchers.IO) {
            listOf(
                UsageStats(
                    deviceId = deviceId,
                    packageName = "com.instagram.android",
                    appName = "Instagram",
                    date = date,
                    minutesUsed = 45,
                    limitMinutes = 60,
                    remainingMinutes = 15
                ),
                UsageStats(
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

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }
}
