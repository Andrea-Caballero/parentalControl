package com.tudominio.parentalcontrol.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.network.ConnectionState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic WorkManager worker that re-fetches pending time requests for the
 * currently signed-in parent and pushes the result through
 * `ParentRepository.pendingRequestsFlow` so the UI's Solicitudes tab is fresh
 * even when the parent never opens it.
 *
 * Mirrors `HeartbeatWorker` (`workers/Workers.kt:19-73`):
 *  - `@HiltWorker` for the same Hilt-aware factory wiring.
 *  - `Dispatchers.IO` for the body.
 *  - Exponential backoff at `WorkRequest.MIN_BACKOFF_MILLIS`.
 *  - 5-minute cadence (matches heartbeat).
 *
 * Design decisions implemented here:
 *  - **D2**: the worker calls `ParentRepository.publishPendingRequests(list)`
 *    on success; the `ParentViewModel` collector (added in `init`) mirrors the
 *    flow value into `_pendingRequests`. This keeps the worker's write path
 *    and the VM's read path talking through the same singleton object.
 *  - **D3**: `ExistingPeriodicWorkPolicy.KEEP` per the spec (`specs/time-request-approval/spec.md:67`).
 *    The worker is idempotent (read-only fetch), so replacing its schedule
 *    on every `scheduleAllPeriodicWork` call would be wasteful.
 *  - **D4**: explicit `connectionState != CONNECTED` gate at the top of
 *    `doWork()`. Mirrors `SyncManager.sync()` (`sync/SyncManager.kt:164`) —
 *    the network constraint can pass while the reachability probe is stale.
 *  - **D6 (no signed-in parent)**: `getPendingRequests()` returns
 *    `DeviceListError.AuthMissing`; the worker converts that to
 *    `Result.success()` so there is no retry loop when no parent session
 *    exists. Spec scenario "Worker with no signed-in parent is a no-op".
 */
@HiltWorker
class SolicitudesPollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val parentRepository: ParentRepository,
    private val clientProvider: SupabaseClientProvider,
    @Suppress("unused") private val authManager: DeviceAuthManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SolicitudesPollingWorker"
        const val WORK_NAME = "solicitudes_polling"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Ejecutando polling de solicitudes")

        // D4 — explicit connectivity gate. Mirrors SyncManager.sync() line 164.
        if (clientProvider.connectionState.value != ConnectionState.CONNECTED) {
            Log.d(TAG, "Sin conexión, fast success")
            return@withContext Result.success()
        }

        return@withContext try {
            val result = parentRepository.getPendingRequests()
            val list = result.getOrNull()
            if (list != null) {
                // D2 — push the freshly-fetched list into the singleton
                // flow so any collector (the live ParentViewModel) sees
                // the new rows.
                parentRepository.publishPendingRequests(list)
                Log.d(TAG, "Polling exitoso: ${list.size} solicitudes")
                Result.success()
            } else {
                val error = result.exceptionOrNull()
                if (error is DeviceListError.AuthMissing) {
                    // D6 — no signed-in parent. Surface as success so we
                    // do not loop on retries until the next sign-in.
                    Log.d(TAG, "Sin sesión de padre, fast success")
                    Result.success()
                } else {
                    Log.w(TAG, "Fallo transitorio: ${error?.message}")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en polling: ${e.message}")
            Result.retry()
        }
    }
}
