package com.tudominio.parentalcontrol.viewmodel

import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
}
