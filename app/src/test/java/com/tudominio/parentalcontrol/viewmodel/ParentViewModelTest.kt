package com.tudominio.parentalcontrol.viewmodel

import app.cash.turbine.test
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.domain.model.RequestStatus
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ParentViewModel.authenticateAsParent] and the typed
 * [DeviceListUiState.Error] branch (T6 of `hotfix-parent-auth-session`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentViewModelTest {

    private lateinit var repository: ParentRepository
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `authenticateAsParent delegates to auth manager with PARENT role`() = runTest {
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        val viewModel = ParentViewModel(repository, authManager)
        val result = viewModel.authenticateAsParent()

        assertTrue("authenticateAsParent must return success, got $result", result.isSuccess)
        coVerify { authManager.authenticateOrCreate(Role.PARENT) }
    }

    @Test
    fun `authenticateAsParent propagates auth manager failure`() = runTest {
        val failure = RuntimeException("auth failed")
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.failure(failure)

        val viewModel = ParentViewModel(repository, authManager)
        val result = viewModel.authenticateAsParent()

        assertTrue("authenticateAsParent must return failure, got $result", result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }

    @Test
    fun `loadDevices with not-authenticated failure maps to Error AuthMissing`() = runTest {
        coEvery { repository.getDevices() } returns Result.failure(
            IllegalStateException("not authenticated")
        )

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Error state with AuthMissing, got $state",
            state is DeviceListUiState.Error
        )
        val error = (state as DeviceListUiState.Error).reason
        assertEquals(
            "Error reason must be typed AuthMissing, not a raw string",
            DeviceListError.AuthMissing,
            error
        )
    }

    @Test
    fun `loadDevices with transient failure maps to Error Transient with reason`() = runTest {
        coEvery { repository.getDevices() } returns Result.failure(
            RuntimeException("HTTP 500 internal error")
        )

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Error state with Transient, got $state",
            state is DeviceListUiState.Error
        )
        val error = (state as DeviceListUiState.Error).reason
        assertTrue(
            "Error reason must be Transient, got $error",
            error is DeviceListError.Transient
        )
        assertTrue(
            "Transient reason must carry the underlying message, got $error",
            (error as DeviceListError.Transient).reason.contains("HTTP 500")
        )
    }

    @Test
    fun `loadDevices with successful fixture maps to Success with parsed devices`() = runTest {
        val fixtureDevices = listOf(
            ChildDevice(
                id = "dev-001",
                name = "Galaxy Tab S6 Lite",
                model = "SM-P610",
                appVersion = "1.0.0",
                policyVersion = 3,
                state = DeviceState.ACTIVE,
                lastSeenAt = "2026-06-19T20:55:00Z",
                isOnline = true
            ),
            ChildDevice(
                id = "dev-002",
                name = "Moto G32",
                model = "moto g32",
                appVersion = "1.0.0",
                policyVersion = 1,
                state = DeviceState.DOWNTIME,
                lastSeenAt = "2026-06-19T20:58:00Z",
                isOnline = true
            )
        )
        coEvery { repository.getDevices() } returns Result.success(fixtureDevices)

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertTrue(
            "Expected Success state, got $state",
            state is DeviceListUiState.Success
        )
        val success = state as DeviceListUiState.Success
        assertEquals(2, success.items.size)
        assertEquals("dev-001", success.items.first().id)
        assertEquals("Galaxy Tab S6 Lite", success.items.first().name)
    }

    @Test
    fun `loadDevices with empty list maps to Empty state`() = runTest {
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.loadDevices()

        val state = viewModel.deviceListState.value
        assertEquals(DeviceListUiState.Empty, state)
    }

    // T1.1 / T1.2 of `hotfix-parent-auth-cta-reload` — `authenticateAsParent()`
    // must chain `.onSuccess { loadDevices() }` so the dashboard reloads
    // devices after the user taps "Iniciar sesión como padre". The failure
    // path must NOT trigger loadDevices (per design Decision 3).
    @Test
    fun `authenticateAsParent_success_invokesLoadDevices`() = runTest {
        var getDevicesCalls = 0
        coEvery { repository.getDevices() } coAnswers {
            getDevicesCalls++
            Result.success(emptyList())
        }
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        val viewModel = ParentViewModel(repository, authManager)

        // init { loadDevices() } fired once. After auth success the VM must
        // re-invoke loadDevices — exact count becomes 2.
        assertEquals(
            "init block must have triggered exactly one loadDevices call",
            1, getDevicesCalls
        )

        viewModel.authenticateAsParent()

        assertEquals(
            "authenticateAsParent on success must trigger loadDevices (init + post-auth)",
            2, getDevicesCalls
        )
    }

    @Test
    fun `authenticateAsParent_failure_doesNotInvokeLoadDevices`() = runTest {
        var getDevicesCalls = 0
        coEvery { repository.getDevices() } coAnswers {
            getDevicesCalls++
            Result.failure(IllegalStateException("not authenticated"))
        }
        coEvery { authManager.authenticateOrCreate(Role.PARENT) } returns
            Result.failure(RuntimeException("auth failed"))

        val viewModel = ParentViewModel(repository, authManager)

        // init fired exactly one loadDevices → state = Error(AuthMissing).
        assertEquals(1, getDevicesCalls)
        assertTrue(
            "after init, state must be Error(AuthMissing)",
            viewModel.deviceListState.value is DeviceListUiState.Error &&
                (viewModel.deviceListState.value as DeviceListUiState.Error).reason ==
                DeviceListError.AuthMissing
        )

        viewModel.authenticateAsParent()

        // Failure path must NOT trigger a second loadDevices call.
        assertEquals(
            "authenticateAsParent on failure must NOT trigger loadDevices",
            1, getDevicesCalls
        )
        // And the error banner must remain visible.
        assertTrue(
            "after auth failure, state must remain Error(AuthMissing)",
            viewModel.deviceListState.value is DeviceListUiState.Error &&
                (viewModel.deviceListState.value as DeviceListUiState.Error).reason ==
                DeviceListError.AuthMissing
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `fix-parent-solicitudes-auto-poll` (Tasks 1.1).
    // -------------------------------------------------------------------------

    /**
     * T1.1 — In-flight `loadPendingRequests()` must dedupe. The design's
     * decision D5 gates repeat calls on the existing `_isLoading` flag:
     * when a fetch is already in flight, a second invocation returns
     * immediately without issuing another `getPendingRequests()` call.
     * This test pre-REDs the scenario: the only `getPendingRequests()` call
     * is the one fired by `init`, suspended on a `CompletableDeferred`; two
     * subsequent `loadPendingRequests()` invocations must NOT issue more
     * fetches.
     */
    @Test
    fun `loadPendingRequests_second_call_while_running_is_noop`() = runTest {
        var getPendingCalls = 0
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.getPendingRequests() } coAnswers {
            getPendingCalls++
            gate.await()
            Result.success(emptyList())
        }
        // Devices returns immediately so init's `loadDevices()` does not
        // hold `_isLoading=true` past init — that way the only thing
        // holding the flag is `loadPendingRequests()`'s suspended body.
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)
        // init's loadPendingRequests is launched and suspended on `gate`.
        assertEquals(
            "init must have launched exactly one loadPendingRequests",
            1, getPendingCalls
        )
        assertTrue(
            "isLoading must be true while the in-flight fetch is suspended",
            viewModel.isLoading.value
        )

        // Second and third explicit calls must dedupe while isLoading=true.
        viewModel.loadPendingRequests()
        viewModel.loadPendingRequests()

        assertEquals(
            "loadPendingRequests must dedupe while a previous call is in flight",
            1, getPendingCalls
        )

        // Release the gate and confirm the original fetch completes once.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "still exactly one fetch after the in-flight call completes",
            1, getPendingCalls
        )
        assertFalse(
            "isLoading must reset to false after the in-flight call completes",
            viewModel.isLoading.value
        )
    }

    /**
     * T1.1 — The VM must collect `ParentRepository.pendingRequestsFlow`
     * (a `StateFlow<List<TimeRequest>>` added by design D2) in its `init`
     * block so that any writer — the `SolicitudesPollingWorker` or
     * `loadPendingRequests()` itself via `publishPendingRequests()` —
     * surfaces new rows in `_pendingRequests` without a manual reload.
     */
    @Test
    fun `init_collects_pendingRequestsFlow_into_state`() = runTest {
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
        val flow = MutableStateFlow(fixture)
        every { repository.pendingRequestsFlow } returns flow
        coEvery { repository.getPendingRequests() } returns Result.success(emptyList())
        coEvery { repository.getDevices() } returns Result.success(emptyList())

        val viewModel = ParentViewModel(repository, authManager)

        // The collector must mirror the StateFlow value into `_pendingRequests`.
        assertEquals(
            "init's collector must mirror pendingRequestsFlow into pendingRequests",
            fixture, viewModel.pendingRequests.value
        )

        // Turbine: emit a fresh list, confirm the collector picks it up.
        val second = listOf(
            TimeRequest(
                id = "tr-2",
                deviceId = "dev-2",
                deviceName = "Moto G32",
                minutesRequested = 15,
                status = RequestStatus.PENDING,
                createdAt = "2026-06-30T00:01:00Z"
            )
        )
        viewModel.pendingRequests.test {
            // Skip the current replay (StateFlow replays its value on subscribe).
            assertEquals(fixture, awaitItem())
            flow.value = second
            assertEquals(second, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
