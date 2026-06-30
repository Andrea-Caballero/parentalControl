package com.tudominio.parentalcontrol.workers

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
        // Spec scenario "scheduleAllPeriodicWork enqueues all four periodic
        // workers" — SolicitudesPollingWorker runs alongside the existing
        // three so the Solicitudes tab stays fresh even when the parent
        // never opens it. See `fix-parent-solicitudes-auto-poll`.
        scheduleSolicitudesPolling(context)

        Log.d(TAG, "Todos los workers periódicos programados (4)")
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

    /**
     * Fires an immediate one-shot drain of the outbox. Used after enqueuing a
     * row in places where we don't want to wait for the 15-minute periodic
     * tick — e.g., when the child taps "Enviar solicitud" on
     * `ExtraTimeScreen` and we want the parent to see the request within
     * seconds, not minutes.
     *
     * `ExistingWorkPolicy.REPLACE` collapses concurrent taps into a single
     * drain — the worker re-reads the outbox table on each run, so coalescing
     * is safe and saves WorkManager scheduling overhead.
     */
    fun scheduleOneTimeOutboxDrain(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<OutboxDrainer>()
            .setConstraints(constraints)
            .addTag(OutboxDrainer.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                OutboxDrainer.WORK_NAME_ONESHOT,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )

        Log.d(TAG, "One-time outbox drainer programado")
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

    /**
     * Schedules [SolicitudesPollingWorker] on a 5-minute cadence. Mirrors
     * [scheduleHeartbeat] but uses `ExistingPeriodicWorkPolicy.KEEP` per
     * design D3 (the worker is idempotent — replacing its schedule on every
     * `scheduleAllPeriodicWork` call would be wasteful).
     *
     * Spec scenarios covered:
     *  - "Worker is scheduled with a 5-minute repeat interval"
     *  - "Existing periodic jobs are not duplicated"
     */
    fun scheduleSolicitudesPolling(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<SolicitudesPollingWorker>(
            5, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SolicitudesPollingWorker.WORK_NAME)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                SolicitudesPollingWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )

        Log.d(TAG, "Solicitudes polling programado")
    }

    fun scheduleSyncAfterBoot(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag(SyncWorker.TAG_AFTER_BOOT)
            .build()

        // Each chain step carries its own WORK_NAME tag so observers can
        // query the WorkManager database by tag and identify which worker
        // produced each `WorkInfo` (the public `WorkInfo` API does not
        // expose the worker class). The tags are harmless in production
        // (they only add query affordances) and pin the chain shape in
        // BootReceiverTest — see SUGGESTION #3 of
        // `fix-supabase-client-provider-legacy-mock-gate/verify-report.md`
        // for the historical spec/code drift this guards against.

        val heartbeatRequest = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setConstraints(constraints)
            .addTag(HeartbeatWorker.WORK_NAME)
            .build()

        val outboxRequest = OneTimeWorkRequestBuilder<OutboxDrainer>()
            .setConstraints(constraints)
            .addTag(OutboxDrainer.WORK_NAME)
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
