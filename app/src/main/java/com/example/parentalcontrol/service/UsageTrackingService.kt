package com.example.parentalcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.parentalcontrol.MainActivity
import com.example.parentalcontrol.R
import com.example.parentalcontrol.accessibility.ForegroundAppService
import com.example.parentalcontrol.data.local.AppDatabase
import com.example.parentalcontrol.reconciliation.UsageStatsReconciler
import com.example.parentalcontrol.time.DefaultTimeProvider
import com.example.parentalcontrol.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UsageTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "usage_tracking_channel"
        const val NOTIFICATION_ID = 1
        const val TICK_INTERVAL_MS = 5_000L
        const val WARNING_THRESHOLD_10 = 10
        const val WARNING_THRESHOLD_5 = 5
        const val DEFAULT_DAILY_LIMIT_MINUTES = 60
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var tickJob: Job
    private var currentPackage: String? = null
    private var sessionStartTime: Long = 0L
    private var lastTickTime: Long = 0L
    private var warned10Minutes = false
    private var warned5Minutes = false

    private lateinit var database: AppDatabase
    private lateinit var timeProvider: TimeProvider
    private lateinit var reconciler: UsageStatsReconciler
    private val dailyLimitMinutes = MutableStateFlow(DEFAULT_DAILY_LIMIT_MINUTES)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        timeProvider = DefaultTimeProvider(this)
        reconciler = UsageStatsReconciler(this, database)
        createNotificationChannel()
        observeForegroundChanges()
        requestBackfill()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        startTicking()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForeground() {
        val notification = buildPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Control de uso",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificaciones de seguimiento de uso de aplicaciones"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildPersistentNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Control parental activo")
            .setContentText("Monitoreando uso de aplicaciones")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun observeForegroundChanges() {
        serviceScope.launch {
            ForegroundAppService.appInForeground.collectLatest { packageName ->
                if (packageName != null && packageName != currentPackage) {
                    currentPackage = packageName
                    sessionStartTime = System.currentTimeMillis()
                    lastTickTime = sessionStartTime
                    warned10Minutes = false
                    warned5Minutes = false
                }
            }
        }
    }

    private fun startTicking() {
        tickJob = serviceScope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                if (currentPackage != null) {
                    updateUsage()
                }
            }
        }
    }

    private fun requestBackfill() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                reconciler.backfillFromUsageStats()
            } catch (e: Exception) {
                // Backfill is best-effort, service continues regardless
            }
        }
    }

    private suspend fun updateUsage() {
        val now = System.currentTimeMillis()
        val delta = now - lastTickTime
        lastTickTime = now

        currentPackage?.let { pkg ->
            database.usageDao().incrementUsage(
                packageName = pkg,
                serverDate = timeProvider.currentDate().toString(),
                deltaMinutes = (delta / 60_000).toInt()
            )
            checkWarnings()
        }
    }

    private suspend fun checkWarnings() {
        val packageName = currentPackage ?: return
        val serverDate = timeProvider.currentDate().toString()
        val limit = dailyLimitMinutes.value

        database.usageDao().getUsageForPackageFlow(packageName, serverDate).collectLatest { usageMinutes ->
            usageMinutes?.let { used ->
                val remainingMinutes = (limit - used).coerceAtLeast(0)

                if (remainingMinutes <= WARNING_THRESHOLD_10 && !warned10Minutes) {
                    sendWarningNotification(10)
                    warned10Minutes = true
                }

                if (remainingMinutes <= WARNING_THRESHOLD_5 && !warned5Minutes) {
                    sendWarningNotification(5)
                    warned5Minutes = true
                }
            }
        }
    }

    private fun sendWarningNotification(minutesRemaining: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tiempo limitado")
            .setContentText("Quedan $minutesRemaining minutos para $currentPackage")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(minutesRemaining, notification)
    }

    fun updateDailyLimit(minutes: Int) {
        dailyLimitMinutes.value = minutes
    }
}
