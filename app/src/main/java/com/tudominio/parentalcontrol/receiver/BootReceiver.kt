package com.tudominio.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tudominio.parentalcontrol.service.MonitorForegroundService
import com.tudominio.parentalcontrol.workers.WorkScheduler
import com.tudominio.parentalcontrol.workers.WorkerInitializer

/**
 * Receiver para re-armar los servicios tras boot o actualización.
 *
 * Responsabilidades:
 * 1. Reiniciar el MonitorForegroundService (T06)
 * 2. Reconciliar uso con UsageStats (T07)
 * 3. Encolar sincronización inicial (T18/T20)
 * 4. PR 3: agendar el [com.tudominio.parentalcontrol.workers.OutboxDrainer]
 *    periódico para que el drain de la outbox sobreviva al reinicio.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Recibido broadcast: ${intent.action}")

        when (intent.action) {
            ACTION_BOOT_COMPLETED -> {
                onBootCompleted(context)
            }
            ACTION_MY_PACKAGE_REPLACED -> {
                onPackageReplaced(context)
            }
        }
    }

    private fun onBootCompleted(context: Context) {
        Log.d(TAG, "Boot completado, inicializando servicios")

        // 1. Iniciar MonitorForegroundService si no está corriendo
        if (!isUsageServiceRunning(context)) {
            startMonitorForegroundService(context)
        }

        // 2. PR 3: schedule the OutboxDrainer explicitly so the periodic drain
        //    survives the boot. KEEP + a unique work name ensures we do not
        //    replace an already-scheduled instance.
        WorkScheduler.scheduleOutboxDrainer(context)

        // 3. Inicializar todos los workers restantes (T20)
        WorkerInitializer.initialize(context, isAfterBoot = true)

        Log.d(TAG, "Inicialización post-boot completada")
    }

    private fun onPackageReplaced(context: Context) {
        Log.d(TAG, "Package reemplazado, reinicializando")

        // Similar a boot pero también fuerza sync
        onBootCompleted(context)
    }

    private fun isUsageServiceRunning(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager

            activityManager.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == MonitorForegroundService::class.java.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error verificando servicio: ${e.message}")
            false
        }
    }

    private fun startMonitorForegroundService(context: Context) {
        Log.d(TAG, "Iniciando MonitorForegroundService")

        val serviceIntent = Intent(context, MonitorForegroundService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando servicio: ${e.message}")
        }
    }
}
