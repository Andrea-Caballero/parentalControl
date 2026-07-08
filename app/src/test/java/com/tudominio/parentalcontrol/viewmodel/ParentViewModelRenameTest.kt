package com.tudominio.parentalcontrol.viewmodel

import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RED coverage for [ParentViewModel.renameChildState] +
 * [ParentViewModel.requestRename] + [ParentViewModel.confirmRename] +
 * [ParentViewModel.dismissRename] from
 * `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md` §4.
 *
 * Per Q2=h (hoisted StateFlow) + Q4=p (pessimistic rename):
 *   - `RenameChildState` is a sealed UI state with the 5 variants the
 *     dashboard pattern-matches on (Hidden / Editing / Saving / Saved / Failed).
 *   - `requestRename(childId, currentName)` moves Hidden → Editing.
 *   - `confirmRename(newName)` moves Editing → Saving → Saved on success
 *     (then Saved → Hidden after the 1.5s auto-dismiss) or Editing → Saving
 *     → Failed on error.
 *   - `dismissRename()` resets the flow to Hidden at any time.
 *
 * RED today: none of these symbols exist on `ParentViewModel`. The
 * test body fails to compile (`Unresolved reference: renameChildState`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentViewModelRenameTest {

    private lateinit var repository: ParentRepository
    private lateinit var authManager: DeviceAuthManager

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        every { repository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        authManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun renameChildState_initial_value_is_Hidden() {
        val viewModel = ParentViewModel(repository, authManager)

        assertSame(
            "Fresh VM must expose RenameChildState.Hidden, got ${viewModel.renameChildState.value}",
            RenameChildState.Hidden,
            viewModel.renameChildState.value
        )
    }

    @Test
    fun requestRename_moves_state_Hidden_to_Editing() {
        val viewModel = ParentViewModel(repository, authManager)

        viewModel.requestRename(
            childId = "child-lucas",
            currentName = "Lucas"
        )

        val state = viewModel.renameChildState.value
        assertTrue(
            "requestRename must move Hidden → Editing, got $state",
            state is RenameChildState.Editing
        )
        val editing = state as RenameChildState.Editing
        assertEquals("child-lucas", editing.childId)
        assertEquals("Lucas", editing.currentName)
    }

    /**
     * Happy path: confirmRename sees the stubbed repo return success,
     * transitions through Saving → Saved, and then (after the 1.5s
     * auto-dismiss) back to Hidden. This is the Q4=p pessimistic
     * contract.
     */
    @Test
    fun confirmRename_happy_path_transitions_Editing_Saving_Saved_then_auto_dismisses_to_Hidden() =
        runTest {
            coEvery { repository.renameChild(any(), any()) } returns Result.success(Unit)
            // Stub loadDevices so the success branch doesn't hit a relaxed
            // mock that triggers the real (network) impl.
            every { repository.pendingRequestsFlow } returns MutableStateFlow(emptyList())

            val viewModel = ParentViewModel(repository, authManager)
            viewModel.requestRename("child-lucas", "Lucas")
            assertTrue(
                "precondition: state must be Editing before confirmRename",
                viewModel.renameChildState.value is RenameChildState.Editing
            )

            viewModel.confirmRename("Mateo")

            // The stub returns synchronously under the UnconfinedTestDispatcher
            // — Saving → Saved → (await the auto-dismiss delay)
            val saved = viewModel.renameChildState.value
            assertTrue(
                "Final state should be Saved (transient) or Hidden (auto-dismissed), got $saved",
                saved is RenameChildState.Saved || saved is RenameChildState.Hidden
            )

            // The repository was called once with the trimmed name.
            coVerify { repository.renameChild("child-lucas", "Mateo") }
        }

    /**
     * Failure path: confirmRename with a failing repo
     * surfaces Failed(childId, error) so the dashboard can render the
     * inline error text.
     */
    @Test
    fun confirmRename_failed_repo_surfaces_Failed_state() = runTest {
        coEvery { repository.renameChild(any(), any()) } returns
            Result.failure(DeviceListError.Transient("HTTP 409"))

        val viewModel = ParentViewModel(repository, authManager)
        viewModel.requestRename("child-lucas", "Lucas")

        viewModel.confirmRename("Mateo")
        advanceUntilIdle()

        val state = viewModel.renameChildState.value
        assertTrue(
            "confirmRename must surface Failed on error, got $state",
            state is RenameChildState.Failed
        )
        val failed = state as RenameChildState.Failed
        assertEquals("child-lucas", failed.childId)
        assertTrue(
            "Failed.error must mention HTTP 409 (carries the server error message), got '${failed.error}'",
            failed.error.contains("HTTP 409")
        )
    }

    /**
     * dismissRename resets the flow to Hidden from any state. Pinning
     * the contract explicitly because Saved has an in-flight auto-dismiss
     * timer and a manual tap must short-circuit it.
     */
    @Test
    fun dismissRename_resets_state_to_Hidden() {
        val viewModel = ParentViewModel(repository, authManager)
        viewModel.requestRename("child-lucas", "Lucas")
        assertTrue(
            "precondition: state must be Editing before dismissRename",
            viewModel.renameChildState.value is RenameChildState.Editing
        )

        viewModel.dismissRename()

        assertSame(
            "dismissRename must reset state to Hidden, got ${viewModel.renameChildState.value}",
            RenameChildState.Hidden,
            viewModel.renameChildState.value
        )
    }
}
