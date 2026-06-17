package com.example.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val TAG = "WorkScheduler"

    fun scheduleAllPeriodicWork(context: Context) {
        Log.d(TAG, "Programando todos los workers periódicos")

        scheduleHeartbeat(context)
        scheduleOutboxDrainer(context)
        scheduleReconciliation(context)

        Log.d(TAG, "Todos los workers periódicos programados")
    }

    fun scheduleHeartbeat(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(HeartbeatWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                HeartbeatWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

        Log.d(TAG, "Heartbeat programado")
    }

    fun scheduleOutboxDrainer(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<OutboxDrainer>(
            15, TimeUnit.MINUTES,
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(OutboxDrainer.WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                OutboxDrainer.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Outbox drainer programado")
    }

    fun scheduleReconciliation(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<ReconciliationWorker>(
            1, TimeUnit.HOURS,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(ReconciliationWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                ReconciliationWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Reconciliación programada")
    }

    fun scheduleSyncAfterBoot(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(SyncWorker.TAG_AFTER_BOOT)
            .build()

        val heartbeatRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .build()

        val outboxRequest = OneTimeWorkRequestBuilder<OutboxDrainer>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "${SyncWorker.WORK_NAME}_after_boot",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
            .then(heartbeatRequest)
            .then(outboxRequest)
            .enqueue()

        Log.d(TAG, "Secuencia post-boot programada")
    }

    fun scheduleSyncOnce(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.WORK_NAME)
            .addTag(SyncWorker.TAG_AFTER_FCM)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${SyncWorker.WORK_NAME}_once",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Sync único programado")
    }

    fun scheduleSyncAfterPairing(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.WORK_NAME)
            .addTag(SyncWorker.TAG_AFTER_PAIRING)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${SyncWorker.WORK_NAME}_after_pairing",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )

        Log.d(TAG, "Sync post-emparejamiento programado")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        Log.d(TAG, "Todos los workers cancelados")
    }

    fun cancelWork(context: Context, workName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Log.d(TAG, "Worker cancelado: $workName")
    }

    fun getWorkState(context: Context, workName: String) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(workName)

    fun triggerSyncNow(context: Context) {
        Log.d(TAG, "Forzando sync inmediato")

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("manual_sync")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "manual_sync",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
    }
}

object WorkerInitializer {

    private const val TAG = "WorkerInitializer"

    fun initialize(context: Context, isAfterBoot: Boolean = false) {
        Log.d(TAG, "Inicializando workers (afterBoot=$isAfterBoot)")

        if (isAfterBoot) {
            WorkScheduler.scheduleSyncAfterBoot(context)
        }

        WorkScheduler.scheduleAllPeriodicWork(context)

        Log.d(TAG, "Workers inicializados")
    }

    fun reinitializeAfterPairing(context: Context) {
        Log.d(TAG, "Reinicializando tras emparejamiento")
        WorkScheduler.scheduleSyncAfterPairing(context)
    }
}
