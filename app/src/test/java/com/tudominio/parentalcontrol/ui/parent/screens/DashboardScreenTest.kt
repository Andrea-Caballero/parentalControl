package com.tudominio.parentalcontrol.ui.parent.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.repository.DeviceListError
import com.tudominio.parentalcontrol.data.repository.ParentRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the [DashboardScreen] error banner CTA swap (T8 of
 * `hotfix-parent-auth-session`).
 *
 * Per the spec delta at
 * `openspec/changes/hotfix-parent-auth-session/specs/parent-device-list/spec.md`:
 *  - When the error is [DeviceListError.AuthMissing], the banner shows a
 *    single "Iniciar sesión como padre" CTA (no retry/back).
 *  - When the error is [DeviceListError.Transient], the banner shows
 *    "Reintentar" + "Cerrar" CTAs as before.
 *
 * The previous behavior unconditionally rendered retry/back, which made
 * the auth-missing case unfixable from the UI. T8 fixes the contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xhdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: ParentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        // `fix-parent-solicitudes-auto-poll` — VM's `init` block now
        // collects `repository.pendingRequestsFlow`. Stub a default empty
        // flow so the collector doesn't NPE on the relaxed mock.
        every { mockRepository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        viewModel = ParentViewModel(mockRepository, mockAuthManager)
        // Seed successful child-device load so the default state is not a
        // spinner — we'll override _deviceListState per test as needed.
        coEvery { mockRepository.getDevices() } returns Result.success(
            listOf(
                ChildDevice(
                    id = "dev-1",
                    name = "Test Device",
                    model = "Pixel 7",
                    appVersion = "1.0.0",
                    policyVersion = 1,
                    state = DeviceState.ACTIVE,
                    lastSeenAt = "2026-06-19T20:00:00Z",
                    isOnline = true
                )
            )
        )
        coEvery { mockAuthManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seedDeviceListState(error: DeviceListError) {
        // Use reflection to set the private StateFlow value — avoids
        // running the full loadDevices() coroutine for a focused test.
        val stateField = ParentViewModel::class.java.getDeclaredField("_deviceListState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<DeviceListUiState>
        stateFlow.value = DeviceListUiState.Error(error)
    }

    /**
     * Mirror of [seedDeviceListState] for the Success branch — used by the
     * 3 RED tests in `feature-pluralize-empty-state-and-add-n-device-tests`
     * to pin N-device rendering in `DashboardScreen`. Reads
     * `app/src/main/assets/mock-supabase/devices.json` fixtures (ACTIVE /
     * DOWNTIME / LOCKED) directly so the test stays in sync with the
     * shipped fixture data.
     */
    private fun seedSuccessState(items: List<ChildDevice>) {
        val stateField = ParentViewModel::class.java.getDeclaredField("_deviceListState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<DeviceListUiState>
        stateFlow.value = DeviceListUiState.Success(items = items)
    }

    /**
     * Three-device fixture mirroring
     * `app/src/main/assets/mock-supabase/devices.json`. Pinned here (not
     * imported from JSON) so the assertion stays self-contained and the
     * test does not depend on the assets dir being on the unit-test
     * classpath.
     */
    private val threeDeviceFixtures: List<ChildDevice> = listOf(
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
        ),
        ChildDevice(
            id = "dev-003",
            name = "Pixel 7a",
            model = "GWKK3",
            appVersion = "1.0.0",
            policyVersion = 7,
            state = DeviceState.LOCKED,
            lastSeenAt = "2026-06-19T20:59:30Z",
            isOnline = true
        )
    )

    /**
     * Mock the repository's `getPendingRequests()` so each call returns
     * success and is counted. Used by the tab-tap test below. MUST be
     * called BEFORE constructing the VM so init's launch sees the
     * counter stub.
     */
    private fun setupPendingRequestsCounter(
        repository: ParentRepository
    ): () -> Int {
        var count = 0
        coEvery { repository.getPendingRequests() } answers {
            count++
            Result.success(emptyList())
        }
        return { count }
    }

    @Test
    fun error_banner_authMissing_shows_sign_in_cta_only() {
        seedDeviceListState(DeviceListError.AuthMissing)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // The sign-in CTA MUST be visible.
        composeTestRule.onNodeWithText("Iniciar sesión como padre").assertIsDisplayed()
        // The retry/back CTAs MUST NOT be visible for an auth error.
        val retry = runCatching {
            composeTestRule.onNodeWithText("Reintentar").assertExists()
            true
        }.getOrDefault(false)
        val back = runCatching {
            composeTestRule.onNodeWithText("Cerrar").assertExists()
            true
        }.getOrDefault(false)
        org.junit.Assert.assertFalse(
            "Retry CTA must NOT appear for AuthMissing, found=$retry",
            retry
        )
        org.junit.Assert.assertFalse(
            "Back CTA must NOT appear for AuthMissing, found=$back",
            back
        )
    }

    @Test
    fun error_banner_transient_shows_retry_and_back() {
        seedDeviceListState(DeviceListError.Transient("HTTP 500"))

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // The retry + back CTAs MUST be visible.
        composeTestRule.onNodeWithText("Reintentar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cerrar").assertIsDisplayed()
        // The sign-in CTA MUST NOT be visible for a transient error.
        val signIn = runCatching {
            composeTestRule.onNodeWithText("Iniciar sesión como padre").assertExists()
            true
        }.getOrDefault(false)
        org.junit.Assert.assertFalse(
            "Sign-in CTA must NOT appear for Transient, found=$signIn",
            signIn
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `fix-parent-solicitudes-auto-poll` (Task 1.4).
    //
    // The dashboard's `DashboardScaffold` carries a `LaunchedEffect(selectedTab)`
    // that fires `viewModel.loadPendingRequests()` whenever the parent
    // switches to the Solicitudes tab (index 1). Tabs other than 1 must NOT
    // trigger a Solicitudes fetch. This test asserts both halves.
    // -------------------------------------------------------------------------

    @Test
    fun tab_tap_to_solicitudes_invokes_loadPendingRequests_other_tabs_do_not() {
        // Fresh mocks so this test owns its VM and its counters.
        val mockRepository = mockk<ParentRepository>(relaxed = true)
        val mockAuthManager = mockk<DeviceAuthManager>(relaxed = true)
        // VM's init block now collects pendingRequestsFlow — stub a default
        // empty flow so the collector doesn't NPE on the relaxed mock.
        every { mockRepository.pendingRequestsFlow } returns MutableStateFlow(emptyList())
        coEvery { mockRepository.getDevices() } returns Result.success(emptyList())
        coEvery { mockAuthManager.authenticateOrCreate(Role.PARENT) } returns Result.success(Unit)

        // Set up the counter BEFORE constructing the VM so init's
        // launch sees the counter stub.
        val pendingCalls = setupPendingRequestsCounter(mockRepository)

        val tabViewModel = ParentViewModel(mockRepository, mockAuthManager)
        // init's loadPendingRequests fired exactly once.
        assertEquals(
            "init must have launched exactly one loadPendingRequests",
            1, pendingCalls()
        )

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = tabViewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // Initial selectedTab is 0 (Devices). The LaunchedEffect body is
        // gated to `selectedTab == 1`, so no extra Solicitudes fetch.
        assertEquals(
            "Default Devices tab must NOT trigger a Solicitudes fetch",
            1, pendingCalls()
        )

        // Tap Solicitudes → selectedTab becomes 1 → LaunchedEffect fires.
        composeTestRule.onNodeWithText("Solicitudes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Solicitudes tab tap must trigger a Solicitudes fetch",
            2, pendingCalls()
        )

        // Tap Devices → selectedTab becomes 0 → LaunchedEffect re-keys but
        // body is gated; no fetch.
        composeTestRule.onNodeWithText("Dispositivos").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Devices tab tap must NOT trigger a Solicitudes fetch",
            2, pendingCalls()
        )

        // Re-tap Solicitudes → another fresh fetch.
        composeTestRule.onNodeWithText("Solicitudes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "Re-tap on Solicitudes must trigger a fresh fetch",
            3, pendingCalls()
        )
    }

    // -------------------------------------------------------------------------
    // RED coverage for `feature-pluralize-empty-state-and-add-n-device-tests`.
    //
    // The data layer is already 1:N (ParentRepository.getDevices() returns
    // List<ChildDevice>) but the dashboard's DeviceList only had zero test
    // coverage for the N-device rendering path. These 3 tests pin that:
    //   1.2.1 — the LazyColumn renders exactly N DeviceCards for an N-item
    //           Success state (counted via the `device_card` testTag added
    //           to DeviceCard at DeviceComponents.kt:36-39).
    //   1.2.2 — each card surfaces its `device.name` and `device.model`.
    //   1.2.3 — each card surfaces its state-badge label (Activo /
    //           Hora de dormir / Bloqueado).
    // -------------------------------------------------------------------------

    @Test
    fun success_state_with_3_devices_renders_3_device_card_testTags() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)
    }

    @Test
    fun success_state_with_3_devices_renders_per_card_name_and_model() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // `assertExists` (not `assertIsDisplayed`) because the third card
        // sits below the Robolectric default viewport; the semantic intent
        // is "the card renders the device's name and model into the tree"
        // which is met by existence. Visibility is exercised separately by
        // the count assertion in 1.2.1 and by instrumented tests in CI.

        // dev-001 (ACTIVE)
        composeTestRule.onNodeWithText("Galaxy Tab S6 Lite").assertExists()
        composeTestRule.onNodeWithText("SM-P610").assertExists()
        // dev-002 (DOWNTIME)
        composeTestRule.onNodeWithText("Moto G32").assertExists()
        composeTestRule.onNodeWithText("moto g32").assertExists()
        // dev-003 (LOCKED)
        composeTestRule.onNodeWithText("Pixel 7a").assertExists()
        composeTestRule.onNodeWithText("GWKK3").assertExists()
    }

    @Test
    fun success_state_with_3_devices_renders_per_card_state_badge() {
        seedSuccessState(threeDeviceFixtures)

        val appsVm = mockk<AppsViewModel>(relaxed = true)
        composeTestRule.setContent {
            ParentalControlTheme {
                DashboardScreen(viewModel = viewModel, appsViewModel = appsVm)
            }
        }
        composeTestRule.waitForIdle()

        // `assertExists` for the same reason as above: per-card badge
        // labels are rendered into the tree regardless of viewport.

        // Each fixture's badge label, per DeviceComponents.kt:183-188.
        composeTestRule.onNodeWithText("Activo").assertExists()
        composeTestRule.onNodeWithText("Hora de dormir").assertExists()
        composeTestRule.onNodeWithText("Bloqueado").assertExists()
    }
}
