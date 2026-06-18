package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.sync.OutboxSendResult
import com.tudominio.parentalcontrol.sync.SyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Periodic WorkManager worker that drains the local `outbox` table to
 * Supabase. Replaces the previous [com.tudominio.parentalcontrol.workers.OutboxUploadWorker]
 * (deleted in PR 3) and aligns the local schema with the spec: rows are now
 * marked `processed = 1` instead of being deleted.
 *
 * Per the `outbox-drain` spec:
 *  - Success → mark processed, stamp `processed_at`
 *  - RetryableFailure → increment `retries`, return `Result.retry()` so
 *    WorkManager applies its exponential backoff
 *  - PermanentFailure (4xx other than 408/429) → mark processed and log
 *    a warning so the row does not loop forever
 *
 * If [SyncManager.httpClient] is null (the app is still starting up), the
 * worker short-circuits with `Result.retry()` rather than silently
 * succeeding — see the `null_http_client` test in [OutboxDrainerTest].
 */
@HiltWorker
class OutboxDrainer @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val outboxManager: OutboxManager,
    private val syncManager: SyncManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "OutboxDrainer"
        const val WORK_NAME = "outbox_drain_periodic"
        const val WORK_TAG = "outbox_drain_periodic"
        private const val MAX_RETRY_ATTEMPTS = 10
        private const val PENDING_BATCH_SIZE = 50
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Iniciando drain de outbox")

        // Gate 1: the HTTP client must be initialized. Without it, every send
        // would return RetryableFailure(httpClient not initialized) and the
        // worker would burn through the retry budget on a row that never had
        // a chance. Short-circuit with a retry so WorkManager backs off.
        if (syncManager.httpClient == null) {
            Log.w(TAG, "httpClient no inicializado, retry")
            return@withContext Result.retry()
        }

        val pending = outboxManager.getPendingItems(
            maxAttempts = MAX_RETRY_ATTEMPTS,
            limit = PENDING_BATCH_SIZE
        )
        if (pending.isEmpty()) {
            Log.d(TAG, "Sin items pendientes, fast success")
            return@withContext Result.success()
        }

        var sawRetryable = false
        for (item in pending) {
            val result = syncManager.sendOutboxItem(item)
            val now = Instant.now().toString()
            when (result) {
                is OutboxSendResult.Success -> {
                    outboxManager.markProcessed(item.id, now)
                }
                is OutboxSendResult.RetryableFailure -> {
                    outboxManager.incrementRetries(item.id)
                    sawRetryable = true
                    Log.w(
                        TAG,
                        "Retryable failure para outbox ${item.id}: ${result.cause.message}"
                    )
                }
                is OutboxSendResult.PermanentFailure -> {
                    // Spec: mark processed to avoid an infinite retry loop on
                    // poison rows. The `failed` flag in payload is recorded
                    // by the edge function (out of scope for PR 3).
                    outboxManager.markProcessed(item.id, now)
                    Log.w(
                        TAG,
                        "Permanent failure para outbox ${item.id}: HTTP ${result.statusCode}"
                    )
                }
            }
        }

        return@withContext if (sawRetryable) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}
