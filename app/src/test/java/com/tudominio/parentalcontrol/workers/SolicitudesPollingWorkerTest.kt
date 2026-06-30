package com.tudominio.parentalcontrol.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.network.ConnectionState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SolicitudesPollingWorker].
 *
 * The worker mirrors `HeartbeatWorker`'s lifecycle (design D3) but is
 * read-only — it fetches pending time requests and pushes the result
 * through `ParentRepository.pendingRequestsFlow` so the
 * `ParentViewModel` collector updates the UI without a manual reload.
 *
 * Scenarios covered:
 *  - **Offline** (design D4): `connectionState != CONNECTED` → `Result.success()`
 *    no-op, no fetch attempted, no `publishPendingRequests` call.
 *  - **No signed-in parent**: `getPendingRequests()` returns
 *    `DeviceListError.AuthMissing` → `Result.success()` no-op (spec scenario
 *    "Worker with no signed-in parent is a no-op").
 *  - **Success**: `getPendingRequests()` returns a list → `publishPendingRequests`
 *    called and `Result.success()`.
 *
 * Pattern follows `OutboxDrainerTest`: Robolectric + `WorkManagerTestInitHelper`
 * + MockK for the repository / provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SolicitudesPollingWorkerTest {

    private lateinit var context: Context
    private lateinit var parentRepository: ParentRepository
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        parentRepository = mockk(relaxed = true)
        clientProvider = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        // No persistent state; mocks are GC'd per test.
    }

    private fun newWorker(
        repository: ParentRepository = parentRepository,
        provider: SupabaseClientProvider = clientProvider,
        auth: DeviceAuthManager = authManager
    ): SolicitudesPollingWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return SolicitudesPollingWorker(
                    appContext,
                    workerParameters,
                    repository,
                    provider,
                    auth
                )
            }
        }
        return TestListenableWorkerBuilder
            .from(context, SolicitudesPollingWorker::class.java)
            .setWorkerFactory(factory)
            .build() as SolicitudesPollingWorker
    }

    /**
     * Scenario "Worker skips when offline" — design D4:
     *   `connectionState.value != ConnectionState.CONNECTED` → return
     *   `Result.success()` without fetching.
     */
    @Test
    fun doWork_whenOffline_returnsSuccess_withoutFetching() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "offline worker must return success without throwing",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 0) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Scenario "Worker with no signed-in parent is a no-op" — when the
     * repository call returns `AuthMissing`, the worker returns
     * `Result.success()` (no retry loop) and does NOT mutate
     * `pendingRequestsFlow`.
     */
    @Test
    fun doWork_whenNoSession_returnsSuccess_withoutMutatingState() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns
            Result.failure(DeviceListError.AuthMissing)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "AuthMissing must surface as success (no retry, no loop)",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Scenario "Worker retries on transient failure" — when
     * `getPendingRequests()` returns a non-AuthMissing failure
     * (network drop, HTTP 5xx, parse error), the worker returns
     * `Result.retry()` so WorkManager applies exponential backoff.
     */
    @Test
    fun doWork_returns_retry_on_transient_failure() = runBlocking {
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns
            Result.failure(DeviceListError.Transient("server 503"))

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "transient failure must surface as retry (WorkManager backoff)",
            ListenableWorker.Result.retry(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 0) { parentRepository.publishPendingRequests(any()) }
    }

    /**
     * Happy path — successful fetch pushes the parsed list into
     * `pendingRequestsFlow` (design D2) and returns `Result.success()`.
     * Spec scenario "Worker success pushes fresh data to the StateFlow".
     */
    @Test
    fun doWork_onSuccess_publishesToPendingRequestsFlow() = runBlocking {
        val fixture = listOf(
            TimeRequest(
                id = "tr-1",
                deviceId = "dev-1",
                deviceName = "Poco X6 Pro",
                minutesRequested = 30,
                reason = "homework",
                status = RequestStatus.PENDING,
                createdAt = "2026-06-30T00:00:00Z"
            )
        )
        every { clientProvider.connectionState } returns
            kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.CONNECTED)
        coEvery { parentRepository.getPendingRequests() } returns Result.success(fixture)

        val worker = newWorker()
        val result = worker.doWork()

        assertEquals(
            "successful fetch must return success",
            ListenableWorker.Result.success(), result
        )
        coVerify(exactly = 1) { parentRepository.getPendingRequests() }
        coVerify(exactly = 1) { parentRepository.publishPendingRequests(fixture) }
    }
}
