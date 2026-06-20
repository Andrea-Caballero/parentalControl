package com.tudominio.parentalcontrol.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.TimeRequest
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.ui.theme.ParentalControlTheme
import com.tudominio.parentalcontrol.viewmodel.DeviceListUiState
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the routing logic of [NavGraph].
 *
 * PR 4 of `align-with-guia-fedora44` extracted the top-level `when` block
 * from `MainActivity` into a dedicated `@Composable fun NavGraph(...)`.
 * Per the `app-entry-routing` spec, the routing depends on:
 *
 *   - `isPaired == false`         → [OnboardingScreen]  (unpaired device)
 *   - `isPaired && isChildDevice` → [ChildStatusScreen] (paired child)
 *   - `isPaired && !isChildDevice`→ [DashboardScreen]   (paired parent)
 *
 * Two layers of tests pin the contract:
 *
 *   1. **Pure-function tests** for [resolveInitialRoute]. The decision is
 *      a 3-way branch on two booleans, so the pure function gives the
 *      clearest possible assertion and is the primary regression net.
 *
 *   2. **Compose / Robolectric tests** for [NavGraph] itself. These pass
 *      mock ViewModels + managers to the composable so the test does not
 *      depend on the Hilt graph. `MainActivity` resolves the real ones
 *      via `hiltViewModel()` / Hilt singletons and forwards them to
 *      [NavGraph] in production.
 *
 * Test runner: `./gradlew :app:testDebugUnitTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavGraphTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val parentViewModel: ParentViewModel = mockk(relaxed = true)
    private val appsViewModel: AppsViewModel = mockk(relaxed = true)
    private val pairingViewModel: PairingViewModel = mockk(relaxed = true)
    private val childStatusViewModel: ChildStatusViewModel = mockk(relaxed = true)
    private val copyManager: CopyManager = mockk(relaxed = true)
    private val timeExtraRepository: TimeExtraRepository = mockk(relaxed = true)

    init {
        // The screens read several `StateFlow<T>` properties off their
        // ViewModels (`devices`, `uiState`, etc.) via `collectAsState()`,
        // which performs an unchecked cast to the generic `T`. A
        // relaxed-mockk returns `Any` for those calls, so the cast blows
        // up before the screen renders. Stub each flow with a real
        // `MutableStateFlow` carrying an empty / default value so the
        // cast resolves cleanly. This keeps the test focused on the
        // routing decision, not on the screen content itself.
        every { parentViewModel.devices } returns MutableStateFlow<List<ChildDevice>>(emptyList())
        every { parentViewModel.deviceListState } returns MutableStateFlow(DeviceListUiState.Loading)
        every { parentViewModel.pendingRequests } returns MutableStateFlow<List<TimeRequest>>(emptyList())
        every { parentViewModel.isLoading } returns MutableStateFlow(false)
        every { parentViewModel.error } returns MutableStateFlow<String?>(null)

        every { childStatusViewModel.uiState } returns MutableStateFlow(
            com.tudominio.parentalcontrol.ui.child.status.ChildStatusUiState.Content(
                timeRemaining = 60L,
                timeUsedToday = 0L,
                dailyLimit = 120L,
                nextBlockTime = null,
                warningLevel = com.tudominio.parentalcontrol.ui.child.status.WarningLevel.NONE,
                hasPendingRequest = false,
                allowedAppsNow = emptyList()
            )
        )
        every { childStatusViewModel.warningLevel } returns MutableStateFlow(
            com.tudominio.parentalcontrol.ui.child.status.WarningLevel.NONE
        )
        every { childStatusViewModel.pendingTimeRequest } returns MutableStateFlow(null)
        every { childStatusViewModel.rewardBalance } returns MutableStateFlow(0L)
        every { childStatusViewModel.degradationCauses } returns MutableStateFlow(emptyList())
        every { childStatusViewModel.showRecoveryDialog } returns MutableStateFlow(false)
        every { childStatusViewModel.timeRemaining } returns MutableStateFlow(0L)
        // `events` is a SharedFlow that ChildStatusScreen collects inside a
        // LaunchedEffect; stub it with a real (empty) SharedFlow so the
        // collect{} does not blow up on a mockk Object.
        every { childStatusViewModel.events } returns kotlinx.coroutines.flow.MutableSharedFlow()
    }

    // -----------------------------------------------------------------
    // 1. Pure-function tests: resolveInitialRoute covers the 3 branches
    // -----------------------------------------------------------------

    @Test
    fun resolveInitialRoute_unpairedDevice_returnsOnboarding() {
        assertEquals(
            NavRoute.Onboarding,
            resolveInitialRoute(isPaired = false, isChildDevice = false)
        )
        assertEquals(
            NavRoute.Onboarding,
            resolveInitialRoute(isPaired = false, isChildDevice = true)
        )
    }

    @Test
    fun resolveInitialRoute_pairedChildDevice_returnsChildStatus() {
        assertEquals(
            NavRoute.ChildStatus,
            resolveInitialRoute(isPaired = true, isChildDevice = true)
        )
    }

    @Test
    fun resolveInitialRoute_pairedParentDevice_returnsDashboard() {
        assertEquals(
            NavRoute.Dashboard,
            resolveInitialRoute(isPaired = true, isChildDevice = false)
        )
    }

    // -----------------------------------------------------------------
    // 2. Compose tests: NavGraph composes the correct screen
    // -----------------------------------------------------------------

    @Test
    fun navGraph_unpairedDevice_composesOnboardingScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = false,
                    isChildDevice = false,
                    prefilledPairingCode = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "",
                    onPairingComplete = { }
                )
            }
        }

        // OnboardingScreen renders the role-selection buttons.
        composeTestRule.onNodeWithText("Soy el padre").assertExists()
        composeTestRule.onNodeWithText("Soy el hijo").assertExists()
    }

    @Test
    fun navGraph_pairedChildDevice_composesChildStatusScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = true,
                    isChildDevice = true,
                    prefilledPairingCode = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "test-device",
                    onPairingComplete = { }
                )
            }
        }

        // ChildStatusScreen shows "minutos restantes" as a label.
        composeTestRule.onNodeWithText("minutos restantes").assertExists()
    }

    @Test
    fun navGraph_pairedParentDevice_composesDashboardScreen() {
        composeTestRule.setContent {
            ParentalControlTheme {
                NavGraph(
                    isPaired = true,
                    isChildDevice = false,
                    prefilledPairingCode = null,
                    parentViewModel = parentViewModel,
                    appsViewModel = appsViewModel,
                    pairingViewModel = pairingViewModel,
                    childStatusViewModel = childStatusViewModel,
                    copyManager = copyManager,
                    timeExtraRepository = timeExtraRepository,
                    deviceId = "",
                    onPairingComplete = { }
                )
            }
        }

        // DashboardScreen's top bar reads "Control Parental".
        composeTestRule.onNodeWithText("Control Parental").assertExists()
    }
}
