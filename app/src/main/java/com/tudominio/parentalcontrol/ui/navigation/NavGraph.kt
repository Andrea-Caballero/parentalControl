package com.tudominio.parentalcontrol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tudominio.parentalcontrol.copy.CopyManager
import com.tudominio.parentalcontrol.data.repository.TimeExtraRepository
import com.tudominio.parentalcontrol.pairing.PairingViewModel
import com.tudominio.parentalcontrol.pairing.ui.PairingScreen
import com.tudominio.parentalcontrol.ui.child.extra.ExtraTimeScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusScreen
import com.tudominio.parentalcontrol.ui.child.status.ChildStatusViewModel
import com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreen
import com.tudominio.parentalcontrol.ui.screen.OnboardingScreen
import com.tudominio.parentalcontrol.ui.screen.apps.AppsViewModel
import com.tudominio.parentalcontrol.viewmodel.ParentViewModel

/**
 * Top-level routing composable for the ParentalControl app.
 *
 * PR 4 of `align-with-guia-fedora44` (`app-entry-routing` spec) hoists
 * the inline `when { ... }` block that previously lived in
 * `MainActivity.onCreate` so the activity only handles lifecycle +
 * deeplink plumbing.
 *
 * The initial route is decided by [resolveInitialRoute] and then the
 * composable manages its own sub-navigation between the screens that
 * share a top-level entry point:
 *
 *  - [NavRoute.Onboarding]   → unpaired device; advances to Dashboard or
 *                              PairingFlow when the user picks a role.
 *  - [NavRoute.PairingFlow]  → child device during pairing; cancels back
 *                              to Onboarding.
 *  - [NavRoute.Dashboard]    → paired parent (initial), or unpaired user
 *                              who picked "parent" on Onboarding.
 *  - [NavRoute.ChildStatus]  → paired child (initial).
 *  - [NavRoute.ExtraTime]    → child requested extra time; returns to
 *                              ChildStatus when the request is sent or
 *                              cancelled.
 *
 * Every ViewModel and manager is passed as a parameter so `MainActivity`
 * resolves them via `hiltViewModel()` / Hilt singletons and tests can
 * inject mocks directly (no Hilt graph needed in the Robolectric tests).
 *
 * `prefilledPairingCode` is hoisted state in `MainActivity` so the
 * `parentalcontrol://pair?code=...` deeplink survives both `onCreate`
 * and `onNewIntent`. `onPairingComplete` is the activity-level callback
 * the [PairingScreen] invokes once pairing succeeds; in `MainActivity`
 * it calls `recreate()` so the device's auth state is re-read from disk.
 */
@Composable
fun NavGraph(
    isPaired: Boolean,
    isChildDevice: Boolean,
    prefilledPairingCode: String?,
    parentViewModel: ParentViewModel,
    appsViewModel: AppsViewModel,
    pairingViewModel: PairingViewModel,
    childStatusViewModel: ChildStatusViewModel,
    copyManager: CopyManager,
    timeExtraRepository: TimeExtraRepository,
    deviceId: String,
    onPairingComplete: () -> Unit
) {
    var route by remember { mutableStateOf(resolveInitialRoute(isPaired, isChildDevice)) }

    when (route) {
        NavRoute.Onboarding -> OnboardingScreen(
            onSelectParent = { route = NavRoute.Dashboard },
            onSelectChild = { route = NavRoute.PairingFlow }
        )
        NavRoute.PairingFlow -> PairingScreen(
            viewModel = pairingViewModel,
            onPairingComplete = onPairingComplete,
            onCancel = { route = NavRoute.Onboarding },
            prefilledCode = prefilledPairingCode
        )
        NavRoute.Dashboard -> DashboardScreen(
            viewModel = parentViewModel,
            appsViewModel = appsViewModel,
            onNavigateToDevice = { },
            onNavigateToRequests = { }
        )
        NavRoute.ChildStatus -> ChildStatusScreen(
            viewModel = childStatusViewModel,
            copyManager = copyManager,
            onRequestExtraTime = { route = NavRoute.ExtraTime }
        )
        NavRoute.ExtraTime -> ExtraTimeScreen(
            copyManager = copyManager,
            repository = timeExtraRepository,
            deviceId = deviceId,
            onBack = { route = NavRoute.ChildStatus },
            onRequestSent = { route = NavRoute.ChildStatus },
            onError = { }
        )
    }
}

/**
 * The set of top-level routes [NavGraph] can render.
 *
 * Exposed (internal) so unit tests can assert the routing decision in
 * [resolveInitialRoute] without spinning up Compose. The enum is internal
 * (not `private`) so [NavGraphTest] in the same module can reference it
 * for the pure-function tests.
 */
internal enum class NavRoute {
    Onboarding,
    PairingFlow,
    Dashboard,
    ChildStatus,
    ExtraTime
}

/**
 * Pure-function decision for the entry-point route.
 *
 * Extracted from the composable so tests can pin the 3-way branch with
 * trivial assertions — no Compose, no Hilt, no Robolectric needed for
 * the routing contract itself. The Compose test in [NavGraphTest]
 * complements this by proving the chosen route actually renders.
 */
internal fun resolveInitialRoute(isPaired: Boolean, isChildDevice: Boolean): NavRoute = when {
    !isPaired -> NavRoute.Onboarding
    isChildDevice -> NavRoute.ChildStatus
    else -> NavRoute.Dashboard
}
